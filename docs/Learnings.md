# Market Data Stream Validator — Engineering Learnings

> This file captures key learnings from each step of the build.
> Technical decisions, debugging stories, and key engineering insights from building this project.

---

## Step 1: pom.xml — Project Setup & Dependency Wiring

### Why Spring Boot Parent POM?

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.0</version>
</parent>
```

- Inherits managed dependency versions — you don't specify versions for Spring libraries
- Pre-configures compiler, resource filtering, and plugin defaults
- Upgrade Spring Boot = change ONE version number here, not 20 places

### The 6 Dependencies and Why Each Exists

| Dependency | Purpose | Explanation |
|---|---|---|
| `spring-boot-starter-web` | REST controllers, SSE (`SseEmitter`), Jackson JSON | "The HTTP backbone — handles all REST endpoints and server-sent events for the live dashboard" |
| `spring-boot-starter-webflux` | `ReactorNettyWebSocketClient` for outbound WebSocket | "We're NOT building a reactive app. We only import WebFlux for its WebSocket *client* — it connects outbound to Binance/Finnhub. The REST layer stays on Tomcat (starter-web)." |
| `spring-boot-starter-validation` | `@NotNull`, `@Valid` on request DTOs | "Validates input at the system boundary. Bad request → 400 error, not a NullPointerException deep in the stack." |
| `spring-boot-starter-jdbc` | `JdbcTemplate` for SQLite | "We chose JDBC over JPA deliberately. For a simple schema with 5 tables, JPA's ORM overhead isn't worth it. JdbcTemplate gives full SQL control and is faster for bulk inserts (batching 100 ticks at a time)." |
| `sqlite-jdbc` | JDBC driver for SQLite | "The bridge between Java and the SQLite file. Without it, `DriverManager` can't create connections to `jdbc:sqlite:` URLs." |
| `spring-boot-starter-test` | JUnit 5 + Mockito + AssertJ + MockMvc | "Scope=test means it's excluded from the production JAR. One dependency gives you the full testing toolkit." |

### Why starter-web AND starter-webflux Together?

When both are on the classpath, Spring Boot defaults to **Tomcat (servlet)** not Netty (reactive). We get:
- Tomcat for REST + SSE (from starter-web)
- `ReactorNettyWebSocketClient` for outbound WebSocket connections (from starter-webflux)

If only starter-webflux was present, the app would start on Netty in reactive mode — which we don't want.

### SQLite Configuration

```properties
spring.datasource.url=jdbc:sqlite:data/stream-validator.db
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.datasource.hikari.maximum-pool-size=1
```

**Why `maximum-pool-size=1`?**
- SQLite uses **file-level locking** — only one writer at a time
- Multiple HikariCP connections → `SQLITE_BUSY` errors under concurrent writes
- One connection is the correct setting for SQLite
- For production scale with concurrent access, you'd switch to PostgreSQL

### The `contextLoads()` Test — What It Actually Does

```java
@SpringBootTest
class StreamValidatorApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

This isn't a "do nothing" test. It:
1. Starts the **entire Spring application context**
2. Wires all beans, resolves all dependencies
3. Connects to the datasource
4. Fails if ANY bean creation fails (circular deps, missing configs, bad properties)

It's the single most important smoke test — "can the app even start?"

### Debugging: DataSource Auto-Configuration Gotcha

**Problem:** Adding `spring-boot-starter-jdbc` triggers auto-configuration of a DataSource. Without `spring.datasource.url`, Spring can't guess the database location → `Failed to determine a suitable driver class`.

**Lesson:** Spring Boot starters activate auto-configuration. If you add a starter, you must provide the matching configuration properties. This is a common "it compiled but won't start" issue.

### Maven Wrapper

The `mvnw` / `mvnw.cmd` files let anyone build the project without pre-installing Maven. The `.mvn/wrapper/maven-wrapper.properties` file specifies which Maven version to download.

**Gotcha we hit:** The wrapper generator produced a bad download URL (`apache-maven-3-bin.zip` instead of `apache-maven-3.9.6-bin.zip`). Always verify wrapper URLs resolve.

---

## Step 2: Tick.java — Core Market Data Model

### Why BigDecimal for Financial Data?

```java
// WRONG — double loses precision
double price = 45123.456789012345;
// Stored as: 45123.45678901234 (truncated!)

// CORRECT — BigDecimal preserves every digit
BigDecimal price = new BigDecimal("45123.456789012345");
// Stored as: 45123.456789012345 (exact)
```

**Key insight:** "In financial systems, we use `BigDecimal` for all monetary values because IEEE 754 floating-point (`double`/`float`) cannot represent most decimal fractions exactly. For example, `0.1 + 0.2 != 0.3` in floating-point. In a system processing millions of ticks, these rounding errors accumulate and cause real financial discrepancies. The blueprint stores prices as TEXT in SQLite for the same reason — SQLite's REAL type is a double underneath."

**Key rule:** Always construct BigDecimal from `String`, never from `double`:
```java
new BigDecimal("45123.45")   // ✅ Exact
new BigDecimal(45123.45)     // ❌ Inherits double's imprecision
```

### Tick as the Core Data Unit

Tick flows through the entire pipeline:
```
Exchange → FeedConnection → FeedAdapter.parseTick() → Tick object
  → ValidatorEngine (8 validators each call onTick())
  → SessionRecorder (batches to SQLite)
  → SSE StreamController (pushes to browser)
```

Every component in the system depends on Tick. Getting this model right is critical.

### Fields and Their Purpose

| Field | Type | Why |
|---|---|---|
| `id` | `long` | DB auto-increment. Not set by feed — set by SQLite on insert. |
| `symbol` | `String` | Ticker symbol ("BTCUSDT", "AAPL"). Used as key for per-symbol tracking in validators. |
| `price` | `BigDecimal` | Last traded price. BigDecimal, never double. |
| `bid` / `ask` | `BigDecimal` (nullable) | Best bid/ask. Nullable because trade messages don't always include these. |
| `volume` | `BigDecimal` | Trade volume. BigDecimal because crypto trades have fractional quantities (0.00123 BTC). |
| `sequenceNum` | `long` | From the exchange. Used for: gap detection (CompletenessValidator), ordering (OrderingValidator), idempotent processing (validators skip already-seen sequence numbers). |
| `exchangeTimestamp` | `Instant` | When the exchange generated the event. Used for latency calculation. |
| `receivedTimestamp` | `Instant` | When our system received it. Set automatically in constructor. `latency = received - exchange`. |
| `feedId` | `String` | Which connection this tick came from. Needed when running multiple feeds simultaneously. |
| `sessionId` | `Long` (nullable) | Only set when recording a session. `null` = not being recorded. Wrapper type `Long` (not `long`) to allow null. |

### Why `Long` (wrapper) for sessionId but `long` (primitive) for id?

- `id` is always set (by DB auto-increment) — primitive `long` defaults to 0, which is fine
- `sessionId` might be null (tick not being recorded) — must use `Long` wrapper to represent absence



### Latency Calculation Pattern

```java
public long getLatencyMs() {
    return Duration.between(exchangeTimestamp, receivedTimestamp).toMillis();
}
```

This is **derived data** — computed from two stored fields, not stored itself. Benefits:
- No stale data (always recalculated from source timestamps)
- No extra column in DB
- Used by LatencyValidator to track p50/p95/p99

### Test Strategy: 9 Tests Covering

1. **Auto-set receivedTimestamp** — constructor sets it to `Instant.now()`
2. **Constructor field mapping** — all 6 params land in correct fields
3. **BigDecimal precision** — proves `double` would truncate but `BigDecimal` doesn't
4. **Latency calculation** — 42ms delta computed correctly
5. **Null safety** — null timestamps return `Duration.ZERO`, not NPE
6. **Nullable bid/ask** — null by default, settable
7. **Nullable sessionId** — null by default, settable
8. **Default constructor** — all fields unset (for JdbcTemplate row mapping)
9. **Full setter/getter round-trip** — every field readable after being set

---

## Step 3: Connection.java — WebSocket Connection Config

### Enums as Inner Classes

```java
public class Connection {
    public enum AdapterType { BINANCE, FINNHUB, GENERIC }
    public enum Status { CONNECTED, DISCONNECTED, RECONNECTING, ERROR }
}
```

**Why inner enums?** These enums only make sense in the context of a Connection. They don't need their own files:
- `AdapterType` — determines which parser (BinanceAdapter, FinnhubAdapter, GenericAdapter) to use
- `Status` — tracks the connection lifecycle: DISCONNECTED → CONNECTED → RECONNECTING → CONNECTED (or → ERROR)

**Key insight:** "I used inner enums because `AdapterType` and `Status` are tightly coupled to Connection. They have no independent meaning. If I were modeling shared concepts used across packages, I'd extract them to top-level enums."

### Defensive Copying of Collections

```java
// In constructor:
this.symbols = new ArrayList<>(symbols);

// In setter:
public void setSymbols(List<String> symbols) {
    this.symbols = new ArrayList<>(symbols);
}
```

**Why?** Without defensive copying, external code can mutate the internal list:
```java
List<String> myList = new ArrayList<>(List.of("BTCUSDT"));
Connection conn = new Connection("test", "wss://...", BINANCE, myList);
myList.add("HACKED");  // Without defensive copy, conn.getSymbols() now contains "HACKED"!
```

**Key insight:** "I defensively copy mutable collections in constructors and setters to prevent external mutation of internal state. This is an Effective Java best practice (Item 50: Make defensive copies). For an immutable version, I'd use `List.copyOf()`, but since symbols may change via subscribe/unsubscribe, an ArrayList copy is appropriate."

### UUID as Connection ID

```java
this.id = UUID.randomUUID().toString();
```

**Why UUID instead of auto-increment?**
- Connections are created in-memory before persistence — need an ID immediately
- Multiple servers could create connections simultaneously (Kafka-ready design)
- UUIDs are collision-free without a central coordinator
- DB stores it as TEXT (matches schema.sql: `id TEXT PRIMARY KEY`)

### `recordTick()` — Encapsulated State Update

```java
public void recordTick() {
    this.lastTickAt = Instant.now();
    this.tickCount++;
}
```

Instead of exposing `setTickCount()` + `setLastTickAt()` and hoping callers update both, one method guarantees consistency. This is **encapsulation** — the class owns its state transition rules.

### Test Strategy: 10 Tests Covering

1. **UUID generation** — each Connection gets a unique ID
2. **Default status** — starts DISCONNECTED (not connected to anything yet)
3. **Empty symbols** — new connection subscribes to nothing by default
4. **Parameterized constructor** — all fields set correctly
5. **Defensive copy in constructor** — mutating original list doesn't affect Connection
6. **Defensive copy in setter** — mutating list after `setSymbols()` doesn't affect Connection
7. **recordTick()** — updates both timestamp and count atomically
8. **Status enum values** — all 4 from blueprint exist
9. **AdapterType enum values** — all 3 from blueprint exist
10. **Full setter/getter round-trip** — every field set and read back

---

## Step 4: ValidationResult.java — The 8-Area Validation Model

### Why `double` for metric/threshold but `BigDecimal` for prices?

The blueprint specifies `metric: double` and `threshold: double`. This is intentional:
- **Prices** (Tick.java) = financial data → must be exact → `BigDecimal`
- **Metrics** (ValidationResult) = percentages, latency ms, rates → approximate is fine → `double`

Example: "accuracy rate is 99.98%" — whether it's 99.98 or 99.980000000001 doesn't matter for a pass/fail decision. But "price is $45,123.45" — that extra 0.000001 matters in finance.

**Key insight:** "I use `BigDecimal` at the data boundary where financial precision matters — tick prices and volumes. For internal metrics like pass rates and latency percentiles, `double` is appropriate because these are statistical measures, not financial values."

### Static Factory Methods Pattern

```java
ValidationResult.pass(Area.ORDERING, "100% in order", 100.0, 99.99);
ValidationResult.warn(Area.COMPLETENESS, "2 gaps detected", 2.0, 0.0);
ValidationResult.fail(Area.RECONNECTION, "Failed reconnect", 0.0, 1.0);
```

**Why factories instead of just constructors?**
- More readable: `ValidationResult.pass(...)` is self-documenting
- Less error-prone: you can't accidentally create a "PASS" with `Status.FAIL`
- Consistent: every result gets a timestamp and empty details map

This is from Effective Java Item 1: "Consider static factory methods instead of constructors."

### The 8 Areas — What Each Validator Tests

| Area | Validator | What It Catches |
|---|---|---|
| `ACCURACY` | AccuracyValidator | Bad prices, bid > ask, >10% jumps |
| `LATENCY` | LatencyValidator | Slow delivery, p95 > threshold, spikes |
| `COMPLETENESS` | CompletenessValidator | Sequence gaps, stale feeds |
| `RECONNECTION` | ReconnectionValidator | Failed reconnects, slow recovery |
| `THROUGHPUT` | ThroughputValidator | Message rate drops, zero throughput |
| `ORDERING` | OrderingValidator | Out-of-order timestamps |
| `SUBSCRIPTION` | SubscriptionValidator | Leaky unsubscribes, dead subscriptions |
| `STATEFUL` | StatefulValidator | VWAP/OHLC drift, volume going backwards, stale data |

**Key insight:** "The 8 areas cover the full spectrum of streaming data quality. Areas 1-3 (accuracy, latency, completeness) are tick-level validation. Areas 4-5 (reconnection, throughput) are connection-level. Area 6 (ordering) validates sequencing. Area 7 (subscription) validates protocol. Area 8 (stateful) validates the reconstructed state — the most complex because it catches bugs that individual tick checks miss."

### `Map<String, Object> details` — Flexible Extra Data

The `details` map lets each validator attach context-specific data without changing the model:
```java
result.getDetails().put("gapCount", 3);           // CompletenessValidator
result.getDetails().put("spikeTimestamps", [...]);  // LatencyValidator
result.getDetails().put("staleSymbols", [...]);     // StatefulValidator
```

The UI reads these to show expanded card details. Using `Map<String, Object>` avoids creating 8 different result subclasses — keeps the model simple.

### Test Strategy: 11 Tests Covering

1. **8 area enum values** — matches blueprint exactly (critical!)
2. **3 status values** — PASS, WARN, FAIL
3. **Auto-timestamp** — set on construction
4. **Constructor field mapping** — all 5 params land correctly
5. **Default details map** — initialized empty (not null)
6. **Flexible details** — accepts arbitrary types (int, String, List)
7. **pass() factory** — creates PASS result with all fields
8. **warn() factory** — creates WARN result
9. **fail() factory** — creates FAIL result
10. **Full setter/getter round-trip**
11. **STATEFUL area exists** — explicit test for the 8th validator's area

---

## Step 5: LatencyStats.java — Percentile Snapshot Model

### What Are Percentiles and Why p50/p95/p99?

Percentiles answer: "What's the latency for X% of requests?"

```
Given 100 latency measurements sorted ascending:
  p50 = value at position 50  → "half your ticks arrive faster than this"
  p95 = value at position 95  → "95% arrive faster; only 5% are worse"
  p99 = value at position 99  → "only 1% of ticks are slower than this"
```

**Why not just use average?**
Average hides outliers. If 99 ticks arrive in 10ms but 1 takes 10,000ms:
- Average = 109ms (looks fine)  
- p99 = 10,000ms (reveals the problem!)

**Industry standard:** p95 and p99 are what SLAs/SLOs are written against. AWS, Google, and exchange SLAs use p99 latency.

**Key insight:** "I track p50, p95, and p99 because averages hide tail latency. In a market data system, a p99 spike means 1% of ticks arrive late — which could be thousands of trades per second. The LatencyValidator flags WARN when p95 exceeds the threshold and FAIL at 2x threshold."

### Why `long` (milliseconds), not `Duration`?

- `long` is the smallest representation — 8 bytes per value
- The latency buffer holds 10,000 entries → 80 KB with `long` vs ~240 KB with `Duration` objects
- Blueprint targets < 5 MB total memory budget
- JSON serialization is trivial: `{"p95": 42}` vs `{"p95": "PT0.042S"}`

### Window Concept

`windowStart` / `windowEnd` define the time range this snapshot covers. The LatencyChart on the frontend plots a new `LatencyStats` every second, creating a scrolling timeline:

```
10:00:00-10:00:01  → p50=10, p95=35, p99=80
10:00:01-10:00:02  → p50=12, p95=42, p99=95   ← spike developing
10:00:02-10:00:03  → p50=11, p95=38, p99=85   ← recovering
```

### Test Strategy: 5 Tests

1. **Constructor sets all 8 fields** — parameterized constructor round-trip
2. **Default constructor** — all longs default to 0, Instants to null
3. **Percentile ordering invariant** — p50 <= p95 <= p99 <= max, min <= p50
4. **All-same edge case** — when every latency is identical, all percentiles equal
5. **Full setter/getter round-trip**

---

## Step 6: FeedAdapter.java + BinanceAdapter.java — Strategy Pattern for Exchange Parsing

### The Strategy Pattern

```
FeedConnection has-a FeedAdapter
  ├── BinanceAdapter   → parses Binance JSON format
  ├── FinnhubAdapter   → parses Finnhub JSON format (Phase 5)
  └── GenericAdapter    → parses custom JSON format (Phase 5)
```

**Why Strategy, not if/else?** Adding a new exchange means creating one new class that implements `FeedAdapter`. Zero changes to `FeedConnection`, `ValidatorEngine`, or anything else. This is the **Open/Closed Principle** — open for extension, closed for modification.

**Key insight:** "I used the Strategy pattern for feed adapters. Each exchange sends a different JSON format, but the rest of the system only works with our unified `Tick` model. The adapter translates exchange-specific messages into Ticks. Adding Finnhub support was just one new class — no changes to validation logic."

### Binance WebSocket Protocol

```
URL: wss://stream.binance.com:9443/ws/btcusdt@trade
Subscribe: {"method":"SUBSCRIBE","params":["btcusdt@trade"],"id":1}

Trade message:
{
  "e": "trade",     ← event type (filter on this)
  "s": "BTCUSDT",   ← symbol
  "p": "45123.45",  ← price as STRING (safe for BigDecimal!)
  "q": "0.123",     ← quantity as STRING
  "T": 1711000000000, ← trade time (epoch ms)
  "t": 123456789    ← trade ID (our sequence number)
}
```

**Key insight:** Binance sends prices as strings (`"p": "45123.45"`), not numbers. This is intentional — JSON numbers would lose precision when parsed as `double`. We parse directly into `BigDecimal` from the string: `new BigDecimal(node.get("p").asText())`.

### Defensive Parsing — Return null, Don't Throw

```java
public Tick parseTick(String rawMessage) {
    try {
        JsonNode node = objectMapper.readTree(rawMessage);
        if (!node.has("e") || !"trade".equals(node.get("e").asText())) {
            return null;  // Not a trade — subscription confirmation, etc.
        }
        // ... parse fields ...
        return tick;
    } catch (Exception e) {
        log.warn("Failed to parse Binance message: {}", e.getMessage());
        return null;  // Malformed → log and skip, don't crash the feed
    }
}
```

**Why return null instead of throwing?**
- A WebSocket feed sends thousands of messages per second
- Not all messages are trades (confirmations, heartbeats, errors)
- One bad message must not crash the entire feed pipeline
- The caller (`FeedConnection`) simply skips null results

**Key insight:** "At the system boundary where external data enters, I return null for unparseable messages instead of throwing. This is a resilience pattern — one malformed message from Binance shouldn't disconnect us from the entire feed. I log the failure for observability but keep processing."

### `ObjectMapper` — One Instance, Reused

```java
private final ObjectMapper objectMapper = new ObjectMapper();
```

Jackson's `ObjectMapper` is thread-safe after configuration. Creating one per call is wasteful (~1ms each). One instance per adapter is optimal.

### Test Strategy: 14 Tests in 4 Categories

**parseTick (7 tests):**
1. Valid trade → all fields mapped correctly
2. BigDecimal precision preserved (price with 16 decimal places)
3. Non-trade message (subscription confirmation) → null
4. Malformed JSON → null (not exception)
5. Empty string → null
6. Wrong event type ("aggTrade") → null
7. Missing required field → null

**isHeartbeat (3 tests):**
8. Subscription confirmation → true
9. Trade message → false
10. Malformed → false

**Subscribe/Unsubscribe (4 tests):**
11. Subscribe message format with multiple symbols
12. Unsubscribe message format
13. Symbols converted to lowercase (Binance requirement)
14. Single-symbol subscribe

---

## Step 7: FeedConnection.java — WebSocket Connection with Auto-Reconnect

### Exponential Backoff — The Production Reconnect Pattern

```
Attempt 1: wait 1s   (2^0 * 1000)
Attempt 2: wait 2s   (2^1 * 1000)
Attempt 3: wait 4s   (2^2 * 1000)
Attempt 4: wait 8s   (2^3 * 1000)
Attempt 5: wait 16s  (2^4 * 1000)
Attempt 6+: wait 30s (capped)
After 10 failures: give up → status = ERROR
```

**Why exponential, not fixed interval?**
- Fixed 1s retries would hammer a recovering server — potentially 10 retries in 10s
- Exponential gives the server time to recover: 1s, 2s, 4s, 8s...
- Cap at 30s prevents absurdly long waits (2^10 * 1000 = 1024s = 17 minutes!)

**Key insight:** "I implemented exponential backoff with jitter-ready cap for reconnection. The formula is `min(2^attempt * 1000, 30000)ms`. This prevents thundering herd — if 100 clients disconnect simultaneously, they spread their retries over increasing intervals instead of all hitting the server at the same second. For production, I'd add jitter (random ±20%) to further decorrelate retry storms."

### Virtual Threads for Reconnect — Java 21 Feature

