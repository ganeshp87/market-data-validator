# Future Development Roadmap
## Market Data Stream Validator — Path to Production Grade

> **Audience:** Senior developer with full codebase context  
> **Date generated:** April 2026  
> **Current branch:** `feature/simulator-adapter`  
> **Test suite baseline:** 603 backend tests, 185 frontend tests

---

## Executive Summary

The Market Data Stream Validator is a well-architected, single-node real-time validation platform built on Java 21 + Spring Boot 3.3.0 + React 18. The core validation logic — eight domain validators, BackpressureQueue, per-feed symbol scoping, clock-offset calibration — is solid and production-quality in isolation. The system handles the full ingestion-to-SSE pipeline correctly, the 603-test backend suite covers edge cases thoroughly, and the chaos simulator (LVWRChaosSimulator with 12 failure types) makes it genuinely testable.

The production-readiness gaps are structural rather than logical: there is no authentication on any of the 38 API endpoints, no observability stack (no metrics, no tracing, no health indicators), no CI/CD pipeline, and the database and SSE emitter state are both process-local, making the system fundamentally single-node. None of these are design failures — they are the natural result of a portfolio build — but they are the gaps a production review would flag immediately.

The single highest-value improvement is adding Spring Boot Actuator + Micrometer with a Prometheus scrape endpoint. It takes less than a day, costs zero architectural risk, and immediately gives you queue depth, validator processing time, drop rate, and SSE emitter count as observable metrics — which transforms the system from a demo into something an SRE can actually monitor.

Reaching true production grade (auth, CI/CD, PostgreSQL, Kafka, Kubernetes) is an 8–12 week effort for a single senior developer, or 4–6 weeks with two. The work is well-defined; the complexity is integration, not logic.

---

## Master Summary Table

| # | Item | Phase | Effort | Business Value | Portfolio Value | Priority |
|---|------|--------|--------|----------------|-----------------|----------|
| 1 | GitHub Actions CI/CD | 1 | 2–3 days | High | High | P1 |
| 2 | Spring Boot Actuator + Micrometer | 1 | 1 day | Very High | High | P1 |
| 3 | Springdoc OpenAPI (Swagger UI) | 1 | 0.5 days | Medium | High | P1 |
| 4 | JaCoCo coverage gate in CI | 1 | 0.5 days | High | Medium | P1 |
| 5 | Spring Security + JWT | 1 | 3–4 days | Very High | High | P1 |
| 6 | Flyway DB migrations | 1 | 1 day | High | Medium | P1 |
| 7 | Structured logging MDC fields | 1 | 0.5 days | High | Low | P1 |
| 8 | PostgreSQL + TimescaleDB migration | 2 | 4–5 days | High | Medium | P2 |
| 9 | Micrometer → Prometheus + Grafana | 2 | 2–3 days | Very High | High | P2 |
| 10 | OpenTelemetry distributed tracing | 2 | 2–3 days | High | High | P2 |
| 11 | Testcontainers for integration tests | 2 | 2 days | High | Medium | P2 |
| 12 | Kafka integration (replace BackpressureQueue) | 2 | 1–2 weeks | High | Very High | P2 |
| 13 | Redis for shared SSE state | 2 | 3–5 days | Medium | High | P2 |
| 14 | Kubernetes deployment manifests | 2 | 3–5 days | Medium | Very High | P2 |
| 15 | ArchUnit architecture tests | 2 | 1 day | Medium | Medium | P2 |
| 16 | LMAX Disruptor (replace ArrayBlockingQueue) | 3 | 3–5 days | Medium | Very High | P3 |
| 17 | VolumeProfileValidator | 3 | 3–4 days | High | High | P3 |
| 18 | CrossFeedCorrelationValidator | 3 | 2–3 days | High | High | P3 |
| 19 | ML anomaly detection integration | 3 | 3–6 weeks | Medium | Very High | P3 |
| 20 | Off-heap buffers (Chronicle Map) | 3 | 1 week | Low | Medium | P3 |
| 21 | PIT mutation testing | 3 | 1 day | Medium | Medium | P3 |
| 22 | API versioning (/api/v1/) | 3 | 1–2 days | Low | Low | P3 |
| 23 | OWASP dependency-check in CI | 1 | 0.5 days | High | Low | P1 |
| 24 | Data retention + tick table partitioning | 3 | 2–3 days | Medium | Low | P3 |

---

## Section 1 — CI/CD Pipeline

### Current State

There is no `.github/workflows/` directory. All builds are manual via `./build-dist.sh`. The 603 backend tests and 185 frontend tests run only when a developer remembers to run them locally. No coverage threshold is enforced.

### GitHub Actions Workflow Design

**Trigger strategy:**
- Every push to any branch: run build + test
- Every pull request to `main`: run full quality gate (blocks merge)
- Push to `main`: build Docker image, push to registry (GHCR or Docker Hub)

**Recommended workflow structure:**

```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: ['**']
  pull_request:
    branches: [main]

jobs:
  backend-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      - name: Run backend tests with coverage
        working-directory: backend
        run: ./mvnw verify -Pci
      - name: Upload JaCoCo coverage report
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: backend/target/site/jacoco/

  frontend-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - run: cd frontend && npm ci && npm test

  quality-gate:
    needs: [backend-test, frontend-test]
    runs-on: ubuntu-latest
    if: github.event_name == 'pull_request'
    steps:
      - name: Download coverage report
        uses: actions/download-artifact@v4
        with: { name: jacoco-report }
      - name: Check coverage threshold
        run: |
          # Parse JaCoCo XML, fail if line coverage < 80%
          # Use jacoco-badge-generator action or custom script

  docker-publish:
    needs: [backend-test, frontend-test]
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v4
      - name: Build frontend
        run: cd frontend && npm ci && npm run build
      - name: Copy to backend static
        run: cp -r frontend/dist/* backend/src/main/resources/static/
      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          push: true
          tags: ghcr.io/${{ github.repository }}:latest,${{ github.sha }}
```

**Jobs running in parallel vs sequential:**
- `backend-test` and `frontend-test` run in parallel (independent)
- `quality-gate` runs after both (needs both to pass)
- `docker-publish` runs after both tests pass, only on main

**Estimated pipeline duration:** 4–6 minutes total (Maven with cache ~3 min, Vitest ~30s, Docker build ~2 min)

### Quality Gates

**Block merge on:**
- Any backend test failure
- Any frontend test failure
- JaCoCo line coverage below 75% (conservative — current coverage is likely 70–80% given 603 tests for the backend code size; measure first before setting threshold)
- SpotBugs HIGH/CRITICAL findings
- Checkstyle violations (Google Java style)

**Warn but don't block:**
- JaCoCo branch coverage below 60%
- SpotBugs MEDIUM findings
- Frontend test coverage below 70%

**Recommended pom.xml additions for CI profile:**

```xml
<profiles>
  <profile>
    <id>ci</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.jacoco</groupId>
          <artifactId>jacoco-maven-plugin</artifactId>
          <version>0.8.12</version>
          <executions>
            <execution>
              <goals><goal>prepare-agent</goal></goals>
            </execution>
            <execution>
              <id>report</id>
              <phase>verify</phase>
              <goals><goal>report</goal><goal>check</goal></goals>
              <configuration>
                <rules>
                  <rule>
                    <element>BUNDLE</element>
                    <limits>
                      <limit>
                        <counter>LINE</counter>
                        <value>COVEREDRATIO</value>
                        <minimum>0.75</minimum>
                      </limit>
                    </limits>
                  </rule>
                </rules>
              </configuration>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>com.github.spotbugs</groupId>
          <artifactId>spotbugs-maven-plugin</artifactId>
          <version>4.8.6.0</version>
          <executions>
            <execution>
              <phase>verify</phase>
              <goals><goal>check</goal></goals>
            </execution>
          </executions>
          <configuration>
            <failOnError>true</failOnError>
            <threshold>High</threshold>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

**OWASP dependency-check** (add to the quality-gate job, weekly schedule):

```yaml
  dependency-check:
    runs-on: ubuntu-latest
    schedule:
      - cron: '0 6 * * 1'  # Weekly Monday 6am
    steps:
      - uses: actions/checkout@v4
      - name: OWASP Dependency Check
        run: cd backend && ./mvnw org.owasp:dependency-check-maven:check
        # Add dependency-check-maven plugin to pom.xml
```

### What CI/CD Would Have Caught

In this project's development history (visible in git log), several bugs were fixed in commits like `fix: resolve 19 bugs across critical, high, medium and low severity`. A CI pipeline with:
- **Test-on-push** would have caught the TOCTOU race in BackpressureQueue before it reached the fix commit
- **Coverage gate** would have flagged the per-feed validator scoping gap before it was introduced
- **SpotBugs** would have flagged the CopyOnWriteArrayList-with-remove patterns in StreamController
- **Dependency scanning** gives you CVE alerts for the SQLite JDBC driver before they hit production

---

## Section 2 — Message Broker Integration (Kafka / Pulsar)

### Current Architecture Limitations

`BackpressureQueue` is an `ArrayBlockingQueue<Tick>(10_000)` with a dedicated consumer thread. This is correct for a single-node system, but has three hard limits:

1. **No durability.** If the JVM crashes between `queue.offer()` and `ValidatorEngine.onTick()`, those ticks are gone. `SessionRecorder` only persists ticks *after* validation. There is no recovery point.

2. **Single consumer.** `BackpressureQueue` has one consumer thread polling at 100ms intervals. At 100K ticks/sec, the queue saturates in 100ms and starts dropping. Adding a second consumer thread requires careful locking that the current design doesn't support cleanly.

3. **Single node ceiling.** The `FeedManager`, `BackpressureQueue`, `ValidatorEngine`, and all `CopyOnWriteArrayList` SSE emitter lists exist in one JVM. There is no mechanism for a second process to participate in validation.

### Kafka Integration Design

**Where Kafka slots in:**

The cleanest insertion point is between `FeedConnection` and `BackpressureQueue`. Today:

```
FeedConnection.onMessage() → BackpressureQueue.submit() → consumer thread → ValidatorEngine.onTick()
```

With Kafka:

```
FeedConnection.onMessage() → KafkaProducer.send() → Kafka topic
                                                         ↓
                                             KafkaConsumer (ValidatorEngine consumer group)
                                                         ↓
                                               ValidatorEngine.onTick()
```

`BackpressureQueue` becomes obsolete. `SessionRecorder` self-registers as a `FeedManager` listener — it would need to re-register as a Kafka consumer on a separate consumer group to preserve its current behaviour.

**Topic design:**

| Topic | Partitions | Key | Retention | Purpose |
|-------|-----------|-----|-----------|---------|
| `market.ticks.raw` | 12 | `feedId:symbol` | 24 hours | Raw ticks from all feeds |
| `market.ticks.validated` | 12 | `feedId:symbol` | 7 days | Ticks post-validation (with result attached) |
| `market.alerts` | 4 | `area` | 30 days | Generated alerts |
| `market.validation.results` | 4 | `feedId` | 7 days | Per-validator status snapshots |

Partitioning by `feedId:symbol` (the existing `getFeedScopedSymbol()` key) ensures per-symbol ordering within partitions — which preserves the ordering invariant that `OrderingValidator` relies on.

**Serialization:** Use Avro with Schema Registry (Confluent or Apicurio). JSON is the simpler choice but Avro gives you schema evolution for free — important when `Tick.java` fields change. The existing `Tick` model maps cleanly to an Avro schema. Alternative: JSON with Jackson if you want to move faster and schema evolution isn't a concern yet.

**Producer changes (FeedConnection.java):**

```java
// Replace BackpressureQueue injection with:
private final KafkaTemplate<String, Tick> kafkaTemplate;

// In onMessage():
kafkaTemplate.send("market.ticks.raw", tick.getFeedScopedSymbol(), tick);
// Async — non-blocking, matches current reactive WebSocket handler
```

**Consumer changes (ValidatorEngine.java):**

```java
@KafkaListener(topics = "market.ticks.raw", groupId = "validator-engine",
               concurrency = "4")  // 4 consumer threads = 4 partitions per node
public void onTick(Tick tick) {
    // Current ValidatorEngine.onTick() logic unchanged
}
```

The per-feed validator scoping (`ConcurrentHashMap<feedId, List<Validator>>`) and 250ms SSE throttle remain unchanged. The Kafka consumer thread replaces the BackpressureQueue consumer thread — the ValidatorEngine code itself is agnostic.

**Classes that become obsolete:** `BackpressureQueue.java`

**Classes that stay unchanged:** All 9 validators, `AlertGenerator`, `StreamController`, `SessionRecorder` (with a second consumer group), all stores, all controllers.

**Spring Kafka dependency:**

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

**Spring Kafka test support:**

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
<!-- Replaces current BackpressureQueueTest with embedded Kafka integration tests -->
```

### What You Gain

**Durability:** Kafka replicates `market.ticks.raw` across brokers. A JVM crash between ingestion and validation loses nothing — the consumer group offset is committed only after `ValidatorEngine.onTick()` completes.

**Horizontal scaling:** Three validator nodes each running a `KafkaConsumer` in the same consumer group automatically partition the load. Kafka assigns 4 partitions to each of 3 nodes. This is the prerequisite for any multi-node deployment.

**Replay capability:** Kafka's retention (24 hours on `market.ticks.raw`) gives you a second replay mechanism that doesn't require the existing `SessionRecorder`/`SessionReplayer` path. You can replay a feed failure window by seeking the consumer offset back. This complements but doesn't replace `SessionReplayer` (which persists indefinitely to SQLite).

**SessionRecorder integration with Kafka:** `SessionRecorder` currently self-registers via `@PostConstruct` as a `FeedManager` global tick listener. With Kafka, it becomes a second consumer group (`session-recorder`) on `market.ticks.raw`. The batch flush logic (100 ticks, 1-second timer) is unchanged — just the input source changes.

### Pulsar Alternative

Choose Pulsar over Kafka when:
- You need **multi-tenancy** out of the box (Pulsar has namespaces and tenants as first-class concepts — useful if this platform ever serves multiple internal teams)
- You need **geo-replication** across datacenters without Kafka MirrorMaker complexity
- You want **tiered storage** (Pulsar offloads old segments to S3/GCS automatically — relevant for the `market.ticks.raw` retention question)

The implementation pattern is identical (Pulsar has a Spring integration library). Kafka is the better choice for a portfolio project because its ecosystem tooling (Kafka UI, ksqlDB, Kafka Connect) is more familiar to interviewers.

---

## Section 3 — Authentication and Authorization

### Current State

All 38 endpoints (7 REST controllers + `StreamController`) are unauthenticated. The URL validation in `FeedController` blocks SSRF but anyone who can reach port 8082 can add feeds, replay sessions, acknowledge alerts, or reconfigure validators.

### Spring Security Integration

**Dependencies to add:**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

Use `jjwt` (not `spring-security-oauth2`) — this system doesn't need an OAuth flow. JJWT gives you JWT signing and parsing with a clean API.

**Token structure:**

```json
{
  "sub": "ganesh",
  "roles": ["ROLE_ADMIN"],
  "feedIds": ["*"],
  "iat": 1744000000,
  "exp": 1744086400
}
```

Include `feedIds` claim so a VIEWER role can be scoped to specific feed IDs — useful when multiple teams share one deployment.

**Endpoint classification:**