```java
Thread.ofVirtual().start(() -> {
    Thread.sleep(backoffMs);
    doConnect();
});
```

**Why virtual threads instead of `ScheduledExecutorService`?**
- Virtual threads are lightweight (~1 KB vs ~1 MB for platform threads)
- `Thread.sleep()` on a virtual thread doesn't block an OS thread
- No thread pool sizing decisions needed
- Perfect for I/O-bound tasks like "wait, then reconnect"

**Key insight:** "I used Java 21 virtual threads for the reconnect delay. Each sleeping reconnect attempt consumes ~1 KB instead of ~1 MB. With 100 feeds reconnecting simultaneously, that's 100 KB vs 100 MB. Virtual threads are ideal for I/O-bound waiting."

### `CopyOnWriteArrayList` for Tick Listeners

```java
private final List<Consumer<Tick>> tickListeners = new CopyOnWriteArrayList<>();
```

**Why not `ArrayList`?**
- Tick listeners are read on every incoming message (thousands per second)
- Listeners are added/removed rarely (when dashboard connects/disconnects)
- `CopyOnWriteArrayList` is optimized for this exact pattern: frequent reads, rare writes
- Iteration is lock-free — no synchronization overhead on the hot path

### `volatile boolean intentionalDisconnect`

```java
private volatile boolean intentionalDisconnect = false;
```

**Why `volatile`?**
- `disconnect()` is called from one thread (REST controller thread)
- `handleDisconnect()` runs on the WebSocket reactor thread
- `volatile` ensures the reactor thread sees the update immediately
- Without it, the reactor thread might cache the old value and keep reconnecting

### Testing Without a Real WebSocket Server

We pass `null` as the `WebSocketClient` and test everything else:
- Connection state management
- Backoff calculations (pure math — 6 tests covering the progression)
- Listener add/remove
- Adapter delegation

The actual WebSocket connection is tested in integration tests (Phase 2), not unit tests.

### Test Strategy: 15 Tests in 4 Categories

**Connection state (4):** initial status, disconnect, getter, reconnect counter
**Exponential backoff (6):** attempts 1-5 individual values, cap at 30s
**Tick listeners (3):** add, remove, multiple listeners
**Adapter delegation (2):** subscribe/unsubscribe message generation

---

## Step 8: FeedManager.java — Connection Manager + Tick Broadcasting

### `ConcurrentHashMap` for Thread-Safe Connection Registry

```java
private final Map<String, FeedConnection> connections = new ConcurrentHashMap<>();
```

Multiple threads access this map simultaneously:
- REST controller threads: add/remove/start/stop connections
- WebSocket reactor threads: internal tick broadcasting
- Health check thread: scanning for stale feeds

`ConcurrentHashMap` provides thread-safe reads without locking the entire map. Each segment locks independently → higher concurrency than `Collections.synchronizedMap()`.

### The Broadcast Pattern — Decoupling Producers from Consumers

```
FeedConnection #1 ─── tick ──→ FeedManager.broadcastTick() ──→ ValidatorEngine
FeedConnection #2 ─── tick ──→ FeedManager.broadcastTick() ──→ SessionRecorder
FeedConnection #3 ─── tick ──→ FeedManager.broadcastTick() ──→ SSE StreamController
```

Each `FeedConnection` only knows about `FeedManager` (via its tick listener). `FeedManager` broadcasts to all global listeners. No feed connection knows about validators or recorders — **single responsibility**.

**Key insight:** "FeedManager acts as a message bus. Feed connections produce ticks, and consumers (validators, recorder, SSE) register as global listeners. This decouples producers from consumers — adding a new consumer is just `feedManager.addGlobalTickListener(newConsumer)`. No changes to feed code."

### Health Check — Stale Feed Detection

```java
public List<String> checkHealth() {
    Instant cutoff = Instant.now().minus(STALE_THRESHOLD);
    return connections.values().stream()
            .filter(fc -> fc.isConnected())        // only check connected feeds
            .filter(fc -> lastTick.isBefore(cutoff)) // no tick in 30s
            .map(fc -> fc.getConnection().getId())
            .toList();
}
```

**Why 30 seconds?** Binance sends a trade every ~100ms for BTC. 30s of silence means either the feed is dead or the market is closed. This feeds into the StatefulValidator's stale data detection.

### Java 21 Switch Expression for Adapter Factory

```java
FeedAdapter createAdapter(Connection.AdapterType type) {
    return switch (type) {
        case BINANCE -> new BinanceAdapter();
        case FINNHUB -> throw new UnsupportedOperationException("Phase 5");
        case GENERIC -> throw new UnsupportedOperationException("Phase 5");
    };
}
```

Java 21 switch expressions are **exhaustive** — if you add a new enum value, the compiler forces you to handle it. No forgotten `default` case that silently swallows new adapter types.

### Test Strategy: 19 Tests in 5 Categories

**CRUD (6):** add, add multiple, remove, remove nonexistent, get unknown, get all empty
**Start/Stop (3):** start nonexistent, stop nonexistent, stop sets DISCONNECTED
**Active count (1):** reflects connection status changes
**Global listeners (2):** add, remove
**Health check (4):** empty, stale detected, disconnected ignored, recently active ignored
**Adapter creation (3):** Binance works, Finnhub/Generic throw UnsupportedOperationException

---

## Step 9: Validator Interface + OrderingValidator — Idempotent Stream Validation

### The Validator Interface (Strategy Pattern)

```java
public interface Validator {
    String getArea();
    void onTick(Tick tick);
    ValidationResult getResult();
    void reset();
    void configure(Map<String, Object> config);
}
```

**Why an interface, not abstract class?** All 8 validators (ACCURACY, LATENCY, COMPLETENESS, RECONNECTION, THROUGHPUT, ORDERING, SUBSCRIPTION, STATEFUL) share zero implementation — each has completely different state and logic. An interface is the cleanest fit: it defines the *contract* without imposing structure.

**Key insight:** "The Validator interface uses the Strategy pattern. The ValidatorEngine holds a `List<Validator>` and calls `onTick()` on each. Adding a new validator means implementing 5 methods and registering it — zero changes to existing code. This is the Open/Closed Principle in action."

### Idempotent Processing — Core Distributed Systems Concept

```java
Long lastSeq = lastSequenceBySymbol.get(tick.getSymbol());
if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
    return; // Already processed — skip
}
```

**Why?** In a real streaming system, network retries, reconnects, and WebSocket replays can deliver the same tick twice. Without idempotency, an out-of-order replay would corrupt metrics (double-counted ticks, false ordering violations).

**Key insight:** Idempotency is tracked *per symbol*, not globally. Symbol "BTCUSDT" seq 5 and "ETHUSDT" seq 5 are different streams.

**Key insight:** "Every validator uses sequence-based idempotent processing. If a tick's sequence number is ≤ the last processed for that symbol, we skip it. This makes the system safe against network retries, WebSocket reconnects, and at-least-once delivery — which is the default guarantee in most real-time systems."

### Per-Symbol State with ConcurrentHashMap

```java
private final Map<String, Instant> lastTimestampBySymbol = new ConcurrentHashMap<>();
private final Map<String, Long> lastSequenceBySymbol = new ConcurrentHashMap<>();
```

**Why per-symbol?** Each symbol is an independent time series. BTCUSDT timestamps increasing doesn't mean ETHUSDT timestamps should follow the same sequence. Mixing them would produce false positives.

**Why ConcurrentHashMap over synchronized HashMap?** ConcurrentHashMap uses lock striping — different keys can be read/written concurrently without blocking. In a system receiving ticks from multiple feeds, this avoids a global lock bottleneck.

### AtomicLong Counters for Thread Safety

```java
private final AtomicLong totalTicks = new AtomicLong(0);
private final AtomicLong outOfOrderCount = new AtomicLong(0);
```

**Why not just `long`?** Multiple threads (one per WebSocket connection) can call `onTick()` concurrently. A plain `long++` is a read-modify-write — not atomic. AtomicLong uses CAS (Compare-And-Swap) hardware instructions for lock-free thread-safe increments.

**Key insight:** "We use AtomicLong for counters and ConcurrentHashMap for per-symbol state. Both are lock-free concurrency primitives — they use CAS instructions instead of `synchronized`, so multiple feed threads can update the validator simultaneously without blocking."

### Business Rule Validation in a Streaming Context

The OrderingValidator checks more than just timestamp ordering:

| Rule | Check | Why |
|---|---|---|
| **Timestamp ordering** | `exchangeTimestamp < lastTimestamp` per symbol | Detects network reordering or exchange issues |
| **Bid ≤ Ask** | `bid.compareTo(ask) > 0` → violation | Crossed book = either bad data or exchange glitch |
| **Volume ≥ 0** | `volume.compareTo(ZERO) < 0` → violation | Negative volume is always invalid tick data |

**Key insight:** "The OrderingValidator does three things: timestamp ordering per symbol, bid-ask spread sanity, and volume sign checks. These are 'invariant' checks that should always hold regardless of market conditions. A bid > ask means either the data is corrupt or there's an exchange-level arbitrage opportunity that would be instantly consumed."

### Configurable Thresholds with configure()

```java
public void configure(Map<String, Object> config) {
    if (config.containsKey("passThreshold")) {
        passThreshold = ((Number) config.get("passThreshold")).doubleValue();
    }
}
```

**Why `Number` cast, not `Double`?** JSON deserialization might produce an Integer (e.g., `95` vs `95.0`). Casting to `Number` then calling `.doubleValue()` handles both Integer and Double safely.

**Default thresholds:** PASS ≥ 99.99%, WARN ≥ 99.0%, FAIL < 99.0%. These match real production monitoring — one out-of-order tick per 10,000 is acceptable; one per 100 is a problem.

### O(1) Per Tick — No Sorting, No Growing Lists

Every validator must be O(1) per tick. OrderingValidator achieves this:
- One `ConcurrentHashMap.get()` + `put()` per tick = O(1) amortized
- One `AtomicLong.incrementAndGet()` = O(1)
- No lists, no sorting, no buffering of individual ticks

**Key insight:** "At 100K ticks/second, any O(n) operation in the hot path would be fatal. That's why the blueprint mandates O(1) per tick. We use hash maps for lookups and atomic counters for aggregation — never lists or sorts."

### Test Strategy: 17 Tests in 7 Categories

**Area (1):** `getArea()` returns "ORDERING"
**In-order (2):** sequential timestamps → PASS, no ticks → PASS with message
**Out-of-order (2):** single detection, multiple consecutive out-of-order
**Per-symbol (1):** different symbols tracked independently
**Threshold (1):** 50% ordering rate → FAIL
**Idempotent (2):** duplicate sequence skipped, older sequence skipped
**Business rules (4):** bid > ask violation, bid ≤ ask OK, null bid/ask OK, negative volume
**Reset (1):** clears all state (counters, maps, result)
**Configure (1):** custom thresholds change PASS/WARN/FAIL boundaries
**Result details (1):** result map contains expected keys

---

## Step 10: AccuracyValidator — Price Validation with BigDecimal Arithmetic

### Three Accuracy Rules from the Blueprint

| Rule | Check | What It Catches |
|---|---|---|
| **Price > 0** | `price.compareTo(ZERO) <= 0` | Zero or negative prices from bad data or parsing errors |
| **Bid ≤ Ask** | `bid.compareTo(ask) > 0` | Crossed book — corrupt data or exchange glitch |
| **Large move < 10%** | `abs(current - previous) / previous > 0.10` | Erroneous spikes, fat-finger trades, or bad decimal placement |

**Key insight:** "AccuracyValidator detects three classes of data quality issues: impossible prices (zero/negative), crossed bid-ask spreads, and suspiciously large single-tick moves. Each is an independent check — a tick can fail multiple rules simultaneously. The accuracy rate is `validTicks / totalTicks` where a tick is 'valid' only if it passes ALL three rules."

### BigDecimal Division — Why MathContext.DECIMAL64

```java
BigDecimal percentChange = change.divide(prevPrice, MathContext.DECIMAL64);
```

**Problem:** `BigDecimal.divide()` without a MathContext throws `ArithmeticException` for non-terminating decimals (e.g., `1/3 = 0.3333...`).

**Solution:** `MathContext.DECIMAL64` provides 16 significant digits with IEEE 754 rounding — more than enough for percentage calculations, and guaranteed to terminate.

**Key insight:** "We use MathContext.DECIMAL64 for the percentage calculation because BigDecimal division can produce non-terminating decimals. DECIMAL64 gives 16 significant digits — same as a Java `double` in precision, but computed in BigDecimal to avoid floating-point drift. The key insight is that BigDecimal is not just about precision — it's about *control over rounding*."

### Why BigDecimal and Not double for Prices

```java
// WRONG: double arithmetic
double change = Math.abs(current - previous) / previous;

// RIGHT: BigDecimal arithmetic  
BigDecimal change = tick.getPrice().subtract(prevPrice).abs();
BigDecimal percentChange = change.divide(prevPrice, MathContext.DECIMAL64);
```

**Problem with double:** `0.1 + 0.2 = 0.30000000000000004` in IEEE 754. For financial data, this causes:
- Bid/ask comparison failures on equal values
- Threshold comparisons that flip incorrectly at boundaries
- Accumulating rounding errors over millions of ticks

**Key insight:** "We use BigDecimal for all price comparisons, not double. This is a non-negotiable rule in financial software. Doubles use binary floating-point which can't represent 0.1 exactly. When you're comparing bid vs ask prices, even one ULP of error can cause a false violation. BigDecimal uses decimal arithmetic — what you write is what you get."

### Per-Symbol Price Tracking

```java
private final Map<String, BigDecimal> lastPriceBySymbol = new ConcurrentHashMap<>();
```

The large-move check compares each tick against the **previous tick for the same symbol**. Important: after an out-of-range tick, we still update `lastPriceBySymbol` — the next comparison is against the new (possibly anomalous) price. This is deliberate: if the market genuinely moved 15%, the next tick at the new level shouldn't also be flagged.

### Accuracy Rate Formula

```
accuracyRate = 100.0 * validTicks / totalTicks
```

A tick is "valid" only if it passes ALL three rules. This means a single tick with both a negative price AND a bid>ask violation still counts as just one invalid tick (not two). This gives the cleanest accuracy metric — *what percentage of ticks had zero data quality issues*.

### Configurable largeMovePercent

```java
validator.configure(Map.of("largeMovePercent", "0.05")); // 5% threshold
```

Why configurable? Different asset classes have different volatility:
- Blue-chip stocks: 2-3% daily moves are rare → 5% threshold makes sense
- Crypto: 10% intraday swings are normal → 10% is the right default
- Penny stocks/small caps: Need even looser thresholds

### Test Strategy: 22 Tests in 9 Categories

**Area (1):** `getArea()` returns "ACCURACY"
**Valid ticks (2):** sequential valid prices → PASS, no ticks → PASS
**Price validation (3):** zero price invalid, negative invalid, null invalid
**Bid/ask (3):** bid > ask violation, bid ≤ ask valid, null bid/ask no violation
**Large move (5):** >10% detected, <10% valid, exactly 10% not flagged, first tick never large, per-symbol tracking
**Idempotent (2):** duplicate seq skipped, older seq skipped
**Accuracy rate (2):** rate calculation + FAIL status, WARN range
**Reset (1):** clears all counters and maps
**Configure (2):** custom large move threshold, custom pass/warn thresholds
**Result details (1):** result map contains all expected keys

---

## Step 11: LatencyValidator — Circular Buffer Percentiles & Spike Detection

### Circular Buffer — The Bounded Memory Pattern

```java
private long[] buffer;
private int writeIndex;
private int count;

buffer[writeIndex] = latencyMs;
writeIndex = (writeIndex + 1) % bufferSize;
if (count < bufferSize) {
    count++;
}
```

**Why a raw `long[]` instead of `ArrayList<Long>`?**
- `long[]` uses 8 bytes per entry. `ArrayList<Long>` boxes each value → 16 bytes per Long object + 8 bytes per reference + GC pressure.
- For 10,000 entries: array = 80KB, ArrayList ≈ 240KB + object headers + GC pauses.
- In a hot path processing 100K ticks/second, avoiding autoboxing is critical.

**Circular buffer invariant:** `writeIndex` wraps around using modulo. Once the buffer fills (`count == bufferSize`), old entries are silently overwritten — no memory growth, ever. This is the "bounded memory" mandate from the blueprint.

**Key insight:** "The latency buffer is a fixed-size circular array of primitives. At 10,000 entries of `long`, it's exactly 80KB. It never grows, never allocates objects, and never triggers GC. The write pointer wraps with modulo arithmetic. This is the standard pattern for streaming latency tracking in low-latency systems."

### Percentile Calculation — Sort-Based Approach

```java
private long calculatePercentile(int percentile) {
    long[] sorted = new long[count];
    System.arraycopy(buffer, 0, sorted, 0, count);
    Arrays.sort(sorted);
    int index = (int) Math.ceil(percentile / 100.0 * count) - 1;
    return sorted[index];
}
```

**Why copy-then-sort?** The original buffer must maintain insertion order for the circular overwrite to work. We sort a copy to find percentiles.

**Complexity:** O(n log n) per `getResult()` call. This is fine because `getResult()` is called at dashboard refresh rate (once per second), not once per tick. The per-tick `onTick()` is O(1) — only buffer write + counter increment.

**Blueprint note:** "Use T-Digest for production." T-Digest gives O(1) percentile queries with O(log n) insertion and ~1KB memory. For this implementation, the sort-based approach is simpler and correct.

**Key insight:** "Percentile calculation is O(n log n) but only happens on `getResult()` — the dashboard polling path, not the tick hot path. The hot path `onTick()` is strictly O(1): one array write and one atomic increment. If we needed O(1) percentile queries too, we'd use T-Digest, but the current approach prioritizes simplicity and correctness."

### Spike Detection — Statistical Anomaly in Streaming Context

```java
if (count >= 100) { // Need minimum samples for meaningful p99
    long currentP99 = calculatePercentile(99);
    if (latencyMs > currentP99 * 3) {
        spikeCount.incrementAndGet();
    }
}
```

**Why p99 × 3?** This is a heuristic for "extreme outlier." If p99 is 100ms (99% of ticks are under 100ms), then a tick at 300ms+ is a 3× anomaly — almost certainly a network issue, GC pause, or exchange delay.