| Pattern | Auth Required | Notes |
|---------|--------------|-------|
| `POST /api/auth/login` | No | Returns JWT |
| `GET /api/stream/*` | Yes (see SSE section) | SSE endpoints |
| `GET /api/feeds` | VIEWER | Read-only |
| `POST /api/feeds`, `DELETE /api/feeds/*` | OPERATOR | Mutating |
| `POST /api/feeds/*/start`, `*/stop` | OPERATOR | Lifecycle |
| `GET /api/sessions`, `GET /api/sessions/*/export` | VIEWER | Read-only |
| `POST /api/sessions/start`, `*/stop`, `*/replay` | OPERATOR | Mutating |
| `GET /api/alerts` | VIEWER | Read-only |
| `POST /api/alerts/*/acknowledge` | OPERATOR | Mutating |
| `PUT /api/validation/config` | ADMIN | Config change |
| `POST /api/validation/reset` | ADMIN | Destructive |
| `GET /api/simulator/*` | VIEWER | Read-only |
| `POST /api/simulator/*` | OPERATOR | Mutating |
| `GET /api/compliance`, `GET /api/metrics` | VIEWER | Read-only |

**SecurityFilterChain skeleton:**

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())  // stateless JWT, CSRF not needed
        .sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/feeds", "/api/alerts",
                             "/api/sessions", "/api/compliance", 
                             "/api/metrics").hasAnyRole("VIEWER","OPERATOR","ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/feeds/**",
                             "/api/sessions/**", "/api/alerts/**",
                             "/api/simulator/**").hasAnyRole("OPERATOR","ADMIN")
            .requestMatchers("/api/validation/config", 
                             "/api/validation/reset").hasRole("ADMIN")
            .requestMatchers("/api/stream/**").authenticated()
            .anyRequest().denyAll()
        )
        .addFilterBefore(new JwtAuthFilter(jwtService), 
                         UsernamePasswordAuthenticationFilter.class)
        .build();
}
```

### SSE Authentication Problem

`EventSource` (the browser API used by `useSSE.js`) does not support custom headers. You cannot send `Authorization: Bearer <token>`. This is a known W3C limitation.

**Option A: Token in query param**

```javascript
// In useSSE.js:
const url = `${baseUrl}?token=${encodeURIComponent(jwtToken)}`;
new EventSource(url);
```

Server-side, `JwtAuthFilter` reads from `request.getParameter("token")` as fallback. This works but the JWT appears in server access logs and browser history. Mitigate by using short-lived tokens (15-minute expiry) for SSE connections only.

**Option B: Cookie-based auth for SSE**

Issue a `HttpOnly; Secure; SameSite=Strict` session cookie on login, alongside the JWT. SSE requests automatically include cookies. The SecurityFilterChain checks the cookie for `/api/stream/**` paths.

**Recommendation:** Option B for production. Option A is acceptable for a developer tool or internal system where log exposure is low risk. The `useSSE.js` hook would need no changes — the browser sends the cookie automatically.

### Role Design

| Role | Can Do | Cannot Do |
|------|--------|-----------|
| `VIEWER` | Read feeds, alerts, sessions, metrics, compliance; view SSE streams | Start/stop feeds, record sessions, modify config |
| `OPERATOR` | Everything VIEWER can do + add/remove feeds, start/stop, record/replay sessions, acknowledge alerts, run simulator | Modify validator config thresholds, reset validation state |
| `ADMIN` | Everything OPERATOR can do + modify validator thresholds, reset state, delete all alerts | — |

For a single-developer portfolio system, you can start with just ADMIN + VIEWER and add OPERATOR later.

### API Key Alternative

For programmatic access (CI scripts, monitoring agents) JWT is annoying — keys expire. Add a simple API key scheme alongside JWT:

```java
// ApiKeyFilter.java — runs before JwtAuthFilter
String key = request.getHeader("X-Api-Key");
if (key != null) {
    ApiKey apiKey = apiKeyRepository.findByKey(sha256(key));
    if (apiKey != null && !apiKey.isRevoked()) {
        // Set SecurityContext with apiKey.getRole()
        return;
    }
}
// Fall through to JWT filter
```

Store API keys hashed (SHA-256) in a new `api_keys` SQLite table. This is a 1-day addition that is very useful for CI/CD and monitoring integrations.

---

## Section 4 — Observability and Monitoring

### Current State

The project already includes `logstash-logback-encoder 7.4` (JSON structured logging for ELK). The `AtomicLong` counters in `BackpressureQueue` (`totalSubmitted`, `totalProcessed`, `droppedCount`) are tracked but not exposed via any metrics endpoint. There is no `/actuator` endpoint, no Prometheus scrape target, and no health checks beyond implicit "is the process running."

### Metrics: Micrometer + Spring Actuator

**Dependencies (1-day effort, zero risk):**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**application.properties additions:**

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
management.metrics.tags.application=${spring.application.name}
```

**Metrics to expose — mapped to existing code:**

| Existing Code | Micrometer Metric | Type | Notes |
|--------------|-------------------|------|-------|
| `BackpressureQueue.droppedCount` | `queue.ticks.dropped.total` | Counter | Already `AtomicLong` — wrap with `Metrics.counter()` |
| `BackpressureQueue.queueSize` | `queue.ticks.size` | Gauge | `Gauge.builder(...).register(registry)` |
| `BackpressureQueue.totalSubmitted` | `queue.ticks.submitted.total` | Counter | Already `AtomicLong` |
| `FeedManager.connections.size()` | `feeds.connected.count` | Gauge | Per-status breakdown |
| SSE emitter lists in `StreamController` | `sse.emitters.active` | Gauge | Per-endpoint |
| `LatencyValidator` p50/p95/p99 | `validation.latency.p50`, `.p95`, `.p99` | Gauge | Pull from `ValidationResult.details` |
| `ValidatorEngine.onTick()` processing | `validator.processing.time` | Timer | `Timer.record()` wrapping the fan-out |
| `TickStore.saveBatch()` | `store.batch.write.time` | Timer | Wraps `jdbcTemplate.batchUpdate()` |
| `AccuracyValidator.invalidPriceCount` | `validation.accuracy.invalid.total` | Counter | — |
| `AlertGenerator.alerts.generated` | `alerts.generated.total` | Counter | Per severity |

**Micrometer wiring example for BackpressureQueue:**

```java
@PostConstruct
public void registerMetrics(MeterRegistry registry) {
    Gauge.builder("queue.ticks.size", queue, ArrayBlockingQueue::size)
         .description("Current backpressure queue depth")
         .register(registry);
    // droppedCount already exists as AtomicLong:
    Metrics.more().counter("queue.ticks.dropped.total", Tags.empty(), 
                            droppedCount, AtomicLong::doubleValue);
}
```

**Grafana dashboard panels:**
1. Tick ingestion rate (rate of `queue.ticks.submitted.total`, 1m window)
2. Drop rate % (`queue.ticks.dropped.total` / `queue.ticks.submitted.total`)
3. Queue depth (time series of `queue.ticks.size`)
4. Validator processing time (p50/p95/p99 of `validator.processing.time`)
5. Active SSE emitters per endpoint (`sse.emitters.active`)
6. SQLite write latency (`store.batch.write.time`)
7. Connected feed count by status (`feeds.connected.count`)
8. Alert generation rate (`alerts.generated.total` rate)

**Grafana alert rules:**
- Drop rate > 5% for 2 minutes → page oncall
- Queue depth > 8,000 (80% of 10K capacity) for 30 seconds → warn
- Validator processing p99 > 100ms → warn
- No ticks submitted for 60 seconds → critical

### Distributed Tracing: OpenTelemetry

**Dependencies:**

```xml
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
    <version>2.12.0</version>
</dependency>
```

**Trace span boundaries for a single tick:**

```
[WebSocket receive: FeedConnection.onMessage()]
  └─ [BackpressureQueue.submit()]
       └─ [ValidatorEngine.onTick()]
            ├─ [AccuracyValidator.validate()]
            ├─ [LatencyValidator.validate()]
            ├─ [CompletenessValidator.validate()]
            ├─ ... (9 validators in parallel — child spans)
            └─ [StreamController.broadcast()]
                 └─ [SseEmitter.send()]
```

**Correlation ID:** `Tick.correlationId` already exists as a field. Use it as the OpenTelemetry trace ID by injecting it into the span context:

```java
// In FeedConnection.onMessage():
Span span = tracer.spanBuilder("tick.ingest")
    .setAttribute("feed.id", tick.getFeedId())
    .setAttribute("symbol", tick.getSymbol())
    .setAttribute("correlation.id", tick.getCorrelationId())
    .startSpan();
```

Export to Jaeger (local development) or OTLP endpoint (production).

### Structured Logging Improvements

The `logstash-logback-encoder` dependency already exists. Add MDC fields in the hot path:

```java
// In ValidatorEngine.onTick():
MDC.put("feedId", tick.getFeedId());
MDC.put("symbol", tick.getSymbol());
MDC.put("correlationId", tick.getCorrelationId());
try {
    // ... validation fan-out ...
} finally {
    MDC.clear();
}
```

**logback-spring.xml configuration:**

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>feedId</includeMdcKeyName>
            <includeMdcKeyName>symbol</includeMdcKeyName>
            <includeMdcKeyName>correlationId</includeMdcKeyName>
            <includeMdcKeyName>sessionId</includeMdcKeyName>
        </encoder>
    </appender>
</configuration>
```

With this, every log line from the validation pipeline includes structured `feedId`, `symbol`, and `correlationId` fields that Kibana/Loki can filter on. Debugging a specific feed's issues in production becomes a `feedId: "binance-1"` filter away.

### Custom Health Indicators

```java
@Component
public class BackpressureQueueHealthIndicator implements HealthIndicator {
    private final BackpressureQueue queue;

    @Override
    public Health health() {
        double fillPercent = (double) queue.getQueueSize() / 10_000 * 100;
        if (fillPercent > 90) {
            return Health.down()
                .withDetail("queueDepth", queue.getQueueSize())
                .withDetail("fillPercent", fillPercent)
                .withDetail("droppedTotal", queue.getDroppedCount())
                .build();
        }
        return Health.up()
            .withDetail("queueDepth", queue.getQueueSize())
            .withDetail("droppedTotal", queue.getDroppedCount())
            .build();
    }
}

@Component
public class FeedManagerHealthIndicator implements HealthIndicator {
    private final FeedManager feedManager;

    @Override
    public Health health() {
        long connected = feedManager.getConnections().values().stream()
            .filter(c -> c.getStatus() == Connection.Status.CONNECTED).count();
        long total = feedManager.getConnections().size();
        if (total > 0 && connected == 0) {
            return Health.down().withDetail("connected", 0).withDetail("total", total).build();
        }
        return Health.up().withDetail("connected", connected).withDetail("total", total).build();
    }
}
```

**Kubernetes probe configuration:**

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8082
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8082
  initialDelaySeconds: 10
  periodSeconds: 5
```

The `BackpressureQueueHealthIndicator` contributes to readiness — if the queue is 90% full, the pod should stop receiving new traffic until it drains.

---

## Section 5 — Database Evolution

### When SQLite Becomes the Bottleneck

`SessionRecorder` flushes batches of 100 ticks via `TickStore.saveBatch()` which calls `jdbcTemplate.batchUpdate()`. With WAL mode and a single Hikari connection (`maximum-pool-size=1`), SQLite can handle roughly 5,000–20,000 tick writes per second depending on hardware.

At the current architecture's throughput cap (10K queue + 1 consumer thread), SQLite is not the bottleneck. It becomes the bottleneck when:
- Tick rate exceeds ~15K/sec sustained (SQLite WAL write amplification under load)
- `findBySessionId()` is called on a session with >1M ticks (full table scan, no index on `session_id` + `exchange_ts` composite)
- Multiple simultaneous session replays (the `pool-size=1` serialises all reads behind writes)
- The `ticks` table exceeds ~50M rows (SQLite page cache pressure)

**Queries that slow down first:**
1. `SELECT * FROM ticks WHERE session_id = ? ORDER BY exchange_ts ASC` — `SessionReplayer.replaySync()`
2. `SELECT COUNT(*), SUM(byte_estimate) FROM ticks WHERE session_id = ?` — session stats
3. The batch insert in `saveBatch()` under sustained load with concurrent reads

### PostgreSQL Migration

**Schema compatibility:** The current SQLite schema maps cleanly to PostgreSQL with minor changes:

| SQLite | PostgreSQL | Change |
|--------|-----------|--------|
| `TEXT` (prices as `BigDecimal.toPlainString()`) | `NUMERIC(20,8)` | Better type safety, slightly different RowMapper |
| `INTEGER` (acknowledged as 0/1) | `BOOLEAN` | Direct Java boolean mapping |
| `TEXT` (timestamps as ISO-8601) | `TIMESTAMPTZ` | Native time queries, partitioning |
| No auto-increment syntax difference | `BIGSERIAL` | Minor DDL change |

**Flyway setup (1-day effort):**

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

```properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

Migration files:
- `V1__initial_schema.sql` — current 5-table schema (extract from `SqliteConfig.java`)
- `V2__add_indexes.sql` — composite index on `ticks(session_id, exchange_ts)`
- `V3__prices_as_numeric.sql` — if migrating from TEXT prices

**Flyway + SQLite for development:** Flyway supports SQLite. Use the same migration files for both environments — only the datasource URL changes. `application-test.properties` uses in-memory SQLite; `application-prod.properties` uses PostgreSQL.

**Connection pool sizing:** Replace `maximum-pool-size=1` with `maximum-pool-size=10`. PostgreSQL supports concurrent writers. `SessionRecorder` (writer) and `SessionReplayer` (reader) can now execute concurrently without the current implicit serialisation.

**TimescaleDB extension:**

```sql
-- After migrating to PostgreSQL:
CREATE EXTENSION IF NOT EXISTS timescaledb;
SELECT create_hypertable('ticks', 'exchange_ts', chunk_time_interval => INTERVAL '1 day');
```

TimescaleDB gives you:
- Automatic time-based partitioning (each day = one chunk)
- `time_bucket()` aggregate queries (5-second OHLC from raw ticks without application-level computation)
- Automatic compression of chunks older than 7 days
- Efficient range scans for `SessionReplayer` (reads one chunk at a time rather than full table scan)

The `TickStore.RowMapper` doesn't change. Only `SqliteConfig` (schema init) needs updating.

### Read/Write Separation

**Current split:**
- `SessionRecorder` is write-heavy (batch inserts every 1 second)
- `SessionReplayer` is read-heavy (sequential full scan of a session's ticks)
- `AlertStore`, `SessionStore`, `ConnectionStore` are mixed but low-volume

**Approach:** Add a read replica PostgreSQL connection pool. Spring supports multiple DataSource beans:

```java
@Bean
@Primary
DataSource primaryDataSource() { /* writer — max-pool-size=5 */ }

@Bean
@Qualifier("readReplica")
DataSource readReplicaDataSource() { /* reader — max-pool-size=10 */ }
```

Inject `@Qualifier("readReplica")` JdbcTemplate into `SessionReplayer`, `SessionExporter`, and the GET handlers in controllers.

### Data Retention

Current `ticks` table has no retention policy — it grows indefinitely. A session with 1M ticks at ~200 bytes/tick is ~200MB. 100 sessions = 20GB.

**Recommended retention:**
- Keep raw ticks for **30 days** for recent session replay
- Archive to S3/GCS (CSV export via `SessionExporter.exportAsCsv()` — already implemented) for long-term storage
- Delete raw ticks older than 30 days via a scheduled job

**Scheduled cleanup (add to a `@Scheduled` method in `SessionStore` or a dedicated `DataRetentionService`):**

```java
@Scheduled(cron = "0 0 2 * * *")  // 2am daily
public void purgeOldTicks() {
    long deleted = jdbcTemplate.update(
        "DELETE FROM ticks WHERE session_id IN " +
        "(SELECT id FROM sessions WHERE ended_at < ?)",
        Instant.now().minus(30, DAYS).toString()
    );
    log.info("Purged {} old ticks", deleted);
}
```

With TimescaleDB, use `add_retention_policy('ticks', INTERVAL '30 days')` instead.

---

## Section 6 — Horizontal Scaling

### What Breaks First When You Add a Second Node

In order of severity:

1. **SSE emitter lists are in-memory** (`CopyOnWriteArrayList<SseEmitter>` in `StreamController`, one list per endpoint). If node A receives a tick and broadcasts it, the SSE clients connected to node B receive nothing. **This is the first thing that breaks.** Every user would need sticky session routing, which limits scaling to "more nodes, same clients per node" rather than true load distribution.

2. **ValidatorEngine state is in-memory per node.** Each node's `AccuracyValidator` has its own `lastPrice` map, `LatencyValidator` has its own 10K circular buffer, `StatefulValidator` has its own OHLC per symbol. Two nodes validating the same feed produce different (and contradictory) validation results. This is a fundamental correctness problem, not just a performance one.

3. **SessionRecorder writes to local SQLite.** Two nodes create two separate `data/stream-validator.db` files. Sessions recorded on node A cannot be replayed on node B. This is the easiest to fix (move to shared PostgreSQL), but it depends on item 2 being solved first.

4. **FeedManager manages WebSocket connections locally.** A second node would either connect a duplicate WebSocket (double-counting ticks) or need coordination to know which node owns which feed. Without Kafka (Section 2), this is unsolvable cleanly.

**Hardest to solve:** Item 2 (validator state). Making validators stateless requires either pushing all per-symbol state to Redis or accepting that validators are per-node (and aggregating results across nodes at query time). Per-node is simpler but produces inconsistent results. Pushing to Redis is correct but adds 1–5ms latency per tick for state lookups.

### Redis for Shared SSE State

Replace `CopyOnWriteArrayList<SseEmitter>` in `StreamController` with Redis pub/sub:

**Today (StreamController.java, simplified):**

```java
// Single node — direct broadcast
private final CopyOnWriteArrayList<SseEmitter> tickEmitters = new CopyOnWriteArrayList<>();