**Why minimum 100 samples?** With fewer samples, p99 is meaningless (you'd be comparing against the 1st or 2nd value). 100 samples gives a statistically reasonable p99.

**Testing insight:** Spike detection shifts p99 upward as spike values enter the buffer. With a small buffer, 20 spike values at 400ms would push p99 to 400ms, making subsequent 400ms ticks non-spikes. The fix: use a buffer large enough (2000+) so spike values stay < 1% of data.

**Key insight:** "Spike detection compares each latency against 3× the current p99. The tricky part is that spikes themselves shift p99 upward, so the buffer must be large enough that spike values remain in the tail. In production, you'd use a separate EWM (exponentially weighted moving) baseline that's resistant to outlier contamination."

### ReentrantLock vs synchronized — Why Lock Here

```java
private final ReentrantLock bufferLock = new ReentrantLock();

bufferLock.lock();
try {
    // buffer read/write
} finally {
    bufferLock.unlock();
}
```

**Why not `synchronized`?** Both work, but `ReentrantLock` has advantages:
- Explicit lock/unlock makes the critical section visible in code review
- Supports `tryLock()` for non-blocking attempts (useful if we add timeout logic)
- Can have multiple condition variables (not needed here, but a design option)

**Why not lock-free like AtomicLong?** The buffer operations require *multiple* steps atomically (write value + update index + update count + check min/max). CAS only works for single-variable updates. The lock ensures the entire write is atomic.

**Key insight:** "We use ReentrantLock for the buffer because the critical section involves multiple correlated updates — write, index increment, count, min/max. AtomicLong works great for independent counters, but when you need to update multiple variables atomically, you need a lock. We chose ReentrantLock over synchronized for its explicit semantics and future extensibility."

### Three-Tier Status Logic

```
FAIL: p95 >= threshold × 2 (1000ms) OR spikeCount >= 20
WARN: p95 >= threshold (500ms) OR spikeCount >= 5
PASS: everything below WARN thresholds
```

**Key insight:** The condition checks FAIL first, then WARN, then defaults to PASS. This means FAIL takes priority — if both conditions are met, you see FAIL, not WARN.

**Also: metric is p95, not p99.** The result's `metric` field is p95 because p95 is the standard SLA metric for latency ("95% of requests complete within X ms"). p99 is used for spike detection but not for the overall status.

### Test Strategy: 22 Tests in 10 Categories

**Area (1):** `getArea()` returns "LATENCY"
**No ticks (1):** PASS with default message
**Basic tracking (2):** single tick records latency, multiple ticks track min/max
**Percentiles (2):** sequential 1-100ms calculated correctly, uniform latency returns same for all percentiles
**Circular buffer (2):** bounds memory to configured size, overwrites old values
**Status thresholds (3):** low latency → PASS, high p95 → WARN, very high p95 → FAIL
**Spike detection (4):** spike detected above 3×p99, no spike below, requires minimum 100 samples, 20+ spikes → FAIL
**Idempotent (3):** duplicate seq skipped, older seq skipped, per-symbol independence
**Reset (1):** clears buffer, counters, sequences, returns to PASS
**Configure (1):** custom threshold changes WARN boundary
**Result details (1):** result map contains all expected keys
**Result metric (1):** metric value equals p95

---

## Phase 2, Step 2: TickStore + SessionStore — JDBC Repository Layer

### Why JdbcTemplate over JPA?
- **Simplicity with simple schemas:** 5 flat tables, no entity relationships → JPA overhead (lazy loading, dirty checking, proxy objects) adds complexity without value.
- **Key insight:** "We chose JdbcTemplate because our schema is flat and query patterns are fixed. JPA's object-relational mapping adds complexity we don't need for a tool that records ticks and sessions."

### Session Model — Recording Lifecycle
- **Status enum:** `RECORDING → COMPLETED | FAILED` — represents the recording state machine.
- `startedAt`: Set when recording begins. `endedAt`: Set on completion.
- `tickCount` and `byteSize`: Aggregated stats, set at completion time.
- **Why a separate model from Connection?** Connection = runtime WebSocket state; Session = persisted recording metadata.

### TickStore — Batch Inserts & RowMapper
- **`saveBatch(List<Tick>)`** uses `jdbc.batchUpdate()` — single round-trip for N inserts instead of N round-trips.
- **RowMapper pattern:** Static `ROW_MAPPER` converts ResultSet → Tick. Reads TEXT columns as BigDecimal via `new BigDecimal(rs.getString(...))` — preserves arbitrary precision.
- **Timestamps as TEXT in SQLite:** Stored as ISO-8601 strings (`Instant.toString()`), parsed back with `Instant.parse()`. SQLite has no native timestamp type.
- **Nullable columns:** `bid`, `ask`, `volume`, `session_id` can be null — `rs.getString()` returns null naturally, and we check before constructing BigDecimal.
- **Q: "How do you handle BigDecimal in SQLite?"** "We store as TEXT to preserve exact decimal representation. Converting through REAL (double) would lose precision — e.g., 0.1 can't be represented exactly in IEEE 754."

### SessionStore — KeyHolder Pattern
- **Auto-increment ID retrieval:** `GeneratedKeyHolder` captures the SQLite-generated `INTEGER PRIMARY KEY` after insert.
- **PreparedStatement with RETURN_GENERATED_KEYS:** Required by JDBC spec to tell the driver we want auto-generated keys back.
- **`complete()` method sets `Instant.now()` server-side** — not accepting endedAt as parameter to ensure consistent timestamps.

### Test Architecture — @SpringBootTest + In-Memory SQLite
- **`@ActiveProfiles("test")`** activates `application-test.properties` → `jdbc:sqlite:file::memory:?cache=shared`.
- **`cache=shared`** is critical — without it, each connection gets its own in-memory DB (schema in one, data in another).
- **`@BeforeEach` cleanup:** `DELETE FROM ticks; DELETE FROM sessions;` — ensures test isolation without recreating schema.
- **Why not `@JdbcTest`?** We need the full context (SqliteConfig CommandLineRunner must run to create tables).

### 12 TickStore Tests — What They Cover
**Save & retrieve (4):** save+find round-trip, BigDecimal precision preserved, nullable bid/ask/volume, bid/ask values preserved
**Batch insert (2):** batch saves all ticks, empty batch is a no-op
**Find by symbol (2):** time-range filtering works, results ordered by exchange_timestamp
**Count (1):** per-session counts, zero for unknown session
**Delete (1):** cascaded delete per session, other sessions unaffected
**Nullable session (1):** null session_id doesn't throw
**Timestamp round-trip (1):** exchangeTimestamp and receivedTimestamp survive TEXT serialization

### 12 SessionStore Tests — What They Cover
**Create & retrieve (3):** auto-ID generation, full field round-trip, missing ID returns empty
**Find all (1):** ordered by started_at DESC (newest first)
**Update status (1):** changes status only, other fields untouched
**Complete (1):** sets status=COMPLETED, endedAt, tickCount, byteSize in one UPDATE
**Delete (2):** removes target, leaves others intact
**Count (2):** zero when empty, correct total after inserts
**Timestamps (2):** startedAt preserved through round-trip, endedAt null while RECORDING

### Running Total: 226 tests passing

---

## Phase 2, Step 3: FeedController.java — REST API for Feed Management

### Controller Architecture — @WebMvcTest vs @SpringBootTest
- **`@WebMvcTest(FeedController.class)`** loads ONLY the web layer: the controller, MockMvc, and Jackson. No database, no FeedManager bean, no full context.
- **`@MockBean`** (Spring Boot 3.2: `org.springframework.boot.test.mock.mockito.MockBean`) injects a Mockito mock of `FeedManager` into the web context.
- **Key insight:** "We use `@WebMvcTest` for controller tests because it's 10× faster than `@SpringBootTest` — it only loads the MVC slice, not the full application context. We mock the service layer with `@MockBean`."

### SSRF Prevention — Why and How
- **The threat:** A user could POST `wss://192.168.1.1/internal-service` and our server would connect to an internal host, leaking data or attacking internal systems.
- **Defense:** `validateFeedUrl()` resolves the hostname via `InetAddress.getByName()` and checks:
  - `isLoopbackAddress()` — blocks `127.0.0.1`, `::1`
  - `isSiteLocalAddress()` — blocks `192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`
  - `isLinkLocalAddress()` — blocks `169.254.x.x`
  - `isAnyLocalAddress()` — blocks `0.0.0.0`
- **Scheme restriction:** Only `ws://` and `wss://` allowed — blocks `http://`, `file://`, `ftp://`.
- **Q: "How do you prevent SSRF?"** "We validate the URL at the API boundary before passing it to FeedManager. We resolve the hostname and reject private/loopback/link-local IPs. We also enforce WebSocket-only schemes."

### REST Design Patterns
- **Consistent error shape:** All errors return `{"error": "message"}` via `Map.of("error", ...)`.
- **HTTP status codes:** 201 CREATED for POST, 204 NO CONTENT for DELETE, 404 for unknown IDs, 400 for validation failures.
- **Idempotent GET:** `listFeeds()` returns the current state, no side effects.
- **PUT with guards:** URL changes blocked while connection is CONNECTED/RECONNECTING — must stop first.
- **Subscribe/Unsubscribe:** Body is `{"symbols": ["ETHUSDT"]}`. Subscribe deduplicates (won't add existing symbols). Unsubscribe removes matching.

### 8 Endpoints, One Responsibility Each
| Endpoint | What it does |
|----------|-------------|
| `GET /api/feeds` | Delegates to `feedManager.getAllConnections()` |
| `POST /api/feeds` | Validates URL (SSRF), delegates to `feedManager.addConnection()` |
| `PUT /api/feeds/{id}` | Updates name/url/symbols/adapterType on existing connection |
| `DELETE /api/feeds/{id}` | Delegates to `feedManager.removeConnection()` |
| `POST /api/feeds/{id}/start` | Delegates to `feedManager.startConnection()` |
| `POST /api/feeds/{id}/stop` | Delegates to `feedManager.stopConnection()` |
| `POST /api/feeds/{id}/subscribe` | Adds symbols to connection's list (deduplicated) |
| `POST /api/feeds/{id}/unsubscribe` | Removes symbols from connection's list |

### 29 FeedController Tests — What They Cover
**List feeds (2):** returns all connections, returns empty list
**Add feed (5):** returns 201, blocks non-ws scheme, blocks loopback, blocks private IPs, blocks null URL
**Update feed (3):** changes name, rejects URL change while connected, 404 for unknown
**Remove feed (2):** returns 204, returns 404 when not found
**Start feed (3):** returns 200, returns 400 when already active, 404 for unknown
**Stop feed (3):** returns 200, returns 400 when already stopped, 404 for unknown
**Subscribe (4):** adds new symbols, deduplicates, 400 when no symbols, 404 for unknown
**Unsubscribe (3):** removes symbols, 400 when no symbols, 404 for unknown
**SSRF validation (4):** accepts valid wss, rejects http, rejects null, rejects blank

### Gotcha: @MockBean Package in Spring Boot 3.2
- **Correct import:** `org.springframework.boot.test.mock.mockito.MockBean`
- **NOT** `org.springframework.boot.test.mock.bean.MockBean` (doesn't exist in 3.2.5)
- **NOT** `org.springframework.test.context.bean.override.mockito.MockitoBean` (that's Spring Boot 3.4+)

### Running Total: 255 tests passing

---

## Phase 2, Step 4: ValidationController.java — Validation REST API

### 4 Endpoints, Thin Controller
| Endpoint | Verb | What it does |
|----------|------|-------------|
| `/api/validation/summary` | GET | Returns `results` (all validators) + `overallStatus` + `ticksProcessed` + `timestamp` |
| `/api/validation/history` | GET | Returns `List<ValidationResult>` — current snapshot from engine |
| `/api/validation/config` | PUT | Accepts `{"area":"LATENCY","config":{...}}` or `{"config":{...}}` for all |
| `/api/validation/reset` | POST | Calls `engine.reset()`, returns confirmation + timestamp |

### Overall Status Computation — Worst Wins
- **FAIL > WARN > PASS**: If any validator has FAIL, overall is FAIL. If any has WARN (but no FAIL), overall is WARN.
- Computed live each time `/summary` is called — no caching, no state outside ValidatorEngine.
- **Key insight:** "We compute the worst status across all validators on each request. The controller is stateless — all state lives in ValidatorEngine, which fans out ticks to all validator implementations."

### Making Validators Spring-Managed — @Component Everywhere
- **Problem:** Adding controllers that depend on `ValidatorEngine` broke `@SpringBootTest` — Spring couldn't find the bean.
- **Solution:** Added `@Component` to all 5 validators + `ValidatorEngine`.
- **Spring auto-injection magic:** `ValidatorEngine(List<Validator> validators)` — Spring sees the constructor param is `List<Validator>`, finds all `@Component` beans implementing `Validator`, and injects them all automatically.
- **Q: "How does Spring inject the validator list?"** "Spring Boot's constructor injection collects all beans of the matching interface type into a List. We annotate each validator with @Component and Spring discovers them via classpath scanning. ValidatorEngine doesn't know which validators exist — it just gets a list."
- **Key insight:** This is the Open/Closed Principle in action — adding a new validator (e.g., ThroughputValidator) requires only creating the class with @Component. No changes to ValidatorEngine or ValidationController.

### @WebMvcTest with @MockBean — Controller Testing Pattern
- **`@WebMvcTest(ValidationController.class)`** loads only the controller + MockMvc.
- **`@MockBean ValidatorEngine`** replaces the real engine with a Mockito mock — no validators run during controller tests.
- **Test focus:** HTTP status codes, JSON response shape, delegation to engine methods (verified with `verify(engine)`).

### 13 ValidationController Tests — What They Cover
**Summary (4):** all-pass → PASS, any-fail → FAIL, warn-only → WARN, empty results → PASS
**History (2):** ordered results returned, empty list when no results
**Config (4):** updates specific area, updates all when no area, 400 when no config map, 400 when config empty
**Reset (2):** calls engine.reset(), idempotent (calling twice is fine)
**Overall status (1):** FAIL takes precedence over WARN

### Running Total: 268 tests passing

---

## Phase 2, Step 5: SessionRecorder.java — Tick Recording Service

### What SessionRecorder Does
- **Start:** Creates a `Session` row in DB (via `SessionStore.create()`), sets `recording = true`, zeros counters.
- **onTick():** Buffers incoming ticks. Flushes to DB when buffer hits 100 ticks OR 1 second elapses (whichever first).
- **Stop:** Final flush, calls `SessionStore.complete()` to set COMPLETED + endedAt + stats, clears state.
- **Registered as FeedManager global listener:** `feedManager.addGlobalTickListener(recorder::onTick)`.

### Batched Writes — Why Not Write Every Tick?
- **Problem:** SQLite can handle ~500 writes/sec on HDD. At 10,000 ticks/sec, per-tick INSERT would be a bottleneck.
- **Solution:** Buffer up to 100 ticks, then do a single `jdbc.batchUpdate()` call — one round-trip for 100 inserts.
- **The 1-second timer** ensures data isn't lost if tick rate is low (e.g., 5 ticks/sec during quiet market hours).
- **Key insight:** "We batch ticks to avoid the SQLite write bottleneck. The dual trigger — batch size OR time interval — balances throughput and latency. High-frequency feeds hit the batch size trigger; quiet feeds hit the time trigger."

### ReentrantLock — Why Not synchronized?
- `ReentrantLock` is used because `onTick()` is called from FeedManager's broadcast thread, while `start()`/`stop()` are called from HTTP request threads.
- Explicit `lock.lock()` / `unlock()` in try-finally is clearer than `synchronized` blocks when the critical section spans multiple methods and has complex control flow.
- **Double-checked locking in onTick():** `volatile boolean recording` is checked BEFORE acquiring the lock (fast path — skips lock entirely when not recording), then rechecked AFTER lock acquisition (correctness — prevents race with concurrent `stop()`).

### estimateTickSize() — Rough Byte Accounting
- Sums string lengths of symbol, price, feedId + fixed overhead for timestamps/seqNum.
- Not exact, but consistent across ticks — gives a useful "session size" metric.
- Used by `Session.byteSize` field for UI display (e.g., "2.5 MB recorded").

### Singleton Bean Gotcha — Test Isolation
- **Bug found:** `ticksIgnoredWhenNotRecording` initially asserted `getTickCount() == 0`, but `tickCount` is an instance field on the singleton `SessionRecorder` Spring bean.
- **Fix:** Capture `getTickCount()` BEFORE calling `onTick()`, verify it doesn't change. Also verify buffer is empty.
- **Lesson:** When testing Spring singletons, don't assume clean state — the bean persists across tests. Either reset state in `@BeforeEach` or use relative assertions.

### 16 SessionRecorder Tests — What They Cover
**Start (3):** creates session in DB, sets recording flag, throws if already recording
**onTick (4):** flushes at batch size, buffers below batch size, ignored when not recording, session ID set automatically
**Tick count (1):** tracks all ticks across multiple batches (250 ticks)
**flushNow (1):** manually triggers flush of buffered ticks
**Stop (5):** flushes remaining + finalizes session, all ticks in DB, clears recording state, throws if not recording, updates session in DB
**Multiple sessions (1):** can start new session after stopping previous
**Precision (1):** BigDecimal price survives buffer → batch insert → DB → read round-trip

### Running Total: 284 tests passing

---

## Phase 2, Step 6: SessionController.java — Session REST API

### 7 Endpoints — Recording, Export, Replay
| Endpoint | Verb | What it does |
|----------|------|-------------|
| `/api/sessions` | GET | List all sessions (newest first via `SessionStore.findAll()`) |
| `/api/sessions/start` | POST | Start recording — delegates to `SessionRecorder.start()` |
| `/api/sessions/{id}/stop` | POST | Stop recording — validates active session ID matches |
| `/api/sessions/{id}` | DELETE | Delete session + all its ticks (blocks if actively recording) |
| `/api/sessions/{id}/ticks` | GET | Get all ticks from a recorded session |
| `/api/sessions/{id}/export` | GET | Export as JSON (default) or CSV (`?format=csv`) |
| `/api/sessions/{id}/replay` | POST | Replay all ticks through ValidatorEngine, return results |

### Replay — Offline Validation of Recorded Data
- **`engine.reset()`** called first to clear previous state.
- All ticks loaded from DB and fed sequentially into `engine.onTick()`.
- Returns `{ sessionId, ticksReplayed, results }`.
- **Key insight:** "Replay lets you run the same data through validators repeatedly during development. You record a 5-minute BTC session, then iterate on validator logic without needing a live market."

### CSV Export — StringBuilder, Not a Library
- Header: `symbol,price,bid,ask,volume,sequenceNum,exchangeTimestamp,receivedTimestamp,feedId`
- Nullable fields (`bid`, `ask`, `volume`) emit empty string when null.
- Response headers: `Content-Type: text/csv` + `Content-Disposition: attachment; filename="session-{id}.csv"`.
- No CSV library needed — our data has no commas/quotes in values (symbols are alphanumeric, prices are numeric strings).

### Safety Guards
- **Can't start while recording:** Returns 400 with active session ID.
- **Can't stop wrong session:** Validates path `{id}` matches `recorder.getCurrentSession().getId()`.
- **Can't delete while recording:** Returns 400 — must stop first.
- **Delete cascades:** `tickStore.deleteBySessionId()` first, then `sessionStore.delete()`.

### Controller Dependency Injection — 4 Beans
```java
SessionController(SessionRecorder recorder, SessionStore sessionStore,
                  TickStore tickStore, ValidatorEngine engine)
```
- **SessionRecorder:** For start/stop lifecycle
- **SessionStore:** For listing/finding/deleting sessions
- **TickStore:** For querying/deleting ticks
- **ValidatorEngine:** For replay (feed ticks, get results)

### 21 SessionController Tests — What They Cover
**List sessions (2):** returns all, returns empty
**Start recording (4):** returns 201, blocks when already recording, missing name, missing feedId
**Stop recording (3):** returns 200, 400 when not recording, 400 when wrong session ID
**Delete session (3):** returns 204 + cascades, 404 when not found, 400 when actively recording
**Get ticks (2):** returns tick list, 404 when session not found
**Export (4):** JSON with ticks, defaults to JSON format, CSV with headers, 404 when not found
**Replay (3):** feeds ticks to engine + returns results, 404 when not found, resets engine before replay

### Running Total: 305 tests passing

---

## Step 12: CompletenessValidator — Sequence Gap Detection & Staleness Monitoring

### Two Completeness Signals: Gaps and Staleness

| Signal | Detection | Meaning |
|---|---|---|
| **Sequence gap** | `current.seqNum != last.seqNum + 1` | Messages were dropped in transit — network loss, exchange outage, or subscriber overflow |
| **Staleness** | `timeSinceLastTick > heartbeatThreshold` | Feed went silent — connection may be alive but data stopped flowing |

**Key insight:** "Completeness is about detecting *missing data*, which manifests in two ways: sequence gaps (known messages that never arrived) and staleness (unexplained silence). Both are critical because a trading system that *thinks* it has complete data when it doesn't is worse than one that knows it's incomplete — the former can trade on stale prices."

### Gap Counting — Missed Messages, Not Just Events

```java
if (lastSeq != null && tick.getSequenceNum() != lastSeq + 1) {
    long missedCount = tick.getSequenceNum() - lastSeq - 1;
    gapCount.addAndGet(missedCount);
}
```

**Key insight:** A jump from seq 1 to seq 5 is **3 gaps** (missing 2, 3, 4), not 1 gap event. This matters because the gap count represents *how many messages were lost*, which directly maps to how much data is missing. One large gap is worse than one small gap.

**Key insight:** "We count individual missed messages, not gap events. If sequence jumps from 1 to 100, that's 98 missed messages, not '1 gap'. This gives an accurate completeness metric — if you lost 98 ticks in one burst, that's much worse than losing 1 tick in 1 burst, even though both are '1 gap event'."

### Staleness Recovery — Dynamic Set

```java
if (gapMs > heartbeatThresholdMs) {
    staleSymbols.add(tick.getSymbol());
} else {
    staleSymbols.remove(tick.getSymbol());
}
```

**Why remove on recovery?** Staleness is a *current state*, not a historical count. A symbol that was stale but resumed ticking is no longer stale. The `staleSymbols` set represents *right now*, not *ever was stale*.

**Why ConcurrentHashMap.newKeySet()?** It's the concurrent equivalent of `HashSet`. Thread-safe add/remove/contains without explicit locking. Under the hood, it's a `ConcurrentHashMap<E, Boolean>` where the value is always `Boolean.TRUE`.

**Key insight:** "Stale symbols are tracked as a live set, not a counter. When a symbol resumes sending data within the threshold, it's removed from the stale set. This means the result reflects *current* feed health, not historical — which is what a real-time monitoring dashboard needs."

### First Tick for a Symbol — No Gap

```java
Long lastSeq = lastSeqBySymbol.get(tick.getSymbol());
if (lastSeq != null && tick.getSequenceNum() != lastSeq + 1) {
    // Only check if we have a prior sequence to compare against
}
```

The first tick for a symbol (when `lastSeq == null`) is never counted as a gap, even if its sequence number is 100. We can't know what came before we started listening.

### Status Logic: Staleness Trumps Gaps

```
FAIL: gapCount >= 5 OR staleSymbols.size() > 0
WARN: gapCount > 0 (but < 5)
PASS: gapCount == 0 AND staleSymbols.isEmpty()
```

**Key:** A stale symbol is always FAIL, regardless of gap count. A silent feed is the worst case — you don't even know how much you're missing.

### Per-Symbol Independence Pattern (Recurring Theme)

This is the third validator (after Ordering and Accuracy) using per-symbol state maps. The pattern is now clear:

```java
private final Map<String, Long> lastSeqBySymbol = new ConcurrentHashMap<>();
private final Map<String, Instant> lastTickTimeBySymbol = new ConcurrentHashMap<>();
```

Each symbol is an independent data stream. BTCUSDT sequence 5 followed by ETHUSDT sequence 1 is not a gap — they're different streams. Every validator must track state per symbol.

### Test Strategy: 21 Tests in 10 Categories

**Area (1):** `getArea()` returns "COMPLETENESS"
**No ticks (1):** PASS with default message
**Sequential (1):** consecutive sequences → PASS, zero gaps
**Gap detection (5):** single gap, multi-message jump, cumulative gaps, per-symbol tracking, first tick never gap
**Status thresholds (2):** < 5 gaps → WARN, >= 5 gaps → FAIL
**Staleness (5):** stale detected above threshold, stale → FAIL, recovery removes from set, within threshold not stale, per-symbol staleness
**Idempotent (2):** duplicate seq skipped, older seq skipped
**Reset (1):** clears all state
**Configure (1):** custom heartbeat threshold
**Result details (1):** result map contains all expected keys
**Multi-symbol (1):** independent tracking verified

---

## Step 13: ValidatorEngine — The Orchestrator (Fan-Out + Fault Isolation)

### The Architecture: List<Validator> + Fan-Out

```java
public class ValidatorEngine {
    private final List<Validator> validators;

    public void onTick(Tick tick) {
        for (Validator v : validators) {
            try {
                v.onTick(tick);
            } catch (Exception e) {
                log.error("Validator {} threw: {}", v.getArea(), e.getMessage());
            }
        }
        notifyListeners();
    }
}
```

**Why a simple for-loop, not parallel streams?** Each validator is O(1) per tick. Parallelizing O(1) operations adds thread scheduling overhead (microseconds) that exceeds the work itself. Sequential fan-out is faster for low-cost operations.

**Key insight:** "The ValidatorEngine fans out each tick to all validators in a simple for-loop. I considered parallel streams but rejected it — when each validator's `onTick()` is O(1), the thread context-switching overhead dominates. Sequential is both simpler and faster here. If validators were doing I/O or heavy computation, that calculus would change."

### Fault Isolation — The try/catch Pattern

```java
try {
    v.onTick(tick);
} catch (Exception e) {
    log.error(...);
    // Don't let one broken validator stop others
}
```

**Why catch Exception, not Throwable?** Throwable includes `Error` (OutOfMemoryError, StackOverflowError) which should NOT be caught — they indicate unrecoverable JVM problems. `Exception` covers programmer errors and transient problems that one validator might have without affecting others.

**Key insight:** "Every validator is fault-isolated. If the LatencyValidator throws a NullPointerException, the OrderingValidator and all others still process the tick. This is critical in a monitoring system — you don't want a bug in one validator to blind you to all validation. The same pattern applies to listeners."

### Immutable Validator List — Defensive Copy at Construction

```java
public ValidatorEngine(List<Validator> validators) {
    this.validators = List.copyOf(validators);
}
```

**Why `List.copyOf()`?** Prevents external code from adding/removing validators after construction. The engine's validator set is fixed at creation time. This makes the engine thread-safe for the validators list (no concurrent modification during iteration).

**Key insight:** "The validator list is copied and made immutable at construction time using `List.copyOf()`. This means the for-loop in `onTick()` never needs synchronization — the list can't change during iteration. It's a simple but powerful thread-safety guarantee."

### Listener Pattern — Observer for Validation Updates

```java
private final List<Consumer<List<ValidationResult>>> listeners = new CopyOnWriteArrayList<>();

public void addListener(Consumer<List<ValidationResult>> listener) {
    listeners.add(listener);
}
```

**Who listens?** The SSE controller (to push results to browser), the session recorder (to log validation state), and potentially an alerting system.

**Why CopyOnWriteArrayList again?** Same reasoning as FeedConnection — listeners are added rarely (at startup) but iterated frequently (every tick). COWAL optimizes for this read-heavy pattern.

**Listener fault isolation:** Same try/catch as validators. A broken listener doesn't stop other listeners or the validation pipeline.

### Aggregated Results — Two Access Patterns

```java
public List<ValidationResult> getResults() { ... }              // Ordered list
public Map<String, ValidationResult> getResultsByArea() { ... } // Quick lookup
```

**Why both?** The list is for the SSE stream (ordered display). The map is for controllers that need to check a specific validator's result (e.g., "what's the current latency status?").

### hasFailures() — Quick Health Check

```java
public boolean hasFailures() {
    return validators.stream()
            .anyMatch(v -> v.getResult().getStatus() == ValidationResult.Status.FAIL);
}
```

Short-circuits on first FAIL — doesn't evaluate all validators if one already failed. This is used by health endpoints and alerting.

### Test Strategy: 18 Tests in 9 Categories

**Setup (2):** validator count matches injected list, tick count starts at zero
**Fan-out (2):** single tick reaches all validators, multiple ticks counted  
**Results (2):** `getResults()` returns all 4 areas, `getResultsByArea()` returns map
**Valid data (1):** valid ticks → all PASS, hasFailures false
**Failure detection (1):** invalid prices → accuracy FAIL, hasFailures true
**Exception isolation (2):** broken validator doesn't stop others, broken listener doesn't stop others
**Reset (1):** clears tick count and all validator state
**Configure (2):** propagates to all validators, targets by area name
**Listeners (4):** notified on every tick, receives correct results, removal works, broken listener isolated
**Edge case (1):** empty engine (no validators) works correctly

---

## Step 14: ReconnectionValidator — Event-Driven Validation (Not Tick-Driven)

### The Key Difference: Event-Driven vs Tick-Driven

Every other validator processes ticks via `onTick()`. ReconnectionValidator is fundamentally different — it processes **connection lifecycle events**:

```java
@Override
public void onTick(Tick tick) {
    // No-op: reconnection validation is event-driven, not tick-driven
}

public void onDisconnect(String connectionId) { ... }
public void onReconnect(String connectionId, Duration reconnectTime) { ... }
public void onReconnectFailed(String connectionId) { ... }
public void onSubscriptionRestored(String connectionId) { ... }
```

**Why does it still implement Validator?** Because the ValidatorEngine needs a uniform interface — it calls `onTick()` on all validators and `getResult()` to aggregate status. The ReconnectionValidator participates in this aggregation even though its data source is different.

**Key insight:** "ReconnectionValidator demonstrates the Liskov Substitution Principle compromise. It implements the Validator interface so the engine can aggregate it uniformly, but `onTick()` is a no-op. The real input comes from FeedConnection lifecycle callbacks. In a purist OOP design, you might split Validator into `TickValidator` and `EventValidator` interfaces, but the practical benefit of a single aggregation loop outweighs the interface purity cost."

### Four Connection Lifecycle Events

| Event | When Called | What It Means |
|---|---|---|
| `onDisconnect` | WebSocket drops | A feed connection was lost — disconnectCount++ |
| `onReconnect` | WebSocket re-establishes | Successful recovery — records how long it took |
| `onReconnectFailed` | Max retries exhausted | Permanent failure — this is the worst case |
| `onSubscriptionRestored` | Re-subscribed after reconnect | Data flow should resume — confirms full recovery |

### Three-Tier Status Logic

```
FAIL: failedReconnects > 0
  → At least one connection could NOT recover. Dashboard should alert immediately.

WARN: reconnectCount < disconnectCount OR avgReconnectTime > 5s
  → Either a disconnect is still pending OR reconnects are slow.

PASS: reconnectCount == disconnectCount AND avgReconnectTime < 5s
  → Every disconnect recovered quickly. System is resilient.
```

**Key insight:** FAIL is permanent — one failed reconnect means the system has a blind spot. It stays FAIL until reset. This is intentional: you want operators to investigate, not have the status silently recover.

### CopyOnWriteArrayList for Reconnect Times

```java
private final List<Long> reconnectTimesMs = new CopyOnWriteArrayList<>();
```

**Why not a fixed-size circular buffer like LatencyValidator?** Reconnection events are rare (maybe 0-10 per session). There's no need for bounded memory — the list will never grow large enough to matter. A COWAL gives thread-safe append without locking.

**Why store milliseconds as Long, not Duration?** Simplifies the average calculation (`stream().mapToLong().average()`) and avoids Duration arithmetic complexity for a simple mean.

### Metric: Reconnect Rate

```java
double metric = disconnects == 0 ? 100.0 : 100.0 * reconnects / disconnects;
```

This gives a percentage: "what fraction of disconnects were recovered?" 100% = all recovered, 50% = half still pending.

### Test Strategy: 18 Tests in 9 Categories

**Area (1):** `getArea()` returns "RECONNECTION"
**No events (1):** PASS with default message
**No-op (1):** `onTick()` doesn't change any state
**Disconnect (2):** single disconnect, multiple disconnects counted
**Reconnect (1):** records count and duration
**Status (4):** all reconnected fast → PASS, unresolved disconnect → WARN, slow reconnect → WARN, failed → FAIL, failed trumps all
**Subscription (1):** restoration tracked
**Avg time (2):** calculated across multiple events, zero with no reconnects
**Metric (1):** reconnect rate as percentage
**Reset (1):** clears all counters and times
**Configure (1):** custom reconnect threshold
**Result details (1):** result map contains all expected keys

---

## Phase 2, Step 1: schema.sql + SqliteConfig.java — Database Foundation

### Why TEXT for Prices, Not REAL?

```sql
price TEXT NOT NULL,     -- Stored as TEXT for BigDecimal precision
bid TEXT,
ask TEXT,
volume TEXT,
```

**SQLite's REAL type is IEEE 754 double.** `45123.45` stored as REAL becomes `45123.4500000000000...7` internally. When you read it back into BigDecimal, you get precision drift.

**TEXT stores the exact decimal string.** Write `"45123.45"` → read `"45123.45"` → `new BigDecimal("45123.45")` is lossless.

**Key insight:** "All financial values are stored as TEXT in SQLite and handled as BigDecimal in Java. This gives end-to-end precision: exchange sends `"45123.45"` → Java BigDecimal → SQLite TEXT → Java BigDecimal → UI. No floating-point touchpoint anywhere in the pipeline."

### Why ISO-8601 TEXT for Timestamps?

```sql
exchange_ts TEXT NOT NULL,    -- ISO-8601 instant
received_ts TEXT NOT NULL,
```

SQLite has no native `TIMESTAMP` type. TEXT with ISO-8601 format (`2026-03-23T10:00:00.123Z`) is:
- Human-readable in raw queries
- Sortable (ISO-8601 lexicographic order = chronological order)
- Parseable by `Instant.parse()` in Java

### Schema Design Choices

| Table | Purpose | Key Design Decision |
|---|---|---|
| `connections` | Feed configs | `id TEXT` (UUID), `symbols` as JSON array string |
| `sessions` | Recording metadata | `feed_id` FK links to connection, `status` enum as TEXT |
| `ticks` | Bulk tick data | `session_id` nullable (NULL when not recording), prices as TEXT |
| `validations` | Periodic snapshots | `details` as JSON blob for extensibility |
| `alerts` | Threshold breaches | `acknowledged` as INTEGER (0/1 — SQLite has no boolean) |

### Three Performance Indexes

```sql
CREATE INDEX IF NOT EXISTS idx_ticks_session ON ticks(session_id);
CREATE INDEX IF NOT EXISTS idx_ticks_symbol ON ticks(symbol, exchange_ts);
CREATE INDEX IF NOT EXISTS idx_ticks_feed ON ticks(feed_id, exchange_ts);
```

- `idx_ticks_session`: "Get all ticks for session #5" (session replay)
- `idx_ticks_symbol`: "Get BTCUSDT ticks in time order" (analysis queries)
- `idx_ticks_feed`: "Get all ticks from Binance feed" (feed health)

### SqliteConfig — CommandLineRunner Pattern

```java
@Bean
CommandLineRunner initDatabase(JdbcTemplate jdbcTemplate) {
    return args -> { /* strip comments, split, execute */ };
}
```

**Why CommandLineRunner, not spring.sql.init.mode?** Spring's auto-init runs early — sometimes before beans are wired. CommandLineRunner runs after full context, giving guaranteed JdbcTemplate availability and explicit error handling.

**Bug we hit:** Naive `startsWith("--")` on split results skipped valid statements starting with comment lines. Fix: strip ALL comment lines first, then split on semicolons.

### In-Memory SQLite for Tests

```properties
spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared
```

**Why `cache=shared`?** Plain `:memory:` creates a separate DB per connection. With HikariCP, connection recycling loses the in-memory DB. `cache=shared` ensures all connections share the same instance — critical for tests.

**`@ActiveProfiles("test")`** on the context test activates `application-test.properties`, swapping file-based for in-memory SQLite.

---

## Phase 3, Step 1 — StreamController (SSE Streaming Endpoints)

**File:** `controller/StreamController.java` + `StreamControllerTest.java`  
**Tests:** 8 new → **313 total**

### What It Does
Four SSE (Server-Sent Events) endpoints for real-time browser streaming:

| Endpoint | Event Name | Payload |
|---|---|---|
| `GET /api/stream/ticks?symbol=` | `tick` | Price, bid, ask, volume, latency per tick |
| `GET /api/stream/validation` | `validation` | All 8 validator results + overall status |
| `GET /api/stream/latency` | `latency` | p50, p95, p99, min, max every 1s |
| `GET /api/stream/throughput` | `throughput` | msgs/sec, peak, total every 1s |

### SseEmitter vs WebFlux Flux

**SseEmitter** (Spring MVC) was chosen over **Flux** (WebFlux) because:
- The rest of the app is servlet-based (Spring MVC + JdbcTemplate)
- SseEmitter gives imperative control over when/what to send
- No reactive chain complexity — just `emitter.send(data)`
- Timeout of `0L` means the connection never expires (client can hold open forever)

```java
SseEmitter emitter = new SseEmitter(0L); // No timeout
emitter.send(SseEmitter.event().name("tick").data(payload));
```

### Emitter Lifecycle Management
Each endpoint maintains a `CopyOnWriteArrayList<SseEmitter>`:
- **Thread-safe iteration** — safe to broadcast while clients connect/disconnect
- **Three cleanup callbacks**: `onCompletion`, `onTimeout`, `onError` all remove the emitter
- **IOException during send** = dead client → remove immediately
- **No memory leaks** — every path leads to removal

### Symbol Filtering Pattern
Tick endpoint accepts optional `?symbol=BTCUSDT` filter. Implementation uses a `ConcurrentHashMap<SseEmitter, String>` to tag emitters with their filter:
- Decoupled from the emitter object itself (SseEmitter has no custom attributes)
- O(1) lookup per tick per emitter
- Cleaned up via `removeEmitter()` which clears both the list and the filter map

### Push Model: Listeners + Scheduled Tasks
Two mechanisms feed the SSE endpoints:

1. **Event-driven** (ticks + validation): Controller registers as a listener on FeedManager and ValidatorEngine. Every tick/validation result triggers an immediate broadcast.
2. **Polling** (latency + throughput): `ScheduledExecutorService` runs two tasks every 1 second. Reads current stats and broadcasts to connected emitters. Early-return if no emitters are connected (zero work when nobody's listening).

### Throughput Tracking
Uses `AtomicLong ticksInWindow` with `getAndSet(0)` for lock-free rate measurement:
- Every tick increments the counter
- Every second, the scheduler atomically reads-and-resets
- Peak tracking via simple volatile `peakPerSecond` (single-writer safe)

### Testing SseEmitter with MockMvc
Key insight: **SseEmitter starts an async response**, so MockMvc treats it differently:
```java
mvc.perform(get("/api/stream/ticks"))
   .andExpect(request().asyncStarted()); // Not asyncResult — emitter stays open
```
**Content-Type pitfall**: `text/event-stream` isn't set until the first `send()`. In tests with no actual events, `getContentType()` returns `null`. Don't assert on content-type in unit tests — verify it via curl/browser integration tests.

**Accessing the bean directly** for emitter count assertions:
```java
StreamController controller = mvc.getDispatcherServlet()
        .getWebApplicationContext()
        .getBean(StreamController.class);
assertThat(controller.getTickEmitterCount()).isGreaterThanOrEqualTo(1);
```

### Key Takeaways
- "I chose SseEmitter over Flux because the rest of the stack is servlet-based — mixing reactive would add complexity without benefit for one-directional server push"
- "CopyOnWriteArrayList for emitters is ideal here: reads (broadcasting to N clients) vastly outnumber writes (connect/disconnect)"
- "The scheduled executor uses daemon threads so it doesn't prevent JVM shutdown"
- "Throughput measurement uses AtomicLong.getAndSet(0) for a lock-free sliding window — no synchronization overhead on the hot tick path"

---

## Phase 3, Step 7 — StatusBar.jsx (Connection & Throughput Status Strip)

### What This File Does
StatusBar is a thin horizontal strip at the bottom of the dashboard that aggregates data from **three independent SSE streams** into a single at-a-glance display:  
`BTCUSDT: $45,123.45 | 12,340 msg/s | p95: 42ms`

It answers: "Is my system alive, what's flowing through it, and how fast?"

### Key Concepts

#### 1. Multiple useSSE Subscriptions in One Component
```jsx
const { latest: latestTick, connected: tickConnected } = useSSE('/api/stream/ticks', 'tick', { maxItems: 1 });
const { latest: throughput, connected: throughputConnected } = useSSE('/api/stream/throughput', 'throughput', { maxItems: 1 });
const { latest: latency, connected: latencyConnected } = useSSE('/api/stream/latency', 'latency', { maxItems: 1 });
```
- Each `useSSE` call creates its own `EventSource` → 3 concurrent SSE connections.
- `maxItems: 1` because we only need the latest value, not history.
- Each exposes its own `connected` boolean, letting us build composite connection status.

#### 2. Tri-State Connection Logic
```jsx
const allConnected = tickConnected && throughputConnected && latencyConnected;
const anyConnected = tickConnected || throughputConnected || latencyConnected;
// Green = all, Yellow = partial, Red = none
```
Real systems degrade partially — one stream can fail while others stay alive. Tri-state gives operators precise visibility: green (all healthy), yellow (degraded), red (down).

#### 3. toLocaleString() for Number Formatting
```jsx
Number(throughput.messagesPerSecond).toLocaleString() // "12,340"
num.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) // "45,123.45"
```
Locale-aware formatting gives proper thousands separators without manual regex. The `undefined` locale uses the browser's default.

#### 4. role="status" for Accessibility
The `<footer role="status">` tells screen readers this region contains live status information. Combined with the status dot's CSS, both visual and non-visual users can assess system health.

### Testing Pattern — Mocking Multiple Hook Calls
```jsx
vi.mock('../hooks/useSSE', () => ({
  default: (...args) => mockUseSSE(...args),
}));

mockUseSSE.mockImplementation((url, eventName) => {
  if (eventName === 'tick') return sseStates.tick;
  if (eventName === 'throughput') return sseStates.throughput;
  if (eventName === 'latency') return sseStates.latency;
});
```
The mock dispatches based on `eventName` parameter, letting us control each stream independently. The `setupSSE({ tick: ..., throughput: ... })` helper makes tests read like natural descriptions: "when throughput is connected and shows 12,340 msg/s..."

### Key Takeaways
- "StatusBar acts as a data aggregator — not a data source. It composes three independent SSE streams into one unified display, following the React pattern of lifting consumption without lifting state"
- "Tri-state connection status (green/yellow/red) reflects real system behavior — partial failures are common in distributed systems, and binary up/down misleads operators"
- "maxItems: 1 for all three subscriptions minimizes memory — the status bar only cares about the latest value, not history"
- "I tested the mock dispatch by event name rather than call order, which is more resilient to refactoring — reordering the useSSE calls in the component won't break tests"

---

## Phase 4, Step 1 — LatencyChart.jsx (Live Canvas Chart)

### What This File Does
LatencyChart is a **pure-canvas** real-time line chart showing p50, p95, and p99 latency percentiles over the last 5 minutes. It subscribes to `/api/stream/latency` SSE (1 event/sec) and continuously renders via `requestAnimationFrame`.

This replaces what would typically need a heavyweight charting library (Chart.js, D3) with ~170 lines of canvas drawing — keeping the bundle small per the blueprint's "no heavy libraries" rule.

### Key Concepts

#### 1. Canvas Over SVG/Library
```jsx
<canvas ref={canvasRef} width={900} height={400} />
```
Why canvas?
- **60fps rendering** — `requestAnimationFrame` loop redraws every frame with zero DOM churn.
- **Constant memory** — unlike SVG where each line segment is a DOM node, canvas is just a bitmap.
- **Small bundle** — no external dependency. Chart.js adds ~200KB gzipped; this is 0KB extra.

Trade-off: canvas isn't accessible to screen readers. For a monitoring dashboard this is acceptable — the status bar provides the same data as text.

#### 2. useRef for Mutable Data (Not useState)
```jsx
const pointsRef = useRef([]);  // { time, p50, p95, p99 }[]
```
Points are stored in a `useRef`, not `useState`. Why?
- **Performance**: Writing 1 new point/sec to state would trigger a re-render every second. With ref, the chart draws from the ref in the animation loop — no React re-renders needed.
- **Animation loop reads ref directly**: `pointsRef.current` is always current inside `drawChart`.
- Only `latest` and `connected` (from useSSE) are state, because they affect DOM elements (status text).

#### 3. Bounded Buffer with splice
```jsx
pts.push(point);
if (pts.length > MAX_POINTS) {
  pts.splice(0, pts.length - MAX_POINTS);
}
```
MAX_POINTS = 300 (5 min × 60 sec). When the buffer exceeds 300 points, the oldest entries are removed. This ensures constant memory regardless of how long the page is open — a core blueprint requirement.

#### 4. Auto-Scaling Y Axis
```jsx
let yMax = SLA_THRESHOLD_MS;  // Start at 500ms
for (const pt of pts) {
  if (pt.p99 > yMax) yMax = pt.p99;
}
yMax = Math.ceil(yMax * 1.2 / 100) * 100;  // Round up + 20% headroom
```
The Y axis auto-scales to fit the data while always showing the SLA line. If p99 spikes to 800ms, the chart adapts. This is better than a fixed scale that either wastes space or clips data.

#### 5. SLA Threshold Line
```jsx
ctx.setLineDash([6, 4]);  // Dashed line
// draw at SLA_THRESHOLD_MS (500ms)
ctx.setLineDash([]);       // Reset to solid
```
The dashed SLA line gives immediate visual feedback: lines below = good, lines above = SLA breach. This is the most important visual in a latency monitoring dashboard.

### Testing Canvas in jsdom
jsdom has no canvas implementation. The test approach:
```jsx
const mockCtx = { fillRect: vi.fn(), stroke: vi.fn(), ... };
HTMLCanvasElement.prototype.getContext = vi.fn(() => mockCtx);

// Also mock requestAnimationFrame
globalThis.requestAnimationFrame = vi.fn((cb) => { rafCallback = cb; return 1; });
```
We mock `getContext('2d')` to return a spy object, then verify drawing calls were made (e.g., `setLineDash` for SLA line, `fillText` for "Waiting for data…"). We also mock `requestAnimationFrame` to capture the callback and trigger it manually.

### Key Takeaways
- "I chose raw canvas over Chart.js to keep the bundle at 0KB additional — the blueprint explicitly says 'no heavy libraries, keep bundle small'"
- "Points are stored in a useRef, not useState — the animation loop reads the ref directly, avoiding 60 React re-renders per second. Only connection status uses state because it affects DOM"
- "The 300-point bounded buffer (5 minutes at 1/sec) guarantees O(1) memory. A user leaving the tab open for 8 hours won't cause memory growth"
- "Auto-scaling the Y axis ensures the SLA threshold line is always visible while adapting to latency spikes — this is better UX than a fixed scale"
- "Testing canvas in jsdom requires mocking getContext and requestAnimationFrame — it's a good example of testing behavior (did it draw the SLA line?) rather than pixel output"

---

## Phase 4, Step 2 — ThroughputValidator.java + ThroughputGauge.jsx

### What These Files Do

**ThroughputValidator.java** — The 7th validator (Area.THROUGHPUT). Monitors message rate per second, tracks a 60-second rolling average, detects rate drops, and flags zero-throughput stalls. Uses a **circular buffer** (`long[]`) for the rolling window — not a List, not a queue — just a fixed array with a head pointer.

**ThroughputGauge.jsx** — Frontend gauge that displays current msg/s, peak, total, and a CSS-based sparkline bar chart showing the last 60 seconds of history. Reads from two SSE streams: throughput data + validation results for the THROUGHPUT area.

### Key Concepts

#### 1. Timer-Driven vs. Tick-Driven Validation
```java
// Called for every tick — just increment a counter (O(1))
public void onTick(Tick tick) {
    currentSecondCount.incrementAndGet();
}

// Called every 1 second by external timer — snapshot + analyze
public void tick() {
    long rate = currentSecondCount.getAndSet(0);
    secondCounts[head] = rate;
    head = (head + 1) % windowSize;
    ...
}
```
Unlike AccuracyValidator/OrderingValidator which compute their result on every tick, ThroughputValidator uses a **two-phase approach**: `onTick()` is O(1) (just an atomic increment), and `tick()` is called externally once per second to snapshot the count and compute statistics. This is the correct pattern because throughput is inherently time-based — you can't measure "messages per second" without knowing when the second boundary is.

#### 2. Circular Buffer with Primitive Array
```java
private final long[] secondCounts;
private int head = 0;
private int filled = 0;

secondCounts[head] = rate;
head = (head + 1) % windowSize;
if (filled < windowSize) filled++;
```
A `long[60]` uses 480 bytes total. Compare to `ArrayList<Long>` which would use ~960 bytes (boxed longs + object headers + array overhead). For 10K+ msg/sec, every byte and allocation matters.

#### 3. Drop Detection with Warmup Guard
```java
if (filled >= 5 && rollingAverage > 0) {
    dropDetected = rate < rollingAverage * dropPercent;
}
```
Drop detection only activates after 5 seconds of data. Without this warmup guard, the first second would always show a "drop" (current = 0, previous average = some value). The 50% threshold is configurable.

#### 4. Zero-Throughput FAIL with Connection Awareness
```java
if (rate == 0 && feedConnected) {
    consecutiveZeroSeconds++;
    if (consecutiveZeroSeconds >= zeroThresholdSecs) {
        zeroThroughputFail = true;
    }
}
```
Zero throughput is only a FAIL if we know the feed is connected. If the feed is disconnected, zero messages is expected. This prevents false alarms during intentional disconnects.

#### 5. CSS Sparkline (No Canvas Needed)
```jsx
{sparkData.map((val, i) => (
  <div className="tg-spark-bar"
       style={{ height: `${(val / sparkMax) * 100}%` }} />
))}
```
The sparkline uses plain CSS div bars with dynamic height. No canvas, no SVG, no library. For 60 bars (1 per second), DOM performance is fine. If it were 10,000 bars, canvas would be needed (like LatencyChart).

### Testing Patterns

**Backend (27 tests):** The `ThroughputValidator(int windowSize)` package-private constructor allows tests to use a small 10-slot window instead of 60. Tests cover: idempotency (duplicate seqNums skipped), tick counting, circular buffer wrapping, drop detection after warmup, zero-throughput fail with connection awareness, reset, and configure.

**Frontend (17 tests):** Mock dispatches by SSE event type. Tests verify: rate display, peak/total stats, sparkline bar rendering with proportional heights, drop alert conditional display, PASS/WARN/UNKNOWN badge states, accessibility labels.

### Key Takeaways
- "ThroughputValidator uses a two-phase design: O(1) atomic increment on every tick, then a timer-driven tick() method once per second for analysis. This separates the hot path (counting) from the cold path (statistics)"
- "The circular buffer is a primitive long[] — not ArrayList<Long>. Boxing 10,000 longs/sec would create enormous GC pressure. The entire 60-second window fits in 480 bytes"
- "Drop detection has a warmup guard (5 seconds) to prevent false positives during startup. Zero-throughput FAIL only fires when feedConnected=true to avoid false alarms during intentional disconnects"
- "The sparkline uses CSS div bars because 60 elements is fine for DOM rendering. The LatencyChart uses canvas for 300 points at 60fps — different tools for different scales"

---

## Phase 4, Step 3 — SubscriptionValidator.java (Subscribe/Unsubscribe Tracking)

### What This File Does
The 7th validator (Area.SUBSCRIPTION). Verifies that subscribe/unsubscribe operations work correctly by tracking:
1. **Subscribe latency** — Time from subscribe event to first tick received (< 5s expected)
2. **Leaky unsubscribes** — Ticks still arriving after unsubscribe + grace period
3. **Active symbol monitoring** — Are all subscribed symbols receiving data?
4. **Subscribe timeout detection** — Subscribed but no tick within 5 seconds

This is a **hybrid validator** — it has both event-driven methods (`onSubscribe`, `onUnsubscribe`) AND tick-driven logic (`onTick` tracks which symbols are actively receiving data).

### Key Concepts

#### 1. Hybrid Validator Pattern (Event + Tick Driven)
```java
// Event-driven: called by FeedManager when subscription changes
public void onSubscribe(String symbol) { ... }
public void onUnsubscribe(String symbol) { ... }

// Tick-driven: called for every tick to track active symbols
public void onTick(Tick tick) { ... }
```
Unlike ReconnectionValidator (pure event-driven, onTick is no-op) or AccuracyValidator (pure tick-driven), SubscriptionValidator needs both:
- **Events** tell us what *should* be happening (which symbols are subscribed)
- **Ticks** tell us what *is* happening (which symbols are actually sending data)

Comparing the two reveals problems: subscribed but inactive = WARN, unsubscribed but still receiving = FAIL.

#### 2. Grace Period for Unsubscribe
```java
if (msSinceUnsub > unsubscribeGraceMs) {
    leakyUnsubscribes.add(symbol);
}
// Within grace period, ticks are expected (in-flight messages)
```
When you unsubscribe from a WebSocket feed, there may be in-flight messages already sent by the server. A 3-second grace period allows these to arrive without flagging a false alarm. Only ticks arriving *after* the grace period indicate a real leak — the server didn't actually stop sending.

#### 3. Subscribe Latency (First-Tick Measurement)
```java
if (subTime != null && !subscribeLatencyMs.containsKey(symbol)) {
    long latency = Duration.between(subTime, now).toMillis();
    subscribeLatencyMs.put(symbol, latency);
}
```
The `!containsKey` check ensures latency is recorded only on the **first** tick after subscribe, not on every subsequent tick. This gives us the true time-to-first-data metric.

#### 4. Time-Based Active Detection (Not Just "Ever Received")
```java
Instant lastTick = lastTickTimeBySymbol.get(symbol);
if (lastTick != null && Duration.between(lastTick, now).toMillis() < activeThresholdMs) {
    active++;
}
```
A symbol isn't "active" just because it received a tick once. It must have received a tick within the `activeThreshold` (default 30s). This catches feeds that started fine but silently stopped.

### Testing Pattern — Time-Sensitive Tests
```java
@BeforeEach
void setUp() {
    validator.configure(Map.of(
        "subscribeTimeoutMs", 100L,    // 100ms vs 5s default
        "unsubscribeGraceMs", 50L,     // 50ms vs 3s default
        "activeThresholdMs", 500L      // 500ms vs 30s default
    ));
}

@Test
void detectsLeakyUnsubscribe() throws InterruptedException {
    validator.onUnsubscribe("BTCUSDT");
    Thread.sleep(80);  // Past 50ms grace
    feedTick("BTCUSDT", 2);  // Leak!
    assertThat(validator.getLeakyUnsubscribes()).contains("BTCUSDT");
}
```
Tests use `Thread.sleep()` with short configured thresholds. This is acceptable for unit tests (80ms sleep is not slow), and avoids the complexity of a fake clock. The key insight: **make thresholds configurable via `configure()`, then set short values in tests**.

### Key Takeaways
- "SubscriptionValidator is a hybrid — it combines event-driven input (subscribe/unsubscribe) with tick-driven analysis (active symbol tracking). This lets it answer 'is what's happening matching what should be happening?'"
- "The unsubscribe grace period (3s default) prevents false positives from in-flight messages. In WebSocket protocols, the server may have already dispatched messages before receiving the unsubscribe"
- "Subscribe latency measures time-to-first-data — a critical SLA metric for trading systems. If you subscribe to BTCUSDT and don't see data for 5 seconds, something is wrong"
- "Active detection is time-based, not ever-received. A feed that sent one tick 10 minutes ago and nothing since is effectively dead — the 30-second threshold catches silent failures"

---

## Phase 4, Step 4 — StatefulValidator.java (The 8th Validator — Critical for FinTech)

### What This File Does
The final and most complex validator (Area.STATEFUL). It reconstructs **per-symbol financial state** from a stream of individual ticks and validates the consistency of that state on every update.

This is the validator that separates a "message checker" from a "trading system validator." Individual tick-level checks (price > 0, bid ≤ ask) are necessary but insufficient. Real trading bugs happen at the **state level**: a VWAP that drifts outside the price range, cumulative volume that decreases, or OHLC values that become inconsistent.

### Per-Symbol State: SymbolState (Inner Class)
```java
static class SymbolState {
    BigDecimal lastPrice;
    BigDecimal open;            // First price in session — never changes
    BigDecimal high;            // Max price seen — only increases
    BigDecimal low;             // Min price seen — only decreases
    BigDecimal cumulativeVolume; // Sum of all volumes — non-decreasing
    BigDecimal vwap;            // Σ(price × volume) / Σ(volume)
    BigDecimal priceVolumeSum;  // Running numerator for VWAP
    BigDecimal volumeSum;       // Running denominator for VWAP
    Instant lastTickTime;       // For stale detection
    long tickCount;
}
```
Each symbol gets its own isolated state. `ConcurrentHashMap.computeIfAbsent` creates state lazily on first tick.

---

## Phase 4, Step 5 — SessionReplayer.java (32 tests)

### What It Does
Replays a recorded session's ticks through the ValidatorEngine at configurable speed. This is the **inverse of SessionRecorder** — one saves the stream, the other plays it back. Key for reproducible debugging: validate the same data repeatedly with different validator configs.

### Architecture Pattern: Async State Machine with Thread Coordination

**State machine**: `IDLE → REPLAYING → COMPLETED` (happy path), with `PAUSED` as a holdable intermediate state and `FAILED` for interrupted threads.

```
IDLE ──start()──→ REPLAYING ──all ticks──→ COMPLETED
                    │   ↑                     │
                pause() resume()          start() (can restart)
                    ↓   │
                  PAUSED
                    │
                 stop()──→ IDLE
```

**Thread model**: `start()` sets state synchronously (caller thread), then spawns a daemon `Thread`. The replay loop runs on that thread. This means `getState()` returns `REPLAYING` *immediately* after `start()` returns — no race between caller observing state and thread starting.

### Key Design Decisions

**1. `volatile` + `Object` monitor — not `ReentrantLock`:**
```java
private volatile State state = State.IDLE;      // cross-thread visibility
private final Object pauseLock = new Object();  // pause/resume coordination
```
`volatile` gives happens-before for state reads. The `pauseLock` monitor handles pause waiting — the replay thread does `pauseLock.wait()` in a loop, and `resume()` does `pauseLock.notifyAll()`. This is textbook Java concurrency: volatile for flags, monitors for condition waiting.

**2. Inter-tick timing with speed scaling:**
```java
long gapMs = Duration.between(prevTs, tick.getExchangeTimestamp()).toMillis();
long sleepMs = (long) (gapMs / speedFactor);
if (sleepMs > 0) Thread.sleep(sleepMs);
```
At 1x: 100ms gap → sleep 100ms. At 10x: 100ms gap → sleep 10ms. At very high speed factors, small gaps truncate to 0 and are skipped entirely — replay becomes essentially instant.

**3. Cancellation via state check, not `Thread.interrupt()`:**
The loop checks `if (state == State.IDLE) break;` before and after the pause wait. `stop()` sets state to IDLE *then* interrupts the thread. The interrupt unblocks `Thread.sleep()` and `pauseLock.wait()`, but the *state check* is what actually terminates the loop. This avoids relying solely on InterruptedException for control flow.

**4. `engine.reset()` on each start:**
Every replay clears all validator state first. This ensures validators operate on a clean slate — previous live data or prior replays don't contaminate results.

**5. Daemon thread with progress callbacks:**
```java
private volatile Consumer<ReplayProgress> progressListener;
```
Progress fires after every tick AND on completion. The listener is stored as a volatile reference so it can be set/cleared from any thread. The `ReplayProgress` record includes current state, ticks replayed, total ticks, and speed factor — everything a UI needs for a progress bar.

### Testing Concurrency Without Flakiness

**Problem**: Async tests are inherently timing-sensitive. The classic trap: assert a count at an exact value when a background thread may have processed one more tick.

**Solution — polling waits, not exact timing assertions:**
```java
private void waitForState(State expected, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (replayer.getState() != expected && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
    }
}
```

**Zero-gap ticks for non-timing tests**: Ticks 1ms apart at 10x speed have 0ms sleep — replay completes instantly:
```java
private List<Tick> zeroGapTicks(int count) {
    // 1ms apart → at 10x speed, (long)(1/10.0) = 0 → no sleep
}
```

**CountDownLatch for synchronization**: When testing that `stop()` halts mid-replay, use a latch on `engine.onTick()` to know when at least one tick has been processed before calling stop.

**Range assertions for pause**: After `pause()`, the exact tick count is non-deterministic (±1 due to thread scheduling). Assert `ticksAtPause >= expected && ticksAtPause < total` rather than exact equality.

### Key Takeaways

1. **"Why not use `ScheduledExecutorService` instead of `Thread.sleep` in a loop?"** — ScheduledExecutorService is better for periodic tasks at fixed rates. Here, sleep durations vary per tick (each inter-tick gap is different). A raw thread with computed sleeps is simpler and more accurate for variable-delay replay.

2. **"Why `volatile` and not `AtomicReference<State>`?"** — Either works. `volatile` is sufficient here because only one thread writes state transitions at a time (caller thread for pause/stop, replay thread for COMPLETED/FAILED). No CAS needed.

3. **"How does pause work without losing a tick?"** — The pause check is at the *top* of the loop, before `engine.onTick()`. When paused, the thread blocks on `pauseLock.wait()`. When resumed, it continues with the *same tick* it was about to process. No tick is skipped or double-processed.

4. **"Why daemon thread?"** — If the JVM shuts down, we don't want a lingering replay thread preventing exit. Daemon threads are automatically killed on JVM shutdown.

### Key Concepts

#### 1. VWAP (Volume-Weighted Average Price)
```java
state.priceVolumeSum = state.priceVolumeSum.add(price.multiply(volume));
state.volumeSum = state.volumeSum.add(volume);
state.vwap = state.priceVolumeSum.divide(state.volumeSum, MathContext.DECIMAL64);
```
VWAP = Σ(price × volume) / Σ(volume). This gives the average price weighted by how much was traded at each price. It's the most important derived metric in trading — institutional traders use VWAP benchmarks to evaluate execution quality. A VWAP outside the [low, high] range means the state is mathematically impossible.

We store the running `priceVolumeSum` and `volumeSum` separately rather than recalculating from scratch — this makes VWAP update O(1) per tick.

#### 2. OHLC Invariants (Open-High-Low-Close)
```java
// Rule 2: low ≤ open ≤ high
// Rule 3: low ≤ lastPrice ≤ high
```
These invariants seem obvious but are critical validation targets. If events arrive out of order, or a bug in state reconstruction skips a tick, these invariants can break. For example: if we miss a tick with a new high, subsequent ticks may show `lastPrice > high` — an impossible state.

#### 3. Cumulative Volume Monotonicity
```java
if (state.cumulativeVolume.compareTo(prevCumulativeVolume) < 0) {
    violated.add("VOLUME_DECREASED");
}
```
Cumulative volume should **never decrease** within a session. It's the running total of all traded volume. A decrease means either we lost a tick, applied a negative volume, or have a state reconstruction bug. This is a critical invariant for position tracking systems.

#### 4. Stale Data Detection (Built Into This Validator)
```java
if (Duration.between(s.lastTickTime, now).toMillis() > staleThresholdMs) {
    stale.add(entry.getKey());
}
```
Stale detection lives here because it's a **state** problem: the symbol's state hasn't been updated in 30+ seconds. This is different from CompletenessValidator's sequence gap detection — a symbol can have no gaps but still go stale if the feed stops sending data entirely.

#### 5. Bounded Violation Buffer
```java
while (violations.size() > maxViolations) {
    violations.remove(0);
}
```
Violations are bounded to the last 100 (configurable). In a long-running session, violations could accumulate forever. We keep only the most recent ones, and the `getResult()` method returns the last 5 in the details map for display.

#### 6. Apply-Then-Validate Pattern
```java
private List<String> applyAndValidate(SymbolState state, Tick tick) {
    // 1. Update state (OHLC, volume, VWAP)
    // 2. Check all invariants
    // 3. Return list of violated rules
}
```
We apply the tick to state FIRST, then validate. This means we're checking "is the state still consistent after this update?" — which is the correct approach. Checking before applying would miss the very tick that caused the inconsistency.

### Testing Strategy (33 Tests)
- **OHLC tracking**: first tick initializes, high only increases, low only decreases, open never changes
- **VWAP math**: known-value verification (100×2 + 200×3 = 160 VWAP), zero-volume ignored
- **Idempotency**: duplicate/older seqNums skipped, per-symbol isolation
- **Rule violations**: zero price, negative price detected with correct rule names
- **Consistency rate**: correct calculation, threshold-based status determination
- **Stale detection**: short threshold + Thread.sleep, fresh tick clears stale, 3+ stale = FAIL
- **Bounded buffer**: configured to 5, feed 10 violations, buffer stays at 5
- **Reset**: clears everything, allows same seqNum to process again

### Key Takeaways
- "StatefulValidator is the validator I'm most proud of. It validates **derived financial state** — VWAP, OHLC, cumulative volume — not just individual messages. This catches bugs that per-tick validation misses"
- "VWAP uses running sums (priceVolumeSum / volumeSum) rather than recalculating from history. This gives O(1) updates per tick with constant memory — critical for 10K+ msg/sec"
- "The OHLC invariants (low ≤ open ≤ high, low ≤ lastPrice ≤ high) seem trivial but they catch real bugs: out-of-order events, missed ticks in state reconstruction, or feed adapter parse errors"
- "Cumulative volume monotonicity is a critical invariant for position tracking. If volume ever decreases, it means we lost data or applied a corruption — this must be caught immediately"
- "Stale detection lives in StatefulValidator rather than CompletenessValidator because staleness is a state problem: the symbol's derived state is aging out. Gaps are a message-level problem"
- "All prices use BigDecimal — never double/float. In financial systems, floating-point rounding errors in VWAP calculation can compound over millions of ticks and produce materially wrong results"

---

## Phase 3, Step 2 — Frontend Scaffold

**Files:** `package.json`, `vite.config.js`, `index.html`, `main.jsx`, `App.jsx`, `App.css`, `test-setup.js`

### What We Built
The complete React + Vite project scaffold — 7 files that give us:
- A working dev server on **port 5174**
- API proxy to backend on **port 8082**
- Dark-themed dashboard shell with sidebar navigation and tab switching
- Vitest test runner configured with jsdom + Testing Library
- Placeholder panels for every component that will be built next

### Vite Proxy Configuration
```js
server: {
  port: 5174,
  proxy: {
    '/api': {
      target: 'http://localhost:8082',
      changeOrigin: true,
    },
  },
},
```
**Why a proxy?** In development, the browser loads the UI from `localhost:5174` (Vite dev server). API calls go to `/api/...`, which Vite intercepts and forwards to `localhost:8082` (Spring Boot). This avoids CORS entirely during development — the browser thinks all requests go to the same origin. In production, the built frontend is served from Spring Boot's `static/` folder, so the proxy isn't needed.

### Vitest Configuration
```js
test: {
  globals: true,        // describe/it/expect available without imports
  environment: 'jsdom', // Simulates a browser DOM for component tests
  setupFiles: './src/test/test-setup.js',
},
```
- **`globals: true`** — Vitest injects `describe`, `it`, `expect` globally, matching Jest conventions
- **`jsdom`** — A headless browser environment; components can render into a fake DOM
- **`setupFiles`** — Runs before every test file. We import `@testing-library/jest-dom` here so custom matchers like `toBeInTheDocument()` are available everywhere

### App Shell Architecture
The layout follows a classic dashboard pattern:
```
┌── Header ─────────────────────── (48px fixed) ──┐
├── Sidebar ──┬── Content ─────────────────────────┤
│  5 tab btns │  Active panel (tab-switched)       │
│  (180px)    │  (flex: 1, scrollable)             │
├─────────────┴── Status Bar ── (32px fixed) ──────┤
```
- **No React Router** — Tab switching via `useState` is simpler for a single-page dashboard. No URL routing needed.
- **Placeholder panels** — Each tab renders a `<PlaceholderPanel name="..." />` that will be replaced with real components in Steps 4-7.

### CSS Variables for Theming
Dark theme using CSS custom properties:
```css
:root {
  --bg-primary: #0f1117;    /* Deep dark background */
  --green: #34d399;          /* PASS status */
  --yellow: #fbbf24;         /* WARN status */
  --red: #f87171;            /* FAIL status */
  --accent: #4a9eff;         /* Active tab, header */
}
```
**Why CSS variables over a CSS-in-JS library?** Zero runtime cost, no extra dependency, and the validation status colors (green/yellow/red) map directly to PASS/WARN/FAIL — consistent with the backend's `ValidationResult.Status` enum.

### No Heavy Dependencies
The blueprint explicitly says "no heavy chart libraries." Our `package.json` has only:
- **Runtime**: `react`, `react-dom` (2 deps total)
- **Dev**: `vite`, `@vitejs/plugin-react`, `vitest`, `@testing-library/react`, `@testing-library/jest-dom`, `jsdom`

The latency chart (Phase 4) will use HTML5 Canvas directly — no Chart.js, no Recharts, no D3.

### `"type": "module"` in package.json
This tells Node.js to treat `.js` files as ES modules (import/export syntax). Without it, Vite's config file would need to use CommonJS (`require`). Modern React projects should always use this.

### Key Takeaways
- "I chose Vite over Create React App because CRA is deprecated and Vite gives sub-second hot reload via native ES modules"
- "The proxy pattern eliminates CORS during development — the browser sees a single origin, and Vite forwards /api/* to Spring Boot"
- "I used CSS custom properties instead of Tailwind or styled-components to keep the bundle minimal — this is a data-intensive dashboard where runtime overhead matters"
- "Tab-based navigation with useState is the right level of complexity — no React Router needed for a single-page dashboard"

---

## Phase 3, Step 3 — useSSE.js Hook

**File:** `hooks/useSSE.js` + `test/useSSE.test.js`  
**Tests:** 18 frontend tests (first vitest run)

### What It Does
A custom React hook that subscribes to Server-Sent Events from the backend and returns live-updating state. Every SSE-consuming component uses this single hook.

```javascript
const { data, latest, error, connected, clear } = useSSE(
  '/api/stream/ticks',  // URL
  'tick',               // SSE event name to listen for
  { maxItems: 500 }     // bounded buffer size
);
```

### Return Values

| Field | Type | Purpose |
|---|---|---|
| `data` | `any[]` | Bounded array of all received events (last N) |
| `latest` | `any` | Most recent event (convenience — avoids `data[data.length-1]`) |
| `error` | `Error\|null` | Current error state with reconnect countdown |
| `connected` | `boolean` | `true` when EventSource is open |
| `clear` | `Function` | Resets `data` and `latest` (for pause/resume) |

### EventSource vs Fetch for SSE
`EventSource` is the browser's native SSE client. Advantages over fetching:
- **Automatic reconnection** built into the spec (though we override with custom backoff)
- **Event name routing** — `source.addEventListener('tick', ...)` only fires for `event: tick` lines
- **Streaming** — data arrives incrementally, no buffering the whole response

### Named Events Are Key
The backend sends events like:
```
event: tick
data: {"symbol":"BTCUSDT","price":"45000"}
```
`EventSource` routes these to specifically named listeners. A generic `onmessage` handler **would not receive named events** — you must use `addEventListener(eventName, ...)`. This is why the hook takes `eventName` as a required parameter.

### Bounded Buffer Pattern
```javascript
setData((prev) => {
  const next = [...prev, parsed];
  return next.length > maxItems ? next.slice(next.length - maxItems) : next;
});
```
This keeps at most `maxItems` (default 500) events in memory. Without a bound, a high-throughput feed (12K ticks/sec) would eat GBs of memory in minutes. The `slice()` trims from the front, keeping the most recent data.

### Exponential Backoff Reconnection
```javascript
const delay = Math.min(Math.pow(2, retryCount) * 1000, 30000);
// Attempt 0: 1s, 1: 2s, 2: 4s, 3: 8s, 4: 16s, 5+: 30s cap
```
- **Retry count resets on successful open** — not on connect attempt, but on actual `open` event
- **Cap at 30s** — prevents absurd wait times if the server is down for extended periods
- **Error message includes countdown** — UI can show "Reconnecting in 4s…"

### useRef for Non-Rendering State
```javascript
const eventSourceRef = useRef(null);
const retryCountRef = useRef(0);
const retryTimerRef = useRef(null);
const mountedRef = useRef(true);
```
These values change frequently but should **never trigger re-renders**:
- `retryCount` changes on every disconnect — re-rendering would be wasteful
- `mountedRef` prevents state updates after unmount (React warning avoidance)
- `eventSourceRef` holds the connection object for cleanup

### Testing EventSource in jsdom
jsdom doesn't have `EventSource`, so we built a `MockEventSource` class:
```javascript
class MockEventSource {
  static instances = [];  // Track all created instances
  _listeners = {};        // Event listeners

  _open()  { ... }        // Simulate open
  _sendEvent(name, json)  // Simulate server push
  _error() { ... }        // Simulate disconnect
}

vi.stubGlobal('EventSource', MockEventSource);
```
**`vi.stubGlobal`** replaces the global `EventSource` with our mock. Combined with `vi.useFakeTimers()`, we can:
1. Create the hook (it calls `new EventSource(url)`)
2. Simulate open/events/errors via the mock
3. Advance timers to test backoff delays
4. Assert state via `renderHook` result

### Key Test: Reconnect URL Change
When the URL prop changes (e.g., adding `?symbol=BTCUSDT`), the hook should:
1. Close the old EventSource
2. Open a new one with the updated URL
3. Reset all state

This is handled by the `useEffect` dependency array: `[url, eventName, enabled, maxItems]`. React re-runs the effect (including cleanup) whenever these change.

### Key Takeaways
- "useSSE abstracts all SSE complexity behind a single hook — components just destructure `{ data, connected }` and render"
- "The bounded buffer prevents memory leaks in high-throughput scenarios — 500 items max by default, configurable per component"
- "I use useRef for the EventSource and retry count because they shouldn't trigger re-renders — only the parsed data and connection status need to be reactive"
- "Named SSE events are critical — without `addEventListener('tick', ...)`, the hook would silently miss all server events that use named event types"
- "The exponential backoff mirrors what we built on the backend FeedConnection — both cap at 30 seconds, creating a consistent reconnection pattern across the full stack"

---

## Phase 3, Step 4 — LiveTickFeed.jsx

**File:** `components/LiveTickFeed.jsx` + `test/LiveTickFeed.test.jsx`  
**Tests:** 16 new → **34 frontend tests total**

### What It Does
Auto-scrolling real-time tick table — the primary data visualization. Columns: Time, Symbol, Price, Volume, Sequence, Latency. Connected to `useSSE('/api/stream/ticks', 'tick')`.

### Component Architecture
```
LiveTickFeed
├── feed-toolbar
│   ├── feed-status (dot + connected/disconnected + error + tick count)
│   └── feed-controls (symbol filter input + pause/resume/clear/export buttons)
└── tick-table-wrapper (scrollable)
    └── table (sticky header + tbody rows + scroll anchor div)
```

### Auto-Scroll Pattern
```javascript
const tableEndRef = useRef(null);

useEffect(() => {
  if (!paused && tableEndRef.current && tableEndRef.current.scrollIntoView) {
    tableEndRef.current.scrollIntoView({ behavior: 'smooth' });
  }
}, [data.length, paused]);
```
An invisible `<div ref={tableEndRef} />` sits below the table. When new data arrives (tracked via `data.length` dependency), `scrollIntoView` scrolls it into view — which means the table scrolls to the bottom. The pause button prevents this, letting the user inspect earlier rows.

**jsdom gotcha:** `scrollIntoView` doesn't exist in jsdom (vitest's test environment). The guard `&& tableEndRef.current.scrollIntoView` prevents `TypeError` during testing. This is a common pattern for DOM APIs missing in test environments.

### Symbol Filter → URL Change → useSSE Reconnect
```javascript
const url = symbolFilter
  ? `/api/stream/ticks?symbol=${encodeURIComponent(symbolFilter)}`
  : '/api/stream/ticks';
```
When the user types in the filter input, the URL changes, which triggers `useSSE`'s `useEffect` dependency array to re-run, closing the old EventSource and opening a new one with the filter applied. The backend's `StreamController` uses a `ConcurrentHashMap<SseEmitter, String>` to track which emitters have symbol filters.

### Latency Color-Coding
```javascript
function latencyClass(ms) {
  if (ms <= 50) return 'latency-good';   // Green
  if (ms <= 200) return 'latency-warn';  // Yellow
  return 'latency-bad';                   // Red
}
```
These thresholds match the backend's LatencyValidator logic: < 50ms is healthy, 50-200ms is marginal, > 200ms is problematic. The CSS classes map to `--green`, `--yellow`, `--red` CSS variables.

### CSV Export Pattern
```javascript
const blob = new Blob([header + rows], { type: 'text/csv' });
const a = document.createElement('a');
a.href = URL.createObjectURL(blob);
a.download = `ticks-${Date.now()}.csv`;
a.click();
URL.revokeObjectURL(a.href);
```
This is a "client-side download" pattern — no server round-trip. We:
1. Build CSV string from the current data array
2. Wrap it in a Blob
3. Create a temporary `<a>` element with Object URL
4. Programmatically click it (triggers download)
5. Revoke the Object URL (prevents memory leak)

### Price Formatting
```javascript
num.toLocaleString(undefined, {
  minimumFractionDigits: 2,
  maximumFractionDigits: 8,
});
```
`minimumFractionDigits: 2` ensures "$45,000.00" (not "$45,000"), and `maximumFractionDigits: 8` handles crypto prices like "0.00002345" without rounding loss.

### Testing Strategy: Mocking the Hook
Instead of mocking EventSource (which is useSSE's concern), we mock the entire `useSSE` hook:
```javascript
const mockUseSSE = vi.fn();
vi.mock('../hooks/useSSE', () => ({
  default: (...args) => mockUseSSE(...args),
}));
```
This is the **layered testing** approach:
- `useSSE.test.js` tests EventSource handling (unit)
- `LiveTickFeed.test.jsx` tests rendering + interactions with mocked hook (integration)
- Each layer trusts the layer below it

### File Extension Gotcha
Test files that contain JSX **must use `.jsx` extension** (not `.js`) in Vitest 4+ with rolldown. The parser doesn't transform JSX in `.js` files by default. This caused `Unexpected JSX expression` parse errors until renamed.

### Key Takeaways
- "LiveTickFeed is a pure rendering component — all SSE complexity is abstracted by useSSE, so the component just destructures `{ data, connected, error, clear }` and renders"
- "The auto-scroll uses a sentinel div at the bottom with scrollIntoView — pausing sets a boolean that short-circuits the useEffect"
- "Symbol filtering works by changing the URL prop to useSSE, which triggers a full reconnect to a new SSE stream with server-side filtering"
- "I test at two layers: hook-level (mock EventSource) and component-level (mock hook) — each layer trusts the one below"

---

## Phase 3, Step 5 — ConnectionManager.jsx

**File:** `components/ConnectionManager.jsx` + `test/ConnectionManager.test.jsx`  
**Tests:** 18 new → **52 frontend tests total** (hit the 50+ target!)

### What It Does
CRUD UI for WebSocket feed connections. Lists all feeds with status indicators (🟢🟡🔴), Start/Stop/Delete buttons, and an "Add Feed" dialog for creating new connections. All operations go through `FeedController` REST API.

### Component Decomposition
```
ConnectionManager (parent — state owner)
├── ConnectionCard × N  (display + actions per feed)
└── AddFeedDialog       (modal form for POST /api/feeds)
```
**Single file with 3 components** — keeps them co-located since they share no logic with other parts of the app. If they grew complex, each could be extracted to its own file.

### Polling vs SSE for Connection State
The component **polls** `GET /api/feeds` every 3 seconds instead of using SSE because:
- Connection state changes rarely (start/stop/reconnect)
- No SSE endpoint exists for connection state changes
- 3-second polling is fine for a human-facing status display
- Simpler than adding a 5th SSE endpoint to StreamController

### Fetch Pattern for REST CRUD
```javascript
const handleStart = async (id) => {
  const res = await fetch(`/api/feeds/${id}/start`, { method: 'POST' });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `Start failed: ${res.status}`);
  }
  await fetchConnections(); // Refresh list after action
};
```
Key patterns:
- **Optimistic refresh** — after every mutation (start/stop/delete/add), re-fetch the full list
- **Error extraction** — `res.json().catch(() => ({}))` handles responses that aren't JSON (like 500 with plain text)
- **Vite proxy** — all `/api/feeds/*` requests are proxied to `localhost:8082` in development

### Delete Confirmation
```javascript
if (!window.confirm(`Delete connection "${name}"?`)) return;
```
Uses the native `confirm()` dialog — simple and effective. In tests, we mock it:
```javascript
vi.stubGlobal('confirm', vi.fn(() => true));   // Auto-confirm
window.confirm.mockReturnValue(false);          // Simulate cancel
```

### Add Feed Dialog: Overlay + Form
```jsx
<div className="cm-dialog-overlay" onClick={onClose}>
  <form className="cm-dialog" onClick={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
```
- **Click overlay to close** — `onClick={onClose}` on the backdrop
- **`stopPropagation`** on the form — prevents clicks inside the dialog from closing it
- **Symbols parsing** — comma-separated string split into array, uppercased, filtered for blanks
- **Form validation** — uses HTML `required` attribute for simple client-side validation; backend validates URL format and SSRF protection

### Testing Async Components with `waitFor`
```javascript
mockFetchConnections([makeConnection()]);
render(<ConnectionManager />);

await waitFor(() => {
  expect(screen.getByText('Binance BTC')).toBeInTheDocument();
});
```
`waitFor` retries the assertion until it passes or times out — essential for components that fetch data on mount. Without it, the test would run synchronously and see "Loading feeds…" instead of the actual data.

### Mocking `fetch` in Vitest
```javascript
const mockFetch = vi.fn();
vi.stubGlobal('fetch', mockFetch);

mockFetch.mockResolvedValueOnce({
  ok: true,
  json: async () => connections,
});
```
`vi.stubGlobal` replaces the global `fetch` with our mock. We use `mockResolvedValueOnce` to queue responses in order — first call returns connection list, second call returns action result, third call returns refreshed list.

---

## Production Issues Faced & How We Overcame Them

> These are real debugging scenarios encountered while getting the app running end-to-end.
> Each one is a strong engineering takeaway — they demonstrate debugging skills, not just coding skills.

---

### Issue 1: Terminal Windows Close Instantly — Can't See Errors

**Problem:** The `server-start.bat` launched backend and frontend using `Start-Process -WindowStyle Hidden`. When something failed, the windows closed immediately with no error output visible.

**Root Cause:** Hidden windows suppress all output. If the backend fails to start (e.g., wrong Java version), you never see the error.

**Fix:** Replaced with dedicated helper scripts (`run-backend.cmd`, `run-frontend.cmd`) that end with `pause`. Now if either server fails, the window stays open showing the full error output.

**Design rationale:** "Silent failures are the worst kind of failures. We changed the launcher to keep windows open on exit, making errors immediately visible. Same principle as structured logging — you always want observability into failures."

---

### Issue 2: Java 8 vs Java 21 — Class File Version Mismatch

**Problem:** Backend started from `server-start.bat` (Windows Explorer double-click) picked up Java 8 from the system PATH instead of Java 21.

**Error:**
```
class file has wrong version 61.0, this runtime only recognizes class file versions up to 52.0
```
(Version 61.0 = Java 17+, 52.0 = Java 8)

**Root Cause:** VS Code terminal had `JAVA_HOME` pointing to JDK 21, but system-level PATH resolved to Java 8. Batch files launched from Explorer don't inherit VS Code's environment.

**Fix:** Created `run-backend.cmd` that **explicitly sets `JAVA_HOME`** before running Maven:
```batch
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call mvnw.cmd -DskipTests spring-boot:run
```

**Design rationale:** "This is a classic environment isolation issue. The fix follows the 'explicit over implicit' principle — never rely on system-wide PATH for build tools. In CI/CD pipelines, we do the same with tool version pinning."

---

### Issue 3: SQLite `data/` Directory Doesn't Exist

**Problem:** Backend startup failed with `path does not exist` when trying to create `data/stream-validator.db`.

**Root Cause:** The JDBC URL `jdbc:sqlite:data/stream-validator.db` assumes the `data/` directory exists. SQLite creates the file but not parent directories.

**Fix:** Added directory creation in `SqliteConfig.java`:
```java
Files.createDirectories(Path.of("data"));
```

**Design rationale:** "Defensive initialization — never assume filesystem state. The code should create its own prerequisites. This is especially important in containerized environments where volumes may not be pre-created."

---

### Issue 4: DNS Timeout on Corporate Network — "Connecting..." Hangs Forever

**Problem:** Adding a Binance feed from the UI hung on "Connecting..." for 30+ seconds, then failed.

**Root Cause:** `FeedController.validateFeedUrl()` called `InetAddress.getByName("stream.binance.com")` for SSRF validation. On a corporate network with restricted DNS, resolution for external crypto exchanges was extremely slow or blocked.

**Fix:** Wrapped the DNS check in a try-catch that **allows the URL through** on DNS failure. The actual WebSocket connection will fail gracefully later with proper error handling:
```java
try {
    InetAddress resolved = InetAddress.getByName(host);
    // SSRF check: block private IPs
} catch (Exception e) {
    // DNS failed — allow URL through, WebSocket will handle failure
    log.warn("DNS lookup failed for {}: {}", host, e.getMessage());
}
```

**Design rationale:** "SSRF protection is important, but it shouldn't block legitimate external connections. We separated validation (fail-open for DNS) from connection (fail-closed for actual errors). The key insight: don't make a blocking network call in a synchronous REST handler."

---

### Issue 5: Feeds Lost on Every Restart — In-Memory Only

**Problem:** Every time the backend restarted, all configured feeds were gone. Users had to re-add Binance/Finnhub feeds each time.

**Root Cause:** `FeedManager` stored connections only in a `ConcurrentHashMap` — pure in-memory. No persistence layer existed.

**Fix — 3 parts:**

1. **`ConnectionStore.java`** — New JDBC repository with `save()`, `delete()`, `findAll()` methods that persist to the SQLite `connections` table.

2. **`FeedManager.java`** — Auto-saves on `addConnection()`, auto-deletes on `removeConnection()`, and loads + auto-connects all saved feeds on startup via a `CommandLineRunner` bean.

3. **`@Order` annotations** — Ensured DB schema initializes before feeds load (see Issue 6).

**Design rationale:** "This follows the Repository Pattern — the domain layer (`FeedManager`) delegates persistence to `ConnectionStore` without knowing SQL details. The auto-connect on startup gives a 'zero configuration restart' experience."

---

### Issue 6: `@PostConstruct` vs `CommandLineRunner` — Bean Initialization Order

**Problem:** After adding feed persistence, `SessionRecorderTest` and 5 other Spring context test classes failed with:
```
[SQLITE_ERROR] SQL error or missing database (no such table: connections)
```

**Root Cause (First attempt):** Used `@PostConstruct` on `FeedManager.loadSavedConnections()`. PostConstruct runs during bean creation — **before** `CommandLineRunner` beans execute. Since `SqliteConfig.initDatabase` was a `CommandLineRunner`, the schema wasn't created yet when FeedManager tried to query `connections`.

**Root Cause (Second attempt):** Changed to `@Bean CommandLineRunner` in FeedManager, but Spring doesn't guarantee execution order between multiple CommandLineRunner beans. The feed loader sometimes ran before the schema initializer.

**Final Fix:** Added `@Order` annotations:
```java
// SqliteConfig.java
@Bean
@Order(1)
CommandLineRunner initDatabase(JdbcTemplate jdbcTemplate) { ... }

// FeedManager.java
@Bean
@Order(2)
CommandLineRunner loadSavedConnections() { ... }
```

**Spring Boot Initialization Order (key knowledge):**
```
Constructor injection → @PostConstruct → CommandLineRunner (ordered) → ApplicationReadyEvent
```

**Design rationale:** "This is a classic Spring lifecycle ordering problem. `@PostConstruct` runs too early — during context building, before runners. `CommandLineRunner` runs after full context init, but multiple runners need explicit `@Order`. The lesson: if your startup logic depends on another bean's startup logic, use ordered CommandLineRunners, not PostConstruct."

---

### Issue 7: Blank Page in Browser — Vite SSE Proxy Crash

**Problem:** Browser showed a completely blank white page at `localhost:5174` despite both servers running.

**Root Cause:** The Vite proxy config had an SSE-specific entry with a `configure` callback that set response headers in the `proxyReq` event:
```javascript
// PROBLEMATIC
'/api/stream': {
  configure: (proxy) => {
    proxy.on('proxyReq', (proxyReq, req, res) => {
      res.setHeader('X-Accel-Buffering', 'no');  // Can throw if headers sent
    });
  },
},
```
Setting headers after they've been sent can cause an unhandled exception in the proxy middleware.

**Fix:** Removed the separate SSE proxy entry. The single `/api` proxy handles all routes including SSE. Added an `ErrorBoundary` React component to catch and display any future rendering errors:
```jsx
class ErrorBoundary extends React.Component {
  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }
  render() {
    if (this.state.hasError) return <pre>{this.state.error?.message}</pre>;
    return this.props.children;
  }
}
```

**Design rationale:** "Two lessons here. First: proxy middleware errors can silently break an entire dev server — always test proxy configs carefully. Second: React ErrorBoundary is essential for production apps. Without it, a single component throwing an error gives users a blank screen. With it, you get a useful error message and the rest of the app remains usable."

---

### Summary Table: Quick Reference

| # | Issue | Root Cause | Fix | Key Concept |
|---|---|---|---|---|
| 1 | Terminal closes instantly | Hidden windows hide errors | `pause` at end of scripts | Observability |
| 2 | Java 8 vs 21 | System PATH mismatch | Explicit `JAVA_HOME` in script | Environment isolation |
| 3 | `data/` dir missing | SQLite won't create dirs | `Files.createDirectories()` | Defensive initialization |
| 4 | DNS timeout on corp net | Blocking `InetAddress.getByName()` | Catch & allow through | Fail-open validation |
| 5 | Feeds lost on restart | In-memory only storage | `ConnectionStore` + auto-load | Repository Pattern |
| 6 | Schema not created yet | `@PostConstruct` too early | `@Order` on CommandLineRunners | Spring lifecycle ordering |
| 7 | Blank page in browser | Proxy header crash + no ErrorBoundary | Simplified proxy + ErrorBoundary | Defense in depth |

### CSS Status Indicators
```css
.cm-card.connected { border-left: 3px solid var(--green); }
.cm-card.reconnecting { border-left: 3px solid var(--yellow); }
.cm-card.disconnected { border-left: 3px solid var(--text-secondary); }
.cm-card.error { border-left: 3px solid var(--red); }
```
The `status.toLowerCase()` directly maps to CSS class names — no switch/case needed. The colored left border gives instant visual feedback matching the 🟢🟡🔴 status icons.

### Key Takeaways
- "ConnectionManager is the only CRUD-style component — it uses REST fetch + polling, while data-intensive components use SSE streaming"
- "I chose polling over SSE for connection state because state changes are rare — adding a 5th SSE endpoint would be over-engineering"
- "The overlay + stopPropagation pattern for the dialog is the simplest modal implementation — no library needed for a single dialog"
- "All 8 FeedController endpoints are wired: list, add, update, delete, start, stop, subscribe, unsubscribe"

---

## Phase 3, Step 6 — ValidationDashboard.jsx

**File:** `components/ValidationDashboard.jsx` + `test/ValidationDashboard.test.jsx`  
**Tests:** 17 new → **69 frontend tests total**

### What It Does
8-area validation status dashboard with live SSE updates. Each card shows one of the 8 validation areas (ACCURACY, LATENCY, COMPLETENESS, RECONNECTION, THROUGHPUT, ORDERING, SUBSCRIPTION, STATEFUL) with pass/warn/fail status, metric value, threshold, and expandable details.

### SSE Payload Shape
```json
{
  "timestamp": "2026-03-23T14:30:00Z",
  "results": {
    "ACCURACY": { "area": "ACCURACY", "status": "PASS", "message": "...",
                  "metric": 99.98, "threshold": 99.9, "details": {} },
    "LATENCY": { ... },
    // ... 8 total
  },
  "overallStatus": "PASS",
  "ticksProcessed": 12340
}
```
The hook uses `{ maxItems: 1 }` — we only care about the **latest** validation snapshot, not a history buffer.

### Constant Area Array Pattern
```javascript
const ALL_AREAS = [
  'ACCURACY', 'LATENCY', 'COMPLETENESS', 'RECONNECTION',
  'THROUGHPUT', 'ORDERING', 'SUBSCRIPTION', 'STATEFUL',
];
```
Instead of `Object.keys(results)`, we always render all 8 cards from a constant array. This means:
- Cards appear immediately (even before any SSE data arrives)
- The order is deterministic (Object.keys doesn't guarantee order)
- Missing areas show "Waiting for data…" instead of nothing

### Click-to-Expand Pattern
```jsx
const [expanded, setExpanded] = useState(false);

<div className="vd-card" onClick={() => setExpanded(e => !e)}>
  {/* ... header + body ... */}
  {expanded && Object.keys(details).length > 0 && (
    <div className="vd-card-details">
      {Object.entries(details).map(([key, val]) => (...))}
    </div>
  )}
</div>
```
Each `ValidationCard` manages its own `expanded` state independently (no parent coordination needed). The details are only rendered when both `expanded` is true AND details exist — no empty panels.

### Area-Specific Metric Formatting
```javascript
function formatMetric(area, value) {
  switch (area) {
    case 'ACCURACY':  return `${value.toFixed(2)}%`;     // 99.98%
    case 'LATENCY':   return `${value.toFixed(0)}ms`;     // 42ms
    case 'THROUGHPUT': return `${value.toLocaleString()} msg/s`;  // 12,340 msg/s
    case 'COMPLETENESS': return `${value} gaps`;          // 2 gaps
    // ...
  }
}
```
Metrics mean different things per area — percentages for accuracy, milliseconds for latency, counts for completeness. The formatter ensures each is human-readable without requiring the backend to pre-format strings.

### CSS Grid Layout
```css
.vd-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
}
```
`auto-fill` + `minmax(220px, 1fr)` creates a responsive grid:
- On wide screens: 4 cards per row (matching blueprint wireframe)
- On narrow screens: automatically wraps to 2 or 1 per row
- No media queries needed — CSS Grid handles it

### Status Color System
Three consistent indicators per card:
1. **Left border** — `.vd-card.status-pass { border-left: 3px solid var(--green); }`
2. **Status icon** — ✅ PASS, ⚠️ WARN, ❌ FAIL
3. **Overall banner** — colored background pill at the top

All use the same CSS variables (`--green`, `--yellow`, `--red`) defined in `:root`.

### Accessibility
Cards use `role="button"` and `tabIndex={0}` with a keyboard handler for Enter key — making expandable cards accessible without a real `<button>` element. This is important for screen readers.

### Key Takeaways
- "I render all 8 cards from a constant array rather than from `Object.keys(results)` — this ensures deterministic order and shows placeholder cards before data arrives"
- "Each card manages its own expanded state independently — no need for parent coordination since cards don't interact with each other"
- "The CSS Grid with `auto-fill` + `minmax` creates a responsive layout without media queries — it adapts automatically from 4 columns down to 1"
- "Area-specific metric formatting ensures 'accuracy' shows as 99.98% while 'latency' shows as 42ms — the backend sends raw numbers, the frontend decides the presentation"

---

## Phase 4, Step 6 — SessionManager.jsx (28 tests)

### What It Does
Full session lifecycle UI: start/stop recording, browse saved sessions, replay through validators, export as JSON/CSV, and delete with confirmation. Replaces the PlaceholderPanel on the "Sessions" tab.

### Architecture: Container Component with Fetch-Based CRUD

SessionManager is a **container component** — it owns all state and orchestrates API calls. Three internal components (`StartRecordingDialog`, `SessionCard`, `ReplayPanel`) are pure presentational, receiving data and callbacks as props.

```
SessionManager (state owner)
  ├── StartRecordingDialog (form → onStart callback)
  ├── ReplayPanel (progress display → onClose callback)
  └── SessionCard × N (card display → onReplay/onExport/onDelete callbacks)
```

### Key Design Decisions

**1. Polling instead of SSE for session list:**
```javascript
useEffect(() => {
  fetchSessions();
  pollRef.current = setInterval(fetchSessions, POLL_INTERVAL);
  return () => clearInterval(pollRef.current);
}, [fetchSessions]);
```
Sessions change infrequently (start/stop/delete). Polling at 3s intervals is simpler and sufficient. SSE would be over-engineering for a list that rarely updates. The cleanup function prevents interval leak on unmount.

**2. Recording detection from session list — no separate state:**
```javascript
const active = data.find((s) => s.status === 'RECORDING');
setRecording(active || null);
```
Instead of tracking recording state separately, we derive it from the session list. This is the single-source-of-truth pattern — the backend is authoritative, and the UI reflects it. No risk of frontend/backend state divergence.

**3. Two-phase delete (confirm pattern):**
```jsx
{confirmDelete ? (
  <><button onClick={onDelete}>Confirm</button>
    <button onClick={() => setConfirmDelete(false)}>Cancel</button></>
) : (
  <button onClick={() => setConfirmDelete(true)}>🗑</button>
)}
```
Each card manages its own `confirmDelete` state. The delete button transforms into Confirm/Cancel inline — no modal dialog. This is per-card isolation: deleting one session doesn't affect other cards' UI state.

**4. Optimistic removal on delete:**
```javascript
setSessions((prev) => prev.filter((s) => s.id !== id));
```
After the DELETE succeeds, we remove the session from local state immediately rather than re-fetching. This gives instant visual feedback. The next poll will confirm the server state.

**5. Export via blob download:**
```javascript
const blob = await res.blob();
const url = URL.createObjectURL(blob);
const a = document.createElement('a');
a.href = url;
a.download = `session-${id}.${format}`;
a.click();
URL.revokeObjectURL(url);
```
We create a hidden anchor, trigger a click programmatically, then revoke the blob URL. The `revokeObjectURL` prevents memory leaks — blob URLs persist until explicitly revoked or the page unloads.

**6. Replay as synchronous POST + result display:**
The current replay uses the existing `POST /api/sessions/{id}/replay` endpoint, which replays inline and returns results. The SessionReplayer (backend) provides async replay with speed control — the UI can be upgraded to use that when needed, but the synchronous approach is simpler for MVP.

### CSS Architecture

**Reusing existing design system classes** (`toolbar-btn`, `toolbar-btn.primary`, `toolbar-btn.danger`, `cm-dialog-overlay`, `cm-dialog`) for buttons and dialogs — consistent look without new CSS.

**New `sm-*` namespace** for SessionManager-specific styles:
- `sm-card` — session card with color-coded left border (green=completed, red=failed)
- `sm-recording-active` — pulsing red dot animation for active recording
- `sm-progress-bar` — smooth animated progress fill for replay
- `sm-results-grid` — auto-fill responsive grid for validation results
- `sm-stat` — label/value stat display in a 4-column grid

### Testing Without `@testing-library/user-event`

We use `fireEvent` from `@testing-library/react` instead of `userEvent`. Key difference:

```javascript
// fireEvent — synchronous, dispatches a single DOM event
fireEvent.click(button);
fireEvent.change(input, { target: { value: 'text' } });

// userEvent — async, simulates full user interaction (focus, keydown, keyup, etc.)
await user.click(button);
await user.type(input, 'text');
```

`fireEvent` is sufficient for our tests and requires no additional dependency. The tradeoff: we don't test focus/blur/keyboard event sequences. For a dashboard like this, that's acceptable.

### Testing Pattern: Mock `fetch` with Route Matching

```javascript
global.fetch = mockFetch((url, opts) => {
  if (url === '/api/sessions/start' && opts?.method === 'POST') {
    return jsonResponse(makeSession(), 201);
  }
  return jsonResponse([]);  // default: session list
});
```

Each test creates its own fetch mock with a simple URL router. The `mockFetch` helper wraps the handler in `vi.fn()` for assertion support. Default responses handle the initial session list load that happens on mount.

### Key Takeaways

1. **"Why polling instead of WebSocket/SSE for the session list?"** — Sessions change infrequently. Polling at 3s gives adequate freshness without the complexity of maintaining a push connection for a low-frequency resource. SSE is reserved for high-frequency data like live ticks.

2. **"Why derive recording state from the session list?"** — Single source of truth. If the recording started from another tab or the backend restarted, polling catches it. Tracking recording state separately creates a synchronization problem.

3. **"How do you handle the delete race condition?"** — Optimistic UI removal after successful DELETE, then the poll confirms. If the DELETE fails, the error is shown and the next poll restores the card. No stale UI.

4. **"Why `URL.revokeObjectURL` after download?"** — Blob URLs are refcounted. Without revoking, each export leaks a blob in memory. In a session with many exports, this could accumulate significant memory.

---

## Phase 4, Step 7: AlertStore.java + AlertController.java + AlertPanel.jsx

### Alert.java — Domain Model

```java
public enum Severity { INFO, WARN, CRITICAL }
```

Simple enum with three levels. CRITICAL maps to 🔴, WARN to 🟡, INFO to 🟢 on the frontend. The enum is stored as TEXT in SQLite (not ordinal) for human-readable data.

### AlertStore.java — Listener Pattern for SSE Broadcasting

```java
private final List<Consumer<Alert>> listeners = new CopyOnWriteArrayList<>();

public void save(Alert alert) {
    // ... JDBC insert ...
    listeners.forEach(listener -> listener.accept(saved));
}
```

**Key insight**: The store itself broadcasts to SSE listeners. When `save()` completes, it iterates over registered `Consumer<Alert>` callbacks. Each callback is the `StreamController.onNewAlert()` method, which pushes the alert to all connected SSE emitters.

**Why CopyOnWriteArrayList?** Listeners register/unregister rarely (SSE connect/disconnect), but `save()` iterates frequently. COW optimizes for read-heavy access — iteration never needs synchronization, only mutation does.

### AlertStore.java — RowMapper Pattern

```java
private final RowMapper<Alert> rowMapper = (rs, rowNum) -> new Alert(
    rs.getLong("id"),
    rs.getString("area"),
    Alert.Severity.valueOf(rs.getString("severity")),
    rs.getString("message"),
    rs.getBoolean("acknowledged"),
    Instant.parse(rs.getString("created_at"))
);
```

**Spring JDBC RowMapper**: A functional interface `(ResultSet, int) → T` that maps each database row to a domain object. Defined once as a field, reused across all query methods. This avoids duplicating mapping logic and ensures consistent deserialization.

**`Severity.valueOf()`**: Direct enum conversion from the TEXT column. Works because we store the enum name (e.g., "CRITICAL"), not the ordinal.

**`Instant.parse()`**: SQLite stores timestamps as ISO-8601 strings. `Instant.parse()` handles the "Z" suffix timezone directly.

### AlertController.java — REST CRUD

7 endpoints following REST conventions:
- `GET /api/alerts` — list all (sorted by createdAt DESC)
- `GET /api/alerts/unacknowledged` — filtered list
- `GET /api/alerts/count` — `{total, unacknowledged}` map
- `POST /api/alerts/{id}/acknowledge` — mark one as read
- `POST /api/alerts/acknowledge-all` — bulk acknowledge
- `DELETE /api/alerts/{id}` — delete one
- `DELETE /api/alerts` — clear all

**Why POST for acknowledge, not PUT/PATCH?** Acknowledge is an action, not a partial update. It's more like an RPC call ("acknowledge this alert") than a resource mutation. POST for actions, PUT/PATCH for state changes.

### StreamController.java — Alert SSE Endpoint

```java
@GetMapping(value = "/api/stream/alerts", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamAlerts() {
    SseEmitter emitter = new SseEmitter(0L);
    alertEmitters.add(emitter);
    emitter.onCompletion(() -> alertEmitters.remove(emitter));
    emitter.onTimeout(() -> alertEmitters.remove(emitter));
    emitter.onError(e -> alertEmitters.remove(emitter));
    return emitter;
}
```

Fifth SSE endpoint (ticks, validation, latency, throughput, alerts). Same pattern: create emitter → add to CopyOnWriteArrayList → register cleanup callbacks → return.

**Integration with AlertStore**: In the constructor, `alertStore.addListener(this::onNewAlert)` registers a callback. When `AlertStore.save()` fires, `onNewAlert()` iterates alertEmitters and sends the alert as an SSE event with name "alert".

### AlertPanel.jsx — Hybrid SSE + REST Polling

```javascript
const { latest: liveAlert } = useSSE('/api/stream/alerts', 'alert', { maxItems: 1 });

useEffect(() => {
    fetchAlerts();
    pollRef.current = setInterval(fetchAlerts, POLL_INTERVAL);
    return () => clearInterval(pollRef.current);
}, [fetchAlerts]);
```

**Why both SSE and polling?** SSE delivers real-time alerts the instant they're created — zero latency for new alerts. Polling every 5 seconds catches state changes from other sources (e.g., another user acknowledged an alert, or the backend recovered from a restart). The combination gives both immediacy AND eventual consistency.

**`maxItems: 1`**: Only need the latest alert from SSE. The full list comes from REST polling.

### AlertPanel.jsx — Optimistic UI Updates

```javascript
const acknowledgeAlert = async (id) => {
    await fetch(`/api/alerts/${id}/acknowledge`, { method: 'POST' });
    setAlerts((prev) =>
        prev.map((a) => (a.id === id ? { ...a, acknowledged: true } : a))
    );
};
```

After the POST succeeds, we update local state immediately without re-fetching. The next poll will confirm. If the POST fails, the error is shown and the alert stays unacknowledged until the next poll refreshes the list.

### AlertPanel.jsx — Multi-level Sorting

```javascript
const sorted = [...displayed].sort((a, b) => {
    if (a.acknowledged !== b.acknowledged) return a.acknowledged ? 1 : -1;
    const sevA = SEVERITY_ORDER[a.severity] ?? 3;
    const sevB = SEVERITY_ORDER[b.severity] ?? 3;
    if (sevA !== sevB) return sevA - sevB;
    return 0;
});
```

Three-tier sort: (1) unacknowledged before acknowledged, (2) CRITICAL → WARN → INFO within each group, (3) preserve server order (createdAt DESC). This ensures the most urgent, unread alerts are always at the top.

### Testing: Timestamp Ordering in SQLite Tests

```java
Alert a1 = new Alert(null, "LATENCY", Alert.Severity.CRITICAL, "msg1", false,
    Instant.parse("2024-01-01T10:00:00Z"));
Alert a2 = new Alert(null, "ACCURACY", Alert.Severity.WARN, "msg2", false,
    Instant.parse("2024-01-01T10:00:01Z"));
```

**Lesson learned**: `Instant.now()` called in rapid succession in tests often produces the same value (millisecond-precision, fast CPU). SQLite's `ORDER BY created_at DESC` then returns arbitrary order. Fix: use explicit, spaced-out timestamps with `Instant.parse()`.

### Key Takeaways

1. **"Why does AltertStore broadcast via listeners instead of the controller calling SSE directly?"** — Separation of concerns. The store is the single source of truth for alert persistence. It doesn't know about SSE or HTTP — it just notifies subscribers. The controller registers as a subscriber. This makes the store testable in isolation and allows other consumers (e.g., a Slack webhook) to subscribe without modifying the controller.

2. **"Why CopyOnWriteArrayList for both emitters and listeners?"** — Both have the same access pattern: rarely modified (connect/disconnect), frequently iterated (on every tick/alert). COW avoids `ConcurrentModificationException` during iteration without explicit locks. The trade-off — expensive writes — is acceptable for low-frequency mutations.

3. **"How do you handle SSE connection failures in AlertPanel?"** — The `useSSE` hook has exponential backoff (1s → 30s cap). If SSE fails, REST polling at 5s still delivers alerts. The user sees no gap — just slightly delayed alerts until SSE reconnects. This is graceful degradation.

4. **"Why not WebSocket for alerts?"** — Alerts are server-to-client only. SSE is the right tool for unidirectional push. WebSocket adds bidirectional complexity we don't need. SSE also auto-reconnects natively in browsers (EventSource), while WebSocket requires manual reconnection logic.

---

## Phase 4, Step 8: BackpressureQueue.java

### The Problem Backpressure Solves

Without backpressure, the WebSocket ingestion thread calls `ValidatorEngine.onTick()` synchronously. If validators are slow (complex computation, I/O), the ingestion thread blocks — which can cause the WebSocket buffer to overflow, drop messages, or disconnect the feed entirely.

**Backpressure decouples ingestion speed from processing speed.** The queue absorbs bursts, and the consumer processes at whatever speed the validators can sustain.

### Architecture — Where It Sits in the Data Flow

```
FeedConnection (WebSocket)
    → FeedManager.broadcastTick()
        → StreamController.onTick()        → SSE to browsers (immediate, no buffering)
        → BackpressureQueue.submit()        → [bounded ArrayBlockingQueue]
            → consumer thread → ValidatorEngine.onTick()
                → 8 validators (ordering, accuracy, latency, etc.)
                → notifyListeners() → StreamController.onValidationUpdate() → SSE
```

**Key design choice**: SSE tick broadcast is NOT queued — it goes directly from FeedManager to StreamController for zero-latency display. Only the validator pipeline (which is CPU-bound) goes through the bounded queue.

### ArrayBlockingQueue — Why Not LinkedBlockingQueue?

```java
private final ArrayBlockingQueue<Tick> queue;
```

- **ArrayBlockingQueue**: Pre-allocated array, fixed capacity, single lock for put/take. Better cache locality, lower GC pressure for bounded scenarios.
- **LinkedBlockingQueue**: Node-allocated per item, separate put/take locks. Better for high-contention unbounded queues.

We chose `ArrayBlockingQueue` because: (1) capacity is fixed at construction, (2) ticks are small objects, (3) the single-lock overhead is negligible since we use non-blocking `offer()`.

### Drop Policies — Two Strategies

```java
public enum DropPolicy { DROP_OLDEST, DROP_NEWEST }
```

**DROP_OLDEST** (default): When queue is full, remove the head (oldest tick), add the new one. This preserves **freshness** — validators always see the most recent market data. Stale ticks are discarded.

```java
if (!queue.offer(tick)) {
    queue.poll();   // Evict oldest
    queue.offer(tick); // Guaranteed to succeed after poll
    droppedCount.incrementAndGet();
}
```

**DROP_NEWEST**: When queue is full, reject the incoming tick. This preserves **history** — useful if you need a complete sequential record.

**Key insight:** "In a market data system, freshness usually matters more than completeness. A 5-second-old price is worse than a slightly incomplete sequence. That's why DROP_OLDEST is the default."

### Non-Blocking Submit — Never Block the Ingestion Thread

```java
public void submit(Tick tick) {
    totalSubmitted.incrementAndGet();
    if (!queue.offer(tick)) { ... }
}
```

We use `offer()` (returns false if full), NOT `put()` (blocks until space). The WebSocket ingestion thread must NEVER block — blocking would stall the entire feed connection, causing the exchange to drop us.

### Consumer Loop — Polling with Timeout

```java
private void consumeLoop() {
    while (running) {
        Tick tick = queue.poll(100, TimeUnit.MILLISECONDS);
        if (tick != null) {
            engine.onTick(tick);
            totalProcessed.incrementAndGet();
        }
    }
}
```

- **`poll(100ms)` instead of `take()`**: `take()` blocks indefinitely. With `poll(timeout)`, the loop checks `running` every 100ms, enabling clean shutdown.
- **Exception isolation**: `engine.onTick()` is wrapped in try-catch. One bad tick won't kill the consumer thread.
- **Daemon thread**: Set `setDaemon(true)` so the JVM can exit even if the consumer is running.

### Metrics — Observability for Production

```java
private final AtomicLong droppedCount = new AtomicLong(0);
private final AtomicLong totalSubmitted = new AtomicLong(0);
private final AtomicLong totalProcessed = new AtomicLong(0);
```

Three key metrics:
- **totalSubmitted**: How many ticks entered the system
- **totalProcessed**: How many ticks made it through to validators
- **droppedCount**: `totalSubmitted - totalProcessed - queueSize` = dropped

If `droppedCount` is rising, the system is under backpressure. This is the metric that should trigger an alert.

### Spring Wiring — @Autowired for Multiple Constructors

```java
@Autowired
public BackpressureQueue(FeedManager feedManager, ValidatorEngine engine) {
    this(engine, DEFAULT_CAPACITY, DropPolicy.DROP_OLDEST);
    feedManager.addGlobalTickListener(this::submit);
}
```

**Lesson learned**: When a class has multiple constructors, Spring requires `@Autowired` on the one to use. Without it, Spring looks for a no-arg default constructor and fails with "No default constructor found." With a single constructor, `@Autowired` is implicit.

### Test Pattern — Stopping the Consumer to Test Queue Behavior

```java
bpq.shutdown();
Thread.sleep(150); // Let consumer thread exit

bpq.submit(createTick(1)); // Goes into queue
bpq.submit(createTick(2)); // Goes into queue
// Queue is now filling up because no consumer is draining it
```

To test drop policies, we need the queue to actually fill up. But the consumer thread drains ticks faster than tests submit them. Solution: shut down the consumer first, then submit ticks to observe queue-full behavior without race conditions.

### Test Pattern — Polling for Async Completion

```java
private void waitForProcessed(BackpressureQueue queue, long expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (queue.getTotalProcessed() < expected && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
    }
    assertThat(queue.getTotalProcessed()).isGreaterThanOrEqualTo(expected);
}
```

The consumer thread is async — submitting a tick doesn't mean it's processed yet. We poll `totalProcessed` with a 2-second timeout. This avoids flaky `Thread.sleep(500)` guesses and fails fast if the consumer dies.

### Idempotent Processing — Already Done in Validators

The blueprint groups backpressure and idempotent processing together. Idempotency was implemented in Phase 2 — every validator already skips duplicate sequence numbers:

```java
Long lastSeq = lastSequenceBySymbol.get(tick.getSymbol());
if (lastSeq != null && tick.getSequenceNum() <= lastSeq) {
    return; // Already processed
}
```

This means even if a tick somehow gets submitted twice (e.g., WebSocket replay after reconnect), validators won't double-count it. The BackpressureQueue doesn't need its own dedup — validators handle it.

### Key Takeaways

1. **"Why a separate queue instead of rate-limiting at the WebSocket level?"** — Rate-limiting at the source means losing data before we can decide what to keep. The bounded queue lets us make intelligent decisions about WHICH ticks to drop (oldest vs. newest) based on our processing capacity. It's application-level backpressure, not transport-level.

2. **"What happens when the queue fills up in production?"** — With DROP_OLDEST, the system gracefully degrades. Validators process the freshest data, and the `droppedCount` metric rises. An alert fires, and ops can scale up processing or reduce the feed rate. No crash, no data corruption — just controlled loss of stale data.

3. **"Why a single consumer thread?"** — Validators maintain per-symbol state (ConcurrentHashMaps). Multiple consumer threads would process ticks out of order, causing false ordering violations. A single thread preserves the total order from the queue. If we needed parallelism, we'd partition by symbol.

4. **"How does this compare to Kafka/Reactor backpressure?"** — Same concept, different scale. Kafka uses partition-based bounded buffers with consumer groups. Reactor/RxJava uses request-based backpressure (subscriber requests N items). Our ArrayBlockingQueue is the simplest correct implementation for a single-JVM system. In production at scale, you'd replace this with Kafka topics.

---

## Phase 5, Step 1: Dockerfile + docker-compose.yml + Build Scripts

### Multi-Stage Docker Build — Three Stages

```dockerfile
FROM node:22-alpine AS frontend-build      # Stage 1: npm ci + npm run build
FROM maven:3.9-eclipse-temurin-21 AS backend-build  # Stage 2: mvn package
FROM eclipse-temurin:21-jre-alpine          # Stage 3: runtime JRE only
```

**Why 3 stages?** The final image contains ONLY the JRE + the JAR. Node.js, npm, Maven, source code, and build tools are discarded. Result: a ~200MB image instead of ~1.5GB.

**Stage 1** builds the React frontend → produces `dist/` folder (static HTML/JS/CSS).

**Stage 2** copies the frontend `dist/` into `src/main/resources/static/` → Spring Boot serves it as static assets. Then `mvn package` produces a fat JAR with everything embedded.

**Stage 3** copies only the JAR into a minimal Alpine JRE image.

### Why `npm ci` Instead of `npm install`?

`npm ci` does a **clean install** — deletes `node_modules/`, installs exact versions from `package-lock.json`. Faster in CI/Docker (no resolution), reproducible (no floating versions), and fails if `package-lock.json` is out of sync with `package.json`.

### `mvn dependency:go-offline` — Docker Layer Caching

```dockerfile
COPY backend/pom.xml ./
RUN mvn dependency:go-offline -B
COPY backend/src ./src
```

By copying `pom.xml` first and downloading dependencies separately, Docker caches this layer. Source changes don't re-download all dependencies — only the `mvn package` layer rebuilds. This turns a 5-minute build into a 30-second rebuild.

### docker-compose.yml — Volume Mount for SQLite

```yaml
volumes:
  - ./data:/app/data    # SQLite DB persistence
```

Without the volume mount, SQLite data is stored inside the container's writable layer — lost on `docker-compose down`. The volume maps `./data` on the host to `/app/data` in the container, persisting the database across container restarts.

### Build Scripts — Windows Batch Files

- **build-dist.bat**: Frontend build → copy to backend static → Maven package. Produces a deployable JAR.
- **server-start.bat**: `start /B java -jar ...` — `/B` runs in background without opening a new window.
- **server-stop.bat**: Finds the process listening on port 8082 via `netstat` and kills it with `taskkill`.

### .dockerignore — Keep the Build Context Small

```
**/node_modules
**/target
**/.git
```

Without `.dockerignore`, Docker sends ALL files to the daemon as build context. `node_modules/` alone can be 500MB+. The ignore file keeps the context to source files only.

### Key Takeaways

1. **"Why embed the frontend in the JAR?"** — Single deployable artifact. One `docker run` serves both the API and the UI. No nginx reverse proxy, no CORS configuration, no separate frontend deploy. Spring Boot serves the static files directly from the classpath.

2. **"Why Alpine-based images?"** — Minimal attack surface (fewer packages = fewer CVEs). Smaller image size = faster pulls in CI/CD. Alpine uses musl libc instead of glibc, which is fine for JRE workloads.

3. **"How would you add CI/CD?"** — The Dockerfile IS the CI artifact. `docker build -t stream-validator .` in any CI system (GitHub Actions, Jenkins) produces the same image. Add `docker push` to a registry, then `docker-compose pull && docker-compose up -d` on the server.

---

## Phase 5, Step 2: CompareController.java + Compare Mode UI

### The Portfolio Story — Two-Tool Integration

The Compare Mode bridges two projects:
- **API Comparator** (existing): Point-in-time REST API diff tool
- **Stream Validator** (this project): Continuous real-time feed validation

Compare Mode lets you diff two recorded sessions — detecting regressions between deployments, discrepancies between exchanges, or drift between a baseline and live data.

### CompareController.java — Five Comparison Dimensions

```java
result.put("priceDifferences", comparePrices(ticksA, ticksB));
result.put("volumeDifferences", compareVolumes(ticksA, ticksB));
result.put("sequenceGaps", compareSequenceGaps(ticksA, ticksB));
result.put("latencyPatterns", compareLatency(ticksA, ticksB));
result.put("missingSymbols", compareMissingSymbols(ticksA, ticksB));
```

| Dimension | What It Detects |
|-----------|----------------|
| Price differences | Average price per symbol changed between sessions |
| Volume differences | Unusual volume changes or data gaps |
| Sequence gaps | New gaps in B that weren't in A |
| Latency patterns | p95 latency degraded between sessions |
| Missing symbols | Symbols present in A but absent in B |

### BigDecimal Arithmetic — MathContext.DECIMAL64

```java
BigDecimal diff = priceB.subtract(priceA);
double diffPercent = diff.divide(priceA, MathContext.DECIMAL64).doubleValue() * 100.0;
```

`MathContext.DECIMAL64` provides 16-digit precision (IEEE 754 decimal64). Without a MathContext, `divide()` throws `ArithmeticException` if the result has infinite decimal expansion (e.g., 1/3). DECIMAL64 rounds to 16 significant digits — more than sufficient for financial percentage calculations.

### Percentile Calculation

```java
private long percentile(long[] sorted, int p) {
    int index = (int) Math.ceil((p / 100.0) * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
}
```

Nearest-rank method: for p95 on 100 items, `ceil(0.95 * 100) - 1 = 94` → the 95th element (0-indexed). The `Math.max(0, Math.min(...))` clamping prevents array-out-of-bounds for edge cases (p=0, p=100, 1-element arrays).

### Frontend — ComparePanel with Session Selectors

Two `<select>` dropdowns for Session A and Session B, with a Compare button disabled when the same session is selected in both. Results render in a structured layout with tables for prices/volumes, stat rows for gaps, and latency comparison with visual highlighting for regressions (p95 degraded >20% highlighted in yellow).

### Self-Contained vs External Integration

The compare endpoint is **self-contained** within market-data-validator. The optional "Open in API Comparator" link is just a UI convenience — a hyperlink that opens the external tool with session JSON data. **No code dependency** between the two projects. They can run independently.

### Key Takeaways

1. **"How do you detect deployment regressions?"** — Record a session before and after deployment. POST both to `/api/compare`. It calculates price drift, new sequence gaps, latency degradation, and missing symbols. If p95 latency increases >20% or new gaps appear, the deployment likely introduced a problem.

2. **"Why compare at the session level, not tick-by-tick?"** — Tick-level comparison is noise — prices change constantly. Session-level comparison aggregates by symbol (average price, total gaps) to detect statistical shifts. A 0.5% average price drift across 10,000 ticks is meaningful; a single tick being different is not.

3. **"How does this complement the API Comparator?"** — Different complementary tools for the testing spectrum. API Comparator handles point-in-time REST responses (static data). Stream Validator handles continuous streaming data (dynamic feeds). Compare Mode bridges them for regression testing. Together they cover the full data quality lifecycle.

---

## Phase 5, Step 3: Structured JSON Logging (Logback Config)

### What We Built

Three files changed/created:
1. **logstash-logback-encoder 7.4** added to pom.xml — the industry-standard library for JSON-formatted Logback output
2. **logback-spring.xml** — profile-based dual-mode logging config
3. **StructuredArguments** added to ValidatorEngine, FeedConnection, BackpressureQueue — key validation/connection events emit searchable fields

### Why logback-spring.xml (Not logback.xml)?

Spring Boot processes `<springProfile>` tags **only** in `logback-spring.xml`. Plain `logback.xml` is loaded by Logback directly (before Spring Boot), so Spring profile tags would be silently ignored. Always use the `-spring.xml` suffix for profile-aware logging configs.

### Dual-Mode Logging Architecture

```
Development (default):   Human-readable colored console output
  HH:mm:ss.SSS INFO  [main] c.m.s.v.ValidatorEngine - Validation WARN: ...

Production (--spring.profiles.active=prod):   One JSON object per line
  {"timestamp":"2026-03-25T14:30:01Z","level":"WARN","logger_name":"c.m.s.v.ValidatorEngine","message":"Validation WARN: ...","validator":"ACCURACY","symbol":"BTCUSDT","status":"WARN","value":0.85,"threshold":0.01}
```

The same SLF4J log calls produce both formats — zero code changes needed when switching environments.

### StructuredArguments — The Key Concept

```java
log.warn("Validation {}: {} {} {} {} {}",
    result.getStatus(),
    StructuredArguments.keyValue("validator", result.getArea()),
    StructuredArguments.keyValue("symbol", tick.getSymbol()),
    StructuredArguments.keyValue("status", result.getStatus()),
    StructuredArguments.keyValue("value", result.getMetric()),
    StructuredArguments.keyValue("threshold", result.getThreshold()));
```

**In dev mode**: renders as `Validation WARN: validator=ACCURACY symbol=BTCUSDT status=WARN value=0.85 threshold=0.01` — just a clear log line.

**In prod (JSON) mode**: each `keyValue()` becomes a **top-level JSON field** — directly searchable in ELK/Splunk/CloudWatch without regex parsing. You can write queries like `validator:ACCURACY AND status:FAIL` instead of parsing free-text messages.

This is the **best of both worlds** — human-readable for development, machine-parseable for production. One import, no MDC management.

### LogstashEncoder Configuration

```xml
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <fieldNames>
        <timestamp>timestamp</timestamp>
        <version>[ignore]</version>       <!-- Remove redundant @version field -->
        <levelValue>[ignore]</levelValue> <!-- Remove numeric level (keep string) -->
    </fieldNames>
    <customFields>{"app":"stream-validator"}</customFields>
</encoder>
```

- `[ignore]` suppresses unnecessary fields like `@version` and `level_value`
- `customFields` adds static fields to every log line — useful for filtering in multi-service environments
- The encoder automatically handles: `@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `stack_trace`, plus any StructuredArguments or MDC fields

### Why WARN/FAIL Only at INFO Level?

```java
if (result.getStatus() == ValidationResult.Status.PASS) {
    log.debug("Validation PASS: ...");  // Only shows with DEBUG enabled
} else {
    log.warn("Validation {}: ...");     // Always shows at default INFO level
}
```

At 10K+ msgs/sec with 8 validators, logging every PASS at INFO would produce 80K+ log lines per second — overwhelming. Only deviations (WARN/FAIL) log at INFO/WARN. Debug-level PASS logs are available when needed for troubleshooting.

### Event-Based Logging Pattern

Every structured log includes an `event` field (e.g., `"event":"connected"`, `"event":"tick_dropped"`, `"event":"reconnect_exhausted"`). This is a best practice for structured logging — it gives each log line a **machine-readable event type** that dashboards and alerts can key on.

### Key Takeaways

1. **"How do you handle observability in streaming systems?"** — Structured JSON logging from day one. Every validation result is a searchable JSON object with fields for validator, symbol, status, value, and threshold. Connection events (connect, disconnect, reconnect, error) carry structured event types. All powered by logstash-logback-encoder with StructuredArguments — zero overhead to switch between human-readable (dev) and machine-parseable (prod) formats.

2. **"How do you debug a specific validation failure in production?"** — Filter logs by `validator:ACCURACY AND symbol:BTCUSDT AND status:FAIL`. Each JSON log line contains the exact metric value and threshold, so you see instantly: "price deviation was 2.3% against a 1% threshold." No regex parsing required.

3. **"How do you handle log volume at high throughput?"** — Only WARN/FAIL validation events log at INFO level. PASS results log at DEBUG (suppressed by default). Drop events in the backpressure queue log at TRACE. This gives a clean signal-to-noise ratio — you only see problems, not 80K "everything is fine" messages per second.

---

## Phase 1 Final: E2E Verification & Production Bug Fixes

### Context

After all features were built and 592 backend tests passed, a full end-to-end verification was performed against a live Binance WebSocket feed. This discovered **4 real bugs** that unit tests didn't catch — demonstrating why E2E verification matters even with strong unit test coverage.

### Bug 1: Mockito SPI Misconfiguration

**Symptom:** Multiple test classes failed with mysterious Mockito initialization errors.

**Root Cause:** The Mockito SPI file (`mockito-extensions/org.mockito.plugins.MockMaker`) contained an invalid entry `mock-maker=subclass`. This is not a valid MockMaker class name — the correct value would be `org.mockito.internal.creation.bytebuddy.SubclassByteBuddyMockMaker` or simply removing the file to use the default.

**Fix:** Removed the invalid SPI entry.

**Lesson:** SPI (Service Provider Interface) files are silently loaded by the classloader. A typo in an SPI file won't cause a compile error — it manifests as cryptic runtime failures. Always validate SPI files.

### Bug 2: SpringBootTest + MockBean = NullBean Explosion

**Symptom:** 44 tests in `FeedControllerTest` and `StreamControllerTest` failed with `BeanNotOfRequiredTypeException`.

**Root Cause:** These tests used `@SpringBootTest` with `@MockBean FeedManager`. `FeedManager` has a `@Bean @Order(2) CommandLineRunner loadSavedConnections()` method. When `FeedManager` is mocked, `loadSavedConnections()` returns `null`. Spring Boot wraps `null` beans in a `NullBean` sentinel. When the `CommandLineRunner` auto-configuration tries to execute it, it fails with `NullBean is not of type CommandLineRunner`.

**Fix:** Migrated from `@SpringBootTest` (loads full context) to `@WebMvcTest(Controller.class)` (loads only the web slice). This avoids loading `FeedManager` as a real bean entirely.

**Lesson:** `@MockBean` replaces the *real* bean, but any `@Bean` factory methods on the mocked class still get invoked — and return mock defaults (`null`). This is a well-known Spring Boot testing trap. Prefer `@WebMvcTest` for controller tests.

### Bug 3: BackpressureQueue TOCTOU Race Condition

**Symptom:** `BackpressureQueueTest.concurrentDropOldestMaintainsMetricConsistency` failed intermittently.

**Root Cause:** A Time-Of-Check-To-Time-Of-Use (TOCTOU) race in the `submit()` method. The initial `offer()` was **outside** `synchronized(dropLock)`:

```java
// BEFORE (buggy):
public void submit(Tick tick) {
    boolean added = queue.offer(tick);   // <-- Outside lock!
    if (!added) {
        synchronized (dropLock) {
            queue.poll();                // Free a slot
            queue.offer(tick);           // Insert — but another thread may have stolen the slot!
        }
    }
}
```

Thread A's `offer()` would fail, Thread A enters the lock and `poll()`s a slot free, but between `poll()` and the locked `offer()`, Thread B's *unlocked* `offer()` steals that freed slot.

**Fix:** Move the initial `offer()` inside the synchronized block:

```java
// AFTER (fixed):
public void submit(Tick tick) {
    synchronized (dropLock) {
        boolean added = queue.offer(tick);
        if (!added) {
            queue.poll();
            queue.offer(tick);
        }
    }
}
```

**Lesson:** In any concurrent code path involving "check-then-act" on shared state, the check and the act must be atomic. This is a textbook TOCTOU bug that only manifests under contention — exactly the kind of bug that passes CI 99% of the time and fails in production.

### Bug 4: SessionRecorder Never Received Ticks

**Symptom:** `POST /api/sessions/start` returned 201, but after recording, `tickCount=0, byteSize=0`.

**Root Cause:** `SessionRecorder.onTick()` was never called. The class was a Spring `@Component` with an `onTick()` method, but it was **never registered** as a `FeedManager.addGlobalTickListener()`. Only `BackpressureQueue` and `StreamController` were wired as listeners. `SessionRecorder`'s constructor only took `SessionStore` and `TickStore`.

**Fix:** Added `FeedManager` as a constructor dependency and registered `this::onTick` as a global tick listener:

```java
@Component
public class SessionRecorder {
    public SessionRecorder(SessionStore sessionStore, TickStore tickStore, FeedManager feedManager) {
        this.sessionStore = sessionStore;
        this.tickStore = tickStore;
        feedManager.addGlobalTickListener(this::onTick);
    }
}
```

**Lesson:** This is the most important bug in the project. All 592 unit tests passed — including 18 `SessionRecorderTest` tests that directly called `onTick()`. But in the real system, `onTick()` was never invoked because the wiring was missing. **Unit tests verify behavior in isolation; only E2E tests verify the system is actually wired together.** This is why integration testing matters.

### Key Engineering Lessons from Phase 1

1. **Unit tests are necessary but not sufficient.** All 592 unit tests passed while a critical wiring bug made session recording completely non-functional.

2. **TOCTOU races are invisible without contention.** The BackpressureQueue race passed single-threaded tests perfectly. Only a concurrent test with CyclicBarrier + CountDownLatch could expose it.

3. **`@MockBean` is more dangerous than it looks.** It replaces the bean but not its `@Bean` factory methods, leading to NullBean surprises. Prefer `@WebMvcTest` for controller testing.

4. **SPI files fail silently.** They're loaded by the classloader at runtime — no compile-time feedback. Always validate them.

5. **Live E2E verification is non-negotiable.** The gap between "tests pass" and "system works" is where production bugs live.