public void broadcastTick(Tick tick) {
    tickEmitters.forEach(emitter -> emitter.send(tick));
}
```

**With Redis:**

```java
// Publisher (ValidatorEngine listener, any node)
redisTemplate.convertAndSend("channel:ticks", objectMapper.writeValueAsString(tick));

// Subscriber (StreamController, each node subscribes)
@Bean
MessageListenerAdapter tickListener() {
    return new MessageListenerAdapter(new TickMessageListener(tickEmitters));
}
```

Each node's `StreamController` subscribes to the Redis channel and broadcasts to its local SSE clients. All nodes receive all tick events regardless of which node's `FeedConnection` ingested the tick.

**Latency addition:** Redis pub/sub round-trip is typically 0.5–2ms on local network. The current SSE throttle is already 250ms, so this is imperceptible. If Kafka is already in place (Section 2), use Kafka's `market.ticks.validated` topic as the pub/sub mechanism instead of Redis — one fewer infrastructure component.

### Stateless Validator Design

The validators most dependent on per-symbol in-memory state:

| Validator | State Size | Stateless Complexity |
|-----------|-----------|---------------------|
| `LatencyValidator` | 10K long circular buffer | Medium — serialize buffer to Redis periodically |
| `StatefulValidator` | OHLC + VWAP per symbol, unbounded | Hard — every tick updates multiple fields |
| `AccuracyValidator` | Last price + sequence per symbol | Easy — two fields per symbol in Redis |
| `CompletenessValidator` | Gap count + stale set | Medium — stale set in Redis Set |
| `OrderingValidator` | Last timestamp per symbol | Easy — one field per symbol |
| `ThroughputValidator` | 60-second circular buffer | Medium — could be replaced by Micrometer rate |

**Recommended approach:** Make `AccuracyValidator` and `OrderingValidator` fully stateless via Redis first (simplest). Leave `LatencyValidator` and `StatefulValidator` as per-node (accept that each node computes its own percentiles from its own ticks). Aggregate results at the `ValidationController` layer.

The `StatefulValidator` (OHLC + VWAP) is the hardest — VWAP requires cumulative `Σ(price × volume)` and `Σ(volume)` which must be consistent across all ticks for a symbol. In a multi-node setup, this validator should run on the Kafka consumer node that "owns" a given symbol partition — partitioning by `feedId:symbol` (the existing key) handles this naturally if Kafka is already in place.

### Load Balancer Configuration

SSE requires sticky sessions because `SseEmitter` objects are heap-resident in the JVM that created them. A client whose request is routed to node B cannot receive events from node A's emitter list (without Redis pub/sub).

**nginx sticky session config:**

```nginx
upstream stream_validator {
    ip_hash;  # or use nginx-plus sticky cookie directive
    server node1:8082;
    server node2:8082;
    server node3:8082;
}

server {
    location /api/stream/ {
        proxy_pass http://stream_validator;
        proxy_set_header Connection '';
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;  # SSE connections are long-lived
    }
    
    location /api/ {
        proxy_pass http://stream_validator;
        # Regular REST — round-robin is fine
    }
}
```

Note: With Redis pub/sub for SSE (above), sticky sessions become optional — any node can serve any SSE client. This is the correct long-term target.

### Kubernetes Deployment

**Deployment manifest (after Kafka + Redis + PostgreSQL are in place):**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: stream-validator
spec:
  replicas: 3
  selector:
    matchLabels:
      app: stream-validator
  template:
    spec:
      containers:
        - name: stream-validator
          image: ghcr.io/your-org/stream-validator:latest
          ports:
            - containerPort: 8082
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: SPRING_DATASOURCE_URL
              valueFrom:
                secretKeyRef:
                  name: db-secret
                  key: url
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: "kafka-svc:9092"
            - name: REDIS_HOST
              value: "redis-svc"
          resources:
            requests:
              memory: "512Mi"
              cpu: "500m"
            limits:
              memory: "1Gi"
              cpu: "2000m"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8082
            initialDelaySeconds: 30
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8082
            initialDelaySeconds: 10
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: stream-validator-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: stream-validator
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Pods
      pods:
        metric:
          name: queue.ticks.size  # Custom metric from Prometheus
        target:
          type: AverageValue
          averageValue: "7000"  # Scale when avg queue depth > 7K
```

**Resource limit rationale based on current code:**
- `LatencyValidator`: 10K longs = ~80KB
- `ThroughputValidator`: 60 ints = negligible
- `StatefulValidator`: bounded by number of active symbols (typically <1K for crypto/equity feeds)
- `BackpressureQueue`: 10K Tick objects. `Tick` has ~15 fields; estimate ~500 bytes per Tick = ~5MB
- Spring Boot baseline: ~256MB heap
- **Total: 512Mi request, 1Gi limit** is conservative but correct for 1-2 active feeds. Scale up limits if running 10+ feeds.

**ConfigMap for application.properties:**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: stream-validator-config
data:
  application-prod.properties: |
    server.port=8082
    spring.datasource.hikari.maximum-pool-size=10
    validator.latency.warn-threshold-ms=500
    validator.accuracy.spike-pct-threshold=10
    management.endpoints.web.exposure.include=health,prometheus
```

---

## Section 7 — Advanced Validation Features

### New Validators Worth Adding

**VolumeProfileValidator**

*What it checks:* Detects abnormal volume patterns consistent with wash trading or spoofing. Specifically:
- Volume on a single tick is > 5× the rolling 100-tick VWAP volume (single-tick volume spike)
- Repeated identical price + volume tuples within a 1-second window (wash trade fingerprint)
- Rapid alternating buy/sell pressure at the same price level (spoofing attempt)

*Integration:* Implements `Validator` interface. Injected into `ValidatorEngine` alongside the existing 9 validators. The `ValidatorEngine.createFeedValidators()` method (the factory that creates per-feed instances) adds it to the list.

*Required state:* Rolling deque of last 100 (price, volume, timestamp) tuples per symbol — similar to `StatefulValidator` but tracking tuple frequency rather than OHLC.

*Estimated complexity:* **3–4 days.** The framework exists; you're writing the detection logic and tests.

**CrossFeedCorrelationValidator**

*What it checks:* When two feeds both provide the same symbol (e.g., BTC-USDT from Binance and from a custom feed), their mid-prices should be within a configurable threshold (default: 0.5%). Persistent divergence beyond 2% indicates a mis-configured feed or a stale price.

*Integration:* This validator is fundamentally different — it must observe ticks from *multiple* feeds simultaneously, so it cannot be a per-feed validator. It must be a global `FeedManager` listener that maintains the last price per `symbol` (not `feedScopedSymbol`) across all feeds.

*Required changes:* Add a global listener registration point in `ValidatorEngine` (currently it has `globalListeners` but validators are per-feed). Alternatively, register directly with `FeedManager` and call `validateResult()` on `ValidatorEngine` directly.

*Estimated complexity:* **2–3 days.** Simpler logic than VolumeProfile, but the cross-feed state management is architecturally non-trivial.

**MarketHoursValidator**

*What it checks:* Flags ticks arriving outside expected trading hours for the instrument's exchange. NYSE equities shouldn't send real trades at 3am EST; Binance crypto does trade 24/7 but specific instruments can have maintenance windows.

*Integration:* Per-feed validator (fits existing framework). Requires a `TradingCalendar` abstraction with exchange-specific schedules. For MVP, hardcode NYSE (09:30–16:00 ET Mon–Fri) and mark crypto as always-open.

*Estimated complexity:* **2 days.** Logic is simple; the complexity is the trading calendar data model.

**RateOfChangeValidator** (Price Acceleration Detector)

*What it checks:* `OrderingValidator` detects that timestamps go forward; `AccuracyValidator` detects single-tick price spikes (>10% from last price). `RateOfChangeValidator` detects *acceleration* — the rate of price change is itself accelerating abnormally. Useful for detecting momentum ignition patterns or flash crash precursors.

*Integration:* Per-feed validator. Maintains a deque of (price, timestamp) pairs; computes first derivative (velocity) and second derivative (acceleration) from consecutive ticks.

*Estimated complexity:* **2–3 days.** Numerical differentiation is straightforward; tuning the thresholds requires market data experimentation.

### Machine Learning Integration

**Good ML feature candidates from existing metrics:**

| Feature | Source | Notes |
|---------|--------|-------|
| Latency p99 (rolling) | `LatencyValidator` | Feed health signal |
| Drop rate | `BackpressureQueue.droppedCount` | Load signal |
| Bid-ask spread | `Tick.ask - Tick.bid` | Liquidity signal |
| Price velocity | Computed from `AccuracyValidator.lastPrice` | Momentum |
| Volume vs VWAP ratio | `StatefulValidator.vwap` | Volume anomaly |
| Sequence gap rate | `CompletenessValidator.gapEventCount` | Data quality |
| Out-of-order rate | `OrderingValidator.outOfOrderCount` | Network quality |

**Anomaly detection approach:** Isolation Forest (sklearn) or a simple autoencoder (PyTorch). Isolation Forest is the right first choice — no training labels needed (unsupervised), fast inference, interpretable (anomaly score). An autoencoder gives better performance on high-dimensional feature vectors but requires more data to train.

**Architecture for ML inference:**

Option A (Python microservice):
```
ValidatorEngine → REST POST /predict → Python Flask/FastAPI service (sklearn model) → anomaly score
```

Option B (ONNX in JVM):
```java
// Export sklearn model to ONNX:
// model.to_onnx() → model.onnx
// In Java:
OrtSession session = OrtEnvironment.getEnvironment().createSession("model.onnx");
OnnxTensor input = OnnxTensor.createTensor(env, features);
float score = (float) session.run(Map.of("input", input)).get(0).getValue();
```

Option B (ONNX) keeps inference in-process and avoids a network hop. The `com.microsoft.onnxruntime` dependency is 5MB. This is the correct approach for low-latency tick-level inference.

**Where in the pipeline:** After `ValidatorEngine.onTick()` completes all 9 validators, pass the feature vector to an `AnomalyDetector` component. Emit as a 10th validation area `ML_ANOMALY` with score as the metric. An ML anomaly with score > 0.8 triggers an alert via `AlertGenerator`.

**Online vs batch model updates:** Start with batch (train offline on recorded sessions via `SessionExporter`, export to ONNX, hot-reload the model file). Online learning (updating model weights per tick) is significantly more complex and rarely necessary for anomaly detection in market data.

### Compliance Enhancements

**Current MiFID II coverage** (from `ComplianceController`): 6 rules checked. Key gaps:

**ESMA clock synchronisation (MiFID II RTS 25):** Requires clock synchronisation to UTC within specified tolerances (1ms for systematic internalisers, 100μs for HFT). The existing `FeedConnection` clock offset calibration (20-tick average, stored as `clockOffsetMs`) is an approximation. For real MiFID II compliance, you need:
- GPS/PTP hardware clock or NTP with known accuracy bound
- Report the clock offset and its uncertainty in the compliance panel
- Log all timestamps with microsecond precision (current `exchangeTimestamp` is milliseconds from Binance — sufficient for most venues)

**Best execution monitoring (MiFID II RTS 27/28):** Track whether fills occur at best available price across venues. This requires:
- `CrossFeedCorrelationValidator` (above) as the price comparison engine
- Recording best bid/offer across all feeds for a given symbol at time of each tick
- This is SMARTS-style surveillance and is a significant feature — 2–4 weeks of work

---

## Section 8 — Performance Optimisation

### Hot Path Analysis

The critical path for a single tick:

```
WebSocket receive (FeedConnection.onMessage())
  → BackpressureQueue.submit()              [~1μs — synchronized on dropLock for DROP_OLDEST]
  → Consumer thread polls (100ms timeout)   [latency from queue to processing: 0–100ms]
  → ValidatorEngine.onTick()               [~5–50μs — 9 validator calls + listener notifications]
  → 9 × Validator.validate()              [~1–5μs each — mostly ConcurrentHashMap reads]
  → Throttled listener notify (250ms)      [amortised ~0μs — AtomicLong CAS]
  → SSE broadcast (every 250ms)           [~1μs per emitter × N emitters]
```

**Likely bottleneck at 100K ticks/sec:**

1. The `BackpressureQueue` consumer thread at 100ms poll timeout introduces up to 100ms of processing latency — this is by design (freshness over completeness) but means the consumer effectively processes in bursts, not continuously.

2. `LatencyValidator.calculatePercentile()` is O(n log n) on the 10K circular buffer. This runs on every SSE notify cycle (every 250ms, not per tick) — but it sorts 10,000 longs every 250ms, which is ~5ms of CPU time. Fine for current load; becomes an issue at very high tick rates if the 250ms throttle is reduced.

3. `ValidatorEngine` calls all 9 validators sequentially in `onTick()`. At 100K ticks/sec, this is 100K × 9 = 900K validator calls/sec. At 5μs each, that's 4.5 CPU cores worth of work from validation alone.

**Profiling tool:** Use async-profiler (agentpath JVM arg, flame graphs). JFR (Java Flight Recorder) is built into JDK 21 and sufficient for identifying which validator's `validate()` is consuming the most CPU without the overhead of async-profiler's SIGPROF approach. JFR is the right first tool.

```bash
jcmd <pid> JFR.start duration=60s filename=profile.jfr
jcmd <pid> JFR.stop
# Open profile.jfr in JDK Mission Control
```

### LMAX Disruptor

The Disruptor is a lock-free ring buffer designed for inter-thread communication at low latency. It replaces `ArrayBlockingQueue` in `BackpressureQueue`.

**What changes in BackpressureQueue.java:**

```java
// Current:
private final ArrayBlockingQueue<Tick> queue;
private final Object dropLock = new Object();

// With Disruptor:
private final Disruptor<TickEvent> disruptor;
private final RingBuffer<TickEvent> ringBuffer;

// TickEvent is a mutable wrapper class (Disruptor's object-pooling pattern):
public class TickEvent {
    public Tick tick;  // Mutable — Disruptor pre-allocates and reuses
}
```

**Expected throughput improvement:** The Disruptor eliminates lock contention on the queue. For a single producer (FeedConnection) + single consumer (ValidatorEngine) scenario: 25–50M operations/sec vs ArrayBlockingQueue's ~3–5M. In practice, the bottleneck shifts to `ValidatorEngine.onTick()` processing time, not the queue. **The Disruptor is worth it only if profiling shows the queue as the bottleneck.** At current scale (10K tick queue, 1 consumer), it is not.

**When this is worth the complexity:** When you have multiple FeedConnection producers (multiple feeds), the Disruptor's single-writer principle (each producer sequence is tracked independently) gives you near-zero contention. With 10+ simultaneous live feeds, the Disruptor pays off. With 1–3 feeds, it's premature optimisation.

### Off-Heap Memory for Tick Buffers

`LatencyValidator`'s circular buffer of 10K longs is currently on-heap:

```java
private final long[] latencySamples = new long[10_000];
```

At 8 bytes per long × 10K = 80KB — this is too small to matter for GC. The case for off-heap (Chronicle Map, ByteBuffer allocateDirect) becomes relevant when:

- Running many per-feed validator instances (10 feeds × 10K buffer = 800KB — still fine on-heap)
- The per-symbol state in `StatefulValidator` and `AccuracyValidator` grows to millions of symbols (unlikely for current feed types)

**Verdict:** Off-heap is not warranted for this codebase at current scale. If you add the `CrossFeedCorrelationValidator` tracking millions of symbols across feeds, revisit.

### Virtual Thread Optimisation

`FeedConnection` already uses virtual threads for reconnect delays:

```java
Thread.ofVirtual().start(() -> {
    Thread.sleep(backoffMs);
    reconnect();
});
```

**Where virtual threads would help further:**

1. **ValidatorEngine fan-out:** Currently sequential. With virtual threads, each validator call can run on its own virtual thread:

```java
// Instead of sequential:
validators.forEach(v -> v.validate(tick));

// With structured concurrency (JDK 21 preview, JDK 25 stable):
try (var scope = StructuredTaskScope.ShutdownOnFailure()) {
    validators.forEach(v -> scope.fork(() -> v.validate(tick)));
    scope.join();
}
```

**Caution:** The validators use `ReentrantLock` (LatencyValidator) and `synchronized` (StatefulValidator violation list). In a virtual-thread fan-out, these locks are correct but add overhead for low-contention cases. Profile before committing.

2. **SessionReplayer:** `replaySync()` blocks the calling thread for the duration of replay (potentially hours for a long session). Moving it to a virtual thread pool lets the REST handler return immediately and the caller polls via SSE.

3. **TickStore.saveBatch():** The `jdbcTemplate.batchUpdate()` call blocks on SQLite I/O. This is currently acceptable (1-second flush cycle, 100-tick batch). With PostgreSQL and 10 connections, virtual threads let you overlap multiple concurrent batch writes without a large thread pool.

---

## Section 9 — Developer Experience

### Docker Compose Improvements

Add Prometheus + Grafana to `docker-compose.yml` (after adding Micrometer):

```yaml
services:
  stream-validator:
    # ... existing config ...
    
  prometheus:
    image: prom/prometheus:v2.50.0
    volumes:
      - ./config/prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:10.3.0
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - ./config/grafana/dashboards:/var/lib/grafana/dashboards
      - ./config/grafana/provisioning:/etc/grafana/provisioning
    ports:
      - "3000:3000"
    depends_on:
      - prometheus
```

```yaml
# config/prometheus.yml
scrape_configs:
  - job_name: 'stream-validator'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['stream-validator:8082']
    scrape_interval: 5s
```

This means `docker compose up` gives you the full observable system including dashboards — a strong portfolio demo.

### Testcontainers for Integration Tests

Current integration tests use in-memory SQLite (`jdbc:sqlite:file::memory:?cache=shared`). This works well for SQLite. If you migrate to PostgreSQL, add Testcontainers:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@SpringBootTest
@Testcontainers
class TickStoreIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("test")
        .withInitScript("schema.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
    }
}
```

For Kafka integration tests:

```java
@Container
static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
```

This gives you integration tests that run against real PostgreSQL and Kafka instances — the gap that in-memory SQLite cannot cover.

### Springdoc OpenAPI

**Dependency (0.5 days):**

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.5.0</version>
</dependency>
```

```properties
springdoc.api-docs.path=/api-docs
springdoc.swagger-ui.path=/swagger-ui.html
springdoc.swagger-ui.operationsSorter=method
```

**Add to key controllers** (example for `FeedController`):

```java
@Tag(name = "Feed Management", description = "CRUD and lifecycle for market data feeds")
@RestController
public class FeedController {

    @Operation(summary = "Add a new feed connection",
               description = "Creates a new WebSocket feed. URL must use ws:// or wss:// scheme.")
    @ApiResponse(responseCode = "200", description = "Feed created")
    @ApiResponse(responseCode = "400", description = "Invalid URL or SSRF-blocked address")
    @PostMapping("/api/feeds")
    public ResponseEntity<Connection> addFeed(@RequestBody FeedRequest request) { ... }
}
```

At `http://localhost:8082/swagger-ui.html` you get a full interactive API explorer — significantly better than the current manual curl examples in the QA guide.

**API versioning strategy:** Prefix all new endpoints with `/api/v2/` when breaking changes are needed. The existing 38 endpoints stay as `/api/` (unversioned = v1 by convention). Don't add versioning to existing endpoints retroactively — it's churn for no gain on a project without external API consumers. When you add Kafka and make breaking changes to the streaming endpoint behaviour, that's when `/api/v2/stream/` makes sense.

### ArchUnit Architecture Tests

ArchUnit enforces architectural rules as unit tests:

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.3.0</version>
    <scope>test</scope>
</dependency>
```

```java
@AnalyzeClasses(packages = "com.marketdata.validator")
class ArchitectureTest {

    @ArchTest
    static final ArchRule validators_must_not_depend_on_controllers =
        noClasses().that().resideInAPackage("..validator..")
            .should().dependOnClassesThat()
            .resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule stores_must_not_depend_on_validators =
        noClasses().that().resideInAPackage("..store..")
            .should().dependOnClassesThat()
            .resideInAPackage("..validator..");

    @ArchTest
    static final ArchRule feed_adapters_must_implement_interface =
        classes().that().resideInAPackage("..feed..")
            .and().haveNameMatching(".*Adapter")
            .and().areNotInterfaces()
            .should().implement(FeedAdapter.class);

    @ArchTest
    static final ArchRule no_direct_sysout =
        noClasses().should().callMethod(System.class, "out")
            .because("Use SLF4J logger instead");
}
```

This prevents architectural drift — ensuring `StatefulValidator` never accidentally imports a `@RestController`, and that future developers adding new adapters remember to implement `FeedAdapter`.

**PIT Mutation Testing:**

```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <version>1.16.1</version>
    <configuration>
        <targetClasses>
            <param>com.marketdata.validator.validator.*</param>
        </targetClasses>
        <mutationThreshold>80</mutationThreshold>
    </configuration>
</plugin>
```

Run: `./mvnw org.pitest:pitest-maven:mutationCoverage`

PIT mutates your code (flips comparisons, removes conditions) and checks whether your tests catch the mutations. With 603 tests, some are likely testing happy paths without asserting on boundary conditions. PIT will tell you which validator rules have weak test coverage — especially the threshold comparisons in `AccuracyValidator` (`PASS ≥ 99.99%`) and `LatencyValidator` (spike count thresholds).

---

## Section 10 — Prioritised Implementation Roadmap

### Phase 1 — Foundation (7–10 working days)

These items require no architectural changes and directly address the most visible production-readiness gaps.

| Item | Effort | Dependency | Deliverable |
|------|--------|-----------|-------------|
| 1. GitHub Actions CI/CD (build + test) | 2 days | None | Every push runs 603 tests automatically |
| 2. Spring Boot Actuator + Micrometer | 1 day | None | `/actuator/health`, `/actuator/prometheus` live |
| 3. OWASP dependency-check in CI | 0.5 days | CI workflow | Weekly CVE scan on all Maven deps |
| 4. Springdoc OpenAPI | 0.5 days | None | Swagger UI at `/swagger-ui.html` |
| 5. JaCoCo coverage gate (75%) | 0.5 days | CI workflow | Coverage enforced on every PR |
| 6. Structured logging MDC fields | 0.5 days | None | `feedId` and `correlationId` in every log line |
| 7. Spring Security + JWT (basic) | 4 days | None | All 38 endpoints require auth; Swagger shows auth |
| 8. Flyway DB migrations | 1 day | None | Schema version controlled; safe to run on prod |

**After Phase 1:** The project has CI/CD, authentication, observable metrics, and version-controlled schema. This is the minimum bar to call it "production-ready" for a single-node deployment.

**Highest portfolio/interview value in Phase 1:** Items 2 (Micrometer) and 7 (Spring Security). These are the first questions an interviewer asks about a backend system.

### Phase 2 — Scalability (3–4 weeks)

These items require architectural changes but are necessary for real production use with multiple feeds or users.

| Item | Effort | Dependency | Deliverable |
|------|--------|-----------|-------------|
| 9. PostgreSQL migration + Flyway V2 | 4 days | Flyway (Phase 1) | Concurrent reads/writes; 10x connection pool |
| 10. TimescaleDB extension | 1 day | PostgreSQL | Time-series hypertable; automatic tick partitioning |
| 11. Micrometer → Prometheus + Grafana | 2 days | Actuator (Phase 1) | Full dashboard; 8 panels; alert rules |
| 12. OpenTelemetry distributed tracing | 2 days | Structured logging (Phase 1) | Tick-level traces from ingest to SSE |
| 13. Testcontainers (PostgreSQL) | 2 days | PostgreSQL | Integration tests run against real DB |
| 14. ArchUnit architecture tests | 1 day | None | Architectural drift prevented |
| 15. Kafka integration | 8–10 days | None | Durable tick pipeline; horizontal scaling ready |
| 16. Redis for shared SSE state | 4 days | Kafka | Multi-node SSE broadcast |
| 17. Kubernetes deployment manifests | 3 days | Docker, Kafka, Redis | Full K8s deployment with HPA |

**Dependencies within Phase 2:**
- PostgreSQL must come before Testcontainers (item 13)
- TimescaleDB requires PostgreSQL (item 10 after item 9)
- Redis (item 16) is most valuable after Kafka (item 15)
- K8s (item 17) requires all infrastructure (Kafka + Redis + PostgreSQL)

**After Phase 2:** The system can run on 3+ nodes, survives JVM crashes without data loss, has full observability, and is deployable to any Kubernetes cluster. This is a genuine production system.

**Highest portfolio/interview value in Phase 2:** Kafka integration (item 15). Being able to explain "I replaced the in-process ArrayBlockingQueue with a Kafka consumer group, preserved per-symbol ordering via partition key, and kept all 9 validators unchanged" is an excellent senior-level interview story.

### Phase 3 — Advanced Features (6–12 weeks)

These items are impressive but require significant investment. Prioritise based on whether this is heading toward a real product or remaining a portfolio piece.

| Item | Effort | Value if Portfolio | Value if Product |
|------|--------|-------------------|-----------------|
| LMAX Disruptor | 4 days | Very High | Medium |
| VolumeProfileValidator | 4 days | High | Very High |
| CrossFeedCorrelationValidator | 3 days | High | Very High |
| ML anomaly detection (ONNX) | 3–6 weeks | Very High | High |
| MarketHoursValidator | 2 days | Medium | High |
| PIT mutation testing | 1 day | Medium | Medium |
| Off-heap Chronicle Map | 1 week | Medium | Low |
| Data retention + partitioning | 2 days | Low | High |
| API versioning | 2 days | Low | Medium |

**After Phase 3:** The system has publishable-quality architecture. The ML anomaly detection and cross-feed correlation are features that commercial market data platforms charge for.

---

## Section 11 — What NOT to Do

### Over-engineering anti-patterns for this codebase

**Do not add gRPC as a transport layer.** The current SSE-based streaming works correctly. gRPC would require client-side code generation, a proto schema for `Tick` and `ValidationResult`, and a completely different frontend approach. The complexity cost is 2–3 weeks; the benefit is marginally better binary serialisation that doesn't matter until you're at 1M ticks/sec.

**Do not replace SQLite with a time-series database (InfluxDB, VictoriaMetrics) immediately.** SQLite with WAL mode handles the current write volume without stress. These databases excel at pure write-heavy metric ingestion, but `SessionReplayer` needs relational queries (fetch all ticks for a session, ordered, with filtering). TimescaleDB (PostgreSQL extension) gives you time-series performance without losing relational capabilities. PostgreSQL + TimescaleDB is the right move; pure time-series DBs are not.

**Do not implement event sourcing.** The existing `SessionRecorder` + `SessionReplayer` pattern *is* a form of event sourcing — ticks are immutable events stored in order, replayed to reconstruct state. Adding a full event sourcing framework (Axon, EventStore) would require rewriting the domain model and adds significant conceptual overhead for no additional capability.

**Do not add a service mesh (Istio, Linkerd) before you have more than one service.** This is a single-binary application. Service mesh overhead (mTLS, sidecar proxies, control plane) is warranted when you have 5+ microservices. If you break this into microservices (separate validation service, session service, feed management service), revisit.

**Do not add a frontend state management library (Redux, Zustand, Jotai) proactively.** The current component-local state + SSE approach is correct for this UI's complexity. React Query would improve the polling (eliminates the manual `setInterval` + `clearInterval` patterns in `ConnectionManager` and `AlertPanel`) but is a library addition, not Redux. If the UI grows to 20+ components sharing state, reconsider.

**Do not implement blue/green deployment before you have CI/CD.** It requires a load balancer, two production environments, and a health-based cutover mechanism. None of those exist yet. Get CI/CD working first (Phase 1); blue/green is a Phase 2+ concern.

**Do not add GraphQL as an API alternative.** The existing REST API is well-designed (resources are clear: `/api/feeds`, `/api/sessions`, `/api/alerts`). GraphQL would require a schema definition, resolvers for every entity, and corresponding frontend changes. The benefit (flexible queries, reduced over-fetching) is real for consumer-facing APIs with heterogeneous clients; it is not real for a developer tool with 5 UI components as the only consumers.

### Common mistakes when "productionising" a portfolio project

**Setting CI coverage thresholds too high on day 1.** If the baseline is 70% and you set the gate at 90%, every PR is blocked while you write tests for code that works fine. Measure actual coverage first (`./mvnw verify -Pci`, read the JaCoCo report), then set the gate at `baseline - 5%` initially. Raise it 5% per quarter.

**Adding Kubernetes before adding auth.** Running an unauthenticated API in Kubernetes with an exposed LoadBalancer is worse than running it locally — it's publicly addressable. Auth comes before scaling infrastructure.

**Migrating to PostgreSQL for a single-node deployment.** If you have one node and modest tick volume, SQLite is genuinely fine. The reason to migrate is multi-node scaling (shared database) or write throughput that exceeds SQLite's WAL mode ceiling — not because "real systems use PostgreSQL." Migrate when you have a reason, not on principle.

**Adding distributed tracing before you have any incidents to diagnose.** OpenTelemetry is very useful when you have a production issue you can't diagnose from logs. If the system is not yet in production, invest in structured logging (MDC fields — 0.5 days) first, and add tracing when you have a real trace to follow.

**Writing ML anomaly detection before the system has enough production data.** Isolation Forest needs representative normal data to learn from. You need thousands of labelled "normal" tick sequences before anomaly detection is meaningful. Record sessions via `SessionRecorder` for 30 days in production before training. The ML code is straightforward; the data is the hard part.

---

*This roadmap was generated from full codebase analysis including all Java source files, React components, test files, pom.xml, application.properties, docker-compose.yml, and documentation. Every class reference, code pattern, and architectural suggestion is specific to the current implementation.*
