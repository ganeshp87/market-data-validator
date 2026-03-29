# Market Data Stream Validator — System Blueprint

> **Project:** Production-grade streaming market data testing platform
> **Stack:** Java 21 LTS, Spring Boot 3.3.0, React 18 + Vite 5, SQLite, Docker
> **Package:** `com.marketdata.validator`
> **Port:** 8082
> **Status:** COMPLETE — 782 tests passing (597 backend + 185 frontend), Phase 1 hardening applied + hardening tests + E2E verified
> **Last Updated:** March 2026

> **Note:** This system evolved beyond the initial blueprint scope with production-grade enhancements including automatic alert generation, co-located component tests, batch tick parsing, session replay state machines, rolling file logging, and a live unacknowledged-alert badge. A Phase 1 production hardening pass added graceful shutdown lifecycle, reconnect race protection, backpressure concurrency guards, SQLite WAL mode, batch transaction safety, and flush error containment. A follow-up hardening-test pass added 14 targeted behavioral tests (CAS race proof, flush failure containment, destroy resilience, concurrent overflow invariant) and 2 minimal production changes for testability. A final E2E verification pass discovered and fixed a SessionRecorder wiring bug (ticks were never routed to the recorder) and a BackpressureQueue TOCTOU race condition. All additions follow the same design principles (bounded buffers, structured logging, O(1)-per-tick processing) documented throughout. A subsequent fix addressed negative latency caused by clock skew between local machine and exchange NTP-synced servers — `getLatencyMs()` now clamps to zero, and `LatencyValidator` uses the clamped value, with 5 regression tests added.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Streaming Data Model](#2-streaming-data-model)
3. [Architecture](#3-architecture)
4. [Project Structure — Complete File Tree](#4-project-structure--complete-file-tree)
5. [Backend — Package by Package](#5-backend--package-by-package)
6. [Frontend — Component by Component](#6-frontend--component-by-component)
7. [Database Schema](#7-database-schema)
8. [API Endpoints](#8-api-endpoints)
9. [The 8 Validators — Detailed Logic](#9-the-8-validators--detailed-logic)
10. [Test Suite](#10-test-suite)
11. [Docker Setup](#11-docker-setup)
12. [Build & Run Scripts](#12-build--run-scripts)
13. [Compare Mode](#13-compare-mode)
14. [Observability](#14-observability)
15. [Performance & Memory Safety](#15-performance--memory-safety)
16. [Dependency Manifest](#16-dependency-manifest)

---

## 1. Project Overview

### What It Does

A real-time market data validation platform that connects to live WebSocket feeds (Binance, Finnhub, or custom sources), validates streaming ticks across **8 independent testing areas**, records sessions for replay/comparison, and presents results through a live React dashboard with SSE (Server-Sent Events).

### Why It Matters

Market data quality is non-negotiable in financial systems. A single corrupt price or missed sequence can trigger incorrect trading decisions. This system provides continuous, automated quality assurance for streaming market data  the kind of tooling that separates production-grade FinTech from prototypes.

### Architecture in One Sentence

```
Exchange WebSocket -> FeedAdapter -> FeedConnection -> FeedManager -> BackpressureQueue -> ValidatorEngine (8 validators) -> SSE -> React Dashboard
                                                                          |
                                                                          +-> SessionRecorder -> SQLite
```

### Key Numbers

| Metric | Value |
|--------|-------|
| Validators | 8 (Accuracy, Latency, Completeness, Ordering, Throughput, Reconnection, Subscription, Stateful) |
| Backend Tests | 597 (JUnit 5 + Mockito + AssertJ) |
| Frontend Tests | 185 (Vitest + React Testing Library) |
| API Endpoints | 33 across 7 controllers |
| Database Tables | 5 (connections, sessions, ticks, validations, alerts) |
| Memory Budget | < 5 MB for 1000 symbols |
| Target Throughput | 10K+ ticks/sec |

---

## 2. Streaming Data Model

### Tick  The Core Data Unit

Every price update from an exchange is a `Tick`. This is the fundamental unit that flows through the entire system.

```java
public class Tick {
    private long id;
    private String symbol;            // "BTCUSDT", "AAPL"
    private BigDecimal price;         // Always BigDecimal  NEVER double for money
    private BigDecimal bid;           // Best bid price (nullable)
    private BigDecimal ask;           // Best ask price (nullable)
    private BigDecimal volume;        // Trade quantity
    private long sequenceNum;         // Source sequence (idempotency key)
    private Instant exchangeTimestamp;// When the exchange generated this tick
    private Instant receivedTimestamp;// When our system received it
    private String feedId;            // Which connection this came from
    private Long sessionId;           // Non-null when recording a session
    private String correlationId;     // UUID linking tick to source feed message
    private String traceId;           // UUID for tracing through validation pipeline

    public Duration getLatency();     // receivedTimestamp - exchangeTimestamp
    public long getLatencyMs();       // Latency in milliseconds (clamped to 0 for clock skew)
}
```

**Design decisions:**
- `BigDecimal` for all prices  floating-point approximation is unacceptable in finance
- `correlationId` assigned at parse time (adapter)  traces back to source message
- `traceId` assigned at validation time (engine)  traces through validation pipeline
- `sequenceNum` from the exchange  used for idempotent processing and gap detection

### Connection  Feed Configuration

```java
public class Connection {
    public enum AdapterType { BINANCE, FINNHUB, GENERIC }
    public enum Status { CONNECTED, DISCONNECTED, RECONNECTING, ERROR }

    private String id;                // UUID, auto-generated
    private String name;              // Human-readable label
    private String url;               // WebSocket URL (validated for SSRF)
    private AdapterType adapterType;  // Which parser to use
    private List<String> symbols;     // Subscribed symbols
    private Status status;            // Current connection state
    private Instant connectedAt;
    private Instant lastTickAt;       // Stale detection
    private long tickCount;           // Total ticks received
}
```

### ValidationResult  Validator Output

```java
public class ValidationResult {
    public enum Area {
        ACCURACY, LATENCY, COMPLETENESS, RECONNECTION,
        THROUGHPUT, ORDERING, SUBSCRIPTION, STATEFUL
    }
    public enum Status { PASS, WARN, FAIL }

    private Area area;
    private Status status;
    private String message;
    private double metric;            // Current measured value
    private double threshold;         // Threshold that triggered the status
    private Instant timestamp;
    private Map<String, Object> details;
}
```

### Session  Recording State

```java
public class Session {
    public enum Status { RECORDING, COMPLETED, FAILED }

    private long id;
    private String name;
    private String feedId;
    private Status status;
    private Instant startedAt;
    private Instant endedAt;
    private int tickCount;
    private long byteSize;
}
```

### Alert  Threshold Breach

```java
public class Alert {
    public enum Severity { INFO, WARN, CRITICAL }

    private long id;
    private String area;
    private Severity severity;
    private String message;
    private boolean acknowledged;
    private Instant createdAt;
}
```

### LatencyStats  Percentile Snapshot

```java
public class LatencyStats {
    private long count;
    private double avgMs;
    private long minMs;
    private long maxMs;
    private long p50Ms;
    private long p95Ms;
    private long p99Ms;
    private Instant windowStart;
    private Instant windowEnd;
}
```

---

## 3. Architecture

### Data Flow

```
+-------------------+    WebSocket     +----------------+    parse     +---------------+
| Binance/Finnhub   | --------------> | FeedAdapter    | ----------> |    Tick        |
| /Generic Feed     |                 | (per-exchange) |             |  (BigDecimal)  |
+-------------------+                 +----------------+             +-------+--------+
                                                                             |
                                                                             v
+---------------+    listeners     +----------------+    submit      +--------------------+
| FeedManager   | --------------> |   Global       | ------------> | BackpressureQueue  |
|               |                 | TickListeners  |               | (bounded 10K,      |
+---------------+                 +----------------+               |  DROP_OLDEST)      |
                                                                   +--------+-----------+
                                                                            | consumer thread
                                                                            v
+--------------------+   fan-out    +--------------------------------------------------+
|  ValidatorEngine   | ----------> |  8 Validators (each processes every tick)          |
|  (orchestrator)    |             |  +----------+ +---------+ +--------------+         |
|                    |             |  |Accuracy  | |Latency  | |Completeness  |         |
|  assigns traceId   |             |  +----------+ +---------+ +--------------+         |
|  logs structured   |             |  +----------+ +---------+ +--------------+         |
|  JSON per tick     |             |  |Ordering  | |Through- | |Reconnection  |         |
|                    |             |  +----------+ |put      | +--------------+         |
|                    |             |               +---------+                           |
|                    |             |  +--------------+ +----------+                      |
|                    |             |  |Subscription  | |Stateful  |                      |
|                    |             |  +--------------+ +----------+                      |
|                    |             +--------------------------------------------------+
|                    |
|                    |--> SSE StreamController --> React Dashboard
|                    |--> SessionRecorder --> SQLite (batched writes)
+--------------------+
```

### Key Architecture Patterns

| Pattern | Implementation |
|---------|---------------|
| **Strategy** | `FeedAdapter` interface  `BinanceAdapter`, `FinnhubAdapter`, `GenericAdapter` |
| **Observer** | `FeedManager.globalTickListeners`  decouples ingestion from processing |
| **Pipeline** | Feed -> Queue -> Engine -> SSE  clear stage boundaries |
| **Backpressure** | `BackpressureQueue`  bounded `ArrayBlockingQueue(10_000)` with DROP_OLDEST/DROP_NEWEST |
| **Idempotent Processing** | Per-symbol `lastSequenceBySymbol` map in each validator  skips duplicates |
| **Fan-out** | `ValidatorEngine.onTick()` dispatches to all 8 validators |
| **Command** | REST controllers accept commands; validators are stateful processors |

### Thread Model

```
WebSocket I/O thread     -> FeedConnection.handleMessage()
                          -> FeedManager.broadcastTick()
                          -> BackpressureQueue.submit()     <- NEVER BLOCKS

backpressure-consumer     -> BackpressureQueue.consumeLoop()
(daemon thread)           -> ValidatorEngine.onTick()       <- sequential processing
                          -> notifyListeners() -> SSE emit
```

The consumer thread is the single point of serialization  all validation happens on one thread, preserving ordering guarantees without locks.

### Shutdown Lifecycle

Four components register `@PreDestroy` callbacks for graceful shutdown:

| Component | `@PreDestroy` Behavior |
|-----------|----------------------|
| `FeedManager` | Disconnects all active WebSocket connections |
| `BackpressureQueue` | Sets `running = false`, interrupts consumer thread |
| `SessionRecorder` | Flushes any buffered ticks to SQLite before exit |
| `StreamController` | Calls `shutdownNow()` on the SSE stats scheduler |

**Caveat:** Shutdown ordering between these components is not explicitly orchestrated (no `@DependsOn`). Spring destroys beans in reverse-dependency order, which is correct in the typical case but not formally guaranteed under all wiring scenarios. See Section 15 Known Caveats.

---

## 4. Project Structure — Complete File Tree

```
market-data-validator/
+-- README.md
+-- Stream-Validator-Blueprint.md
+-- Stream-Validator-Prompts.md
+-- Learnings.md
+-- Dockerfile                              # 3-stage multi-stage build
+-- docker-compose.yml                      # Port 8082, volume, prod profile
+-- .dockerignore
+-- build-dist.sh                           # Build frontend + backend JAR
+-- dev-server-start.sh                     # Start frontend + backend in dev mode
+-- dev-server-stop.sh                      # Stop the running dev servers
|
+-- backend/
|   +-- pom.xml                             # Spring Boot 3.3.0, Java 21
|   +-- mvnw / mvnw.cmd                    # Maven wrapper
|   |
|   +-- src/
|       +-- main/
|       |   +-- resources/
|       |   |   +-- application.properties          # Dev config (port 8082, SQLite)
|       |   |   +-- application-prod.properties     # Production (Docker paths)
|       |   |   +-- logback-spring.xml              # Dual-mode: dev colored / prod JSON
|       |   |   +-- schema.sql                      # 5 tables + 3 indexes
|       |   |
|       |   +-- java/com/marketdata/validator/
|       |       +-- StreamValidatorApplication.java
|       |       |
|       |       +-- model/
|       |       |   +-- Tick.java                   # Core tick with BigDecimal, correlationId, traceId
|       |       |   +-- Connection.java             # Feed config with AdapterType enum
|       |       |   +-- ValidationResult.java       # 8-area enum, 3-status enum
|       |       |   +-- Session.java                # Recording state
|       |       |   +-- Alert.java                  # Threshold breaches
|       |       |   +-- LatencyStats.java           # Percentile snapshot
|       |       |
|       |       +-- feed/
|       |       |   +-- FeedAdapter.java             # Interface: parse, subscribe, heartbeat
|       |       |   +-- BinanceAdapter.java          # Binance trade stream parser
|       |       |   +-- FinnhubAdapter.java          # Finnhub trade array parser
|       |       |   +-- GenericAdapter.java          # Configurable field-mapping adapter
|       |       |   +-- FeedConnection.java          # Single WebSocket connection + auto-reconnect
|       |       |   +-- FeedManager.java             # Manages all connections, global tick broadcast
|       |       |
|       |       +-- validator/
|       |       |   +-- Validator.java               # Interface: onTick, getResult, reset, configure
|       |       |   +-- ValidatorEngine.java         # Orchestrator: fan-out, structured logging
|       |       |   +-- BackpressureQueue.java       # Bounded queue, DROP_OLDEST/DROP_NEWEST
|       |       |   +-- AlertGenerator.java           # Auto-generates alerts from FAIL/WARN results
|       |       |   +-- AccuracyValidator.java       # Price validation, bid<=ask, spike detection
|       |       |   +-- LatencyValidator.java        # p50/p95/p99 with sliding window
|       |       |   +-- CompletenessValidator.java   # Sequence gap detection per symbol
|       |       |   +-- OrderingValidator.java       # Timestamp ordering, business rules
|       |       |   +-- ThroughputValidator.java     # Message rate, rolling average, drop detection
|       |       |   +-- ReconnectionValidator.java   # Disconnect/reconnect/restore tracking
|       |       |   +-- SubscriptionValidator.java   # Subscribe latency, leaky unsubscribe detection
|       |       |   +-- StatefulValidator.java       # VWAP, OHLC, cumVol, stale detection
|       |       |
|       |       +-- controller/
|       |       |   +-- FeedController.java          # Feed CRUD + SSRF prevention
|       |       |   +-- ValidationController.java    # Summary, history, config, reset
|       |       |   +-- SessionController.java       # Record, replay, export (delegates to SessionExporter)
|       |       |   +-- StreamController.java        # SSE: ticks, validations, latency, throughput, alerts
|       |       |   +-- AlertController.java         # Alert CRUD + acknowledge
|       |       |   +-- CompareController.java       # Compare two sessions (5 dimensions)
|       |       |   +-- MetricsController.java       # GET /api/metrics (JSON, Prometheus-ready)
|       |       |
|       |       +-- store/
|       |       |   +-- TickStore.java               # JdbcTemplate CRUD for ticks table
|       |       |   +-- SessionStore.java            # JdbcTemplate CRUD for sessions table
|       |       |   +-- AlertStore.java              # JdbcTemplate CRUD for alerts table
|       |       |   +-- ConnectionStore.java         # JdbcTemplate CRUD for connections table
|       |       |
|       |       +-- session/
|       |       |   +-- SessionRecorder.java         # Start/stop recording, batch inserts (100 ticks)
|       |       |   +-- SessionReplayer.java         # Replay with timing gaps, speed control
|       |       |   +-- SessionExporter.java         # Export as JSON map or CSV string
|       |       |
|       |       +-- config/
|       |           +-- SqliteConfig.java            # Ensures data/ dir + runs schema.sql on startup
|       |
|       +-- test/
|           +-- resources/
|           |   +-- application-test.properties
|           |
|           +-- java/com/marketdata/validator/
|               +-- StreamValidatorApplicationTests.java
|               +-- model/
|               |   +-- TickTest.java
|               |   +-- ConnectionTest.java
|               |   +-- LatencyStatsTest.java
|               |   +-- ValidationResultTest.java
|               +-- feed/
|               |   +-- BinanceAdapterTest.java
|               |   +-- FinnhubAdapterTest.java
|               |   +-- GenericAdapterTest.java
|               |   +-- FeedConnectionTest.java
|               |   +-- FeedManagerTest.java
|               +-- validator/
|               |   +-- AccuracyValidatorTest.java
|               |   +-- LatencyValidatorTest.java
|               |   +-- CompletenessValidatorTest.java
|               |   +-- OrderingValidatorTest.java
|               |   +-- ThroughputValidatorTest.java
|               |   +-- ReconnectionValidatorTest.java
|               |   +-- SubscriptionValidatorTest.java
|               |   +-- StatefulValidatorTest.java
|               |   +-- ValidatorEngineTest.java
|               |   +-- BackpressureQueueTest.java
|               +-- session/
|               |   +-- SessionRecorderTest.java
|               |   +-- SessionReplayerTest.java
|               |   +-- SessionExporterTest.java
|               +-- store/
|               |   +-- TickStoreTest.java
|               |   +-- SessionStoreTest.java
|               |   +-- AlertStoreTest.java
|               +-- controller/
|                   +-- FeedControllerTest.java
|                   +-- ValidationControllerTest.java
|                   +-- SessionControllerTest.java
|                   +-- StreamControllerTest.java
|                   +-- AlertControllerTest.java
|                   +-- CompareControllerTest.java
|                   +-- MetricsControllerTest.java
|
+-- frontend/
    +-- package.json                         # React 18, Vite, Vitest
    +-- vite.config.js                       # API proxy to :8082
    +-- index.html
    |
    +-- src/
        +-- main.jsx                         # React entry point
        +-- App.jsx                          # Root layout with tabs
        +-- App.css                          # Global styles
        |
        +-- components/
        |   +-- ConnectionManager.jsx        # Add/start/stop feeds
        |   +-- LiveTickFeed.jsx             # Real-time tick table with auto-scroll
        |   +-- ValidationDashboard.jsx      # 8 cards with color-coded status
        |   +-- LatencyChart.jsx             # Live p50/p95/p99 visualization
        |   +-- ThroughputGauge.jsx          # Throughput meter
        |   +-- SessionManager.jsx           # Record/replay/export/compare
        |   +-- AlertPanel.jsx               # Alert list with acknowledge
        |   +-- StatusBar.jsx                # Connection/throughput status
        |   +-- AlertPanel.test.jsx          # Co-located: 22 AlertPanel tests
        |   +-- SessionManager.test.jsx      # Co-located: 33 SessionManager tests
        |
        +-- hooks/
        |   +-- useSSE.js                    # Server-Sent Events hook
        |
        +-- test/
            +-- test-setup.js                # Testing Library + jsdom config
            +-- ConnectionManager.test.jsx
            +-- LiveTickFeed.test.jsx
            +-- ValidationDashboard.test.jsx
            +-- LatencyChart.test.jsx
            +-- ThroughputGauge.test.jsx
            +-- SessionManager.test.jsx
            +-- useSSE.test.js
            +-- StatusBar.test.jsx
```

---

## 5. Backend — Package by Package

### 5.1 model/  Data Models

Six model classes represent the domain:

| Class | Purpose | Key Design Decision |
|-------|---------|---------------------|
| `Tick` | Single market data update | BigDecimal for prices; correlationId + traceId for observability |
| `Connection` | Feed configuration + runtime state | AdapterType enum drives adapter selection |
| `ValidationResult` | One validator's assessment | Area(8) x Status(3); factory methods pass/warn/fail |
| `Session` | Recording metadata | Status enum: RECORDING -> COMPLETED / FAILED |
| `Alert` | Threshold breach notification | Severity: INFO/WARN/CRITICAL; acknowledgeable |
| `LatencyStats` | Percentile snapshot | Computed from LatencyValidator's sliding window |

### 5.2 feed/  Feed Ingestion Layer

**FeedAdapter** (interface)  Strategy pattern for exchange-specific parsing:

```
Methods: getSubscribeMessage(symbols), getUnsubscribeMessage(symbols),
         parseTick(rawMessage), parseTicks(rawMessage) -> List<Tick>,
         isHeartbeat(rawMessage)
```

| Adapter | Exchange | Message Format |
|---------|----------|---------------|
| `BinanceAdapter` | Binance (crypto) | `{"e":"trade","s":"BTCUSDT","p":"45123.45","q":"0.123","T":ms,"t":seqnum}` |
| `FinnhubAdapter` | Finnhub (stocks) | `{"type":"trade","data":[{"s":"AAPL","p":178.50,"v":100,"t":ms},...]}` |
| `GenericAdapter` | Custom feeds | Configurable field mapping via constructor `Map<String,String>` |

**BinanceAdapter**  parses Binance `@trade` stream messages. Assigns `correlationId` (UUID) at parse time. Returns `null` for heartbeat/non-trade messages.

**FinnhubAdapter**  parses Finnhub trade arrays (`"data":[{...},{...}]`). Two parse methods: `parseTick(msg)` returns the first trade (implements the interface); `parseTicks(msg)` returns all trades as `List<Tick>` for batch processing. Handles ping messages and empty data arrays.

**GenericAdapter**  parses any JSON feed using configurable field mappings. Default mappings: `symbol`, `price`, `volume`, `timestamp`, `sequence`. Constructor accepts custom field names. Configurable heartbeat types.

**FeedConnection**  Single WebSocket connection lifecycle:
- Uses `ReactorNettyWebSocketClient` (from WebFlux)
- Auto-reconnect with exponential backoff: `min(2^(attempt-1) x 1000, 30000) ms`
- Maximum 10 reconnect attempts before ERROR state
- **Reconnect CAS guard:** `AtomicBoolean reconnecting` with `compareAndSet(false, true)` prevents double reconnect when Reactor's `doOnError` and `doOnTerminate` both fire for the same failure event. Guard resets on fresh `connect()` and before each retry's `doConnect()`.
- `handleDisconnect()` is package-visible (not private) to enable deterministic reconnect-race testing without mocking Reactor internals. Same convention as `calculateBackoff()`.
- Injectable WebSocketClient constructor for testing
- StructuredArguments logging on connect/disconnect/error/reconnect

**FeedManager**  Connection orchestrator:
- `ConcurrentHashMap<String, FeedConnection>` for thread-safe CRUD
- `CopyOnWriteArrayList<Consumer<Tick>>` for global tick listeners
- `checkHealth()`  detects stale feeds (no tick within 30s threshold)
- `createAdapter(type)`  factory returning the correct adapter implementation
- `@PreDestroy destroy()`  disconnects all connections on shutdown; per-connection try-catch ensures one failing `disconnect()` does not abort cleanup of remaining connections

### 5.3 validator/  The 8 Validators

**Validator** (interface):
```java
String getArea();
void onTick(Tick tick);
ValidationResult getResult();
void reset();
void configure(Map<String, Object> config);
```

**ValidatorEngine**  Orchestrator:
- Holds all 8 validators (injected via Spring, defensive copy)
- `onTick()`: assigns traceId (UUID) if absent, fans out to all validators, logs structured JSON
- `getResults()` / `getResultsByArea()`  snapshot aggregation
- Listener pattern for SSE updates
- WARN/FAIL logged at WARN level; PASS at DEBUG (avoids noise)

**BackpressureQueue**  Bounded queue between ingestion and validation:
- `ArrayBlockingQueue(10_000)` capacity
- Drop policies: `DROP_OLDEST` (default) or `DROP_NEWEST`
- **DROP_OLDEST overflow guard:** The initial `offer()` attempt and the `poll()+offer()` eviction pair are both wrapped in `synchronized(dropLock)` to prevent a TOCTOU race where concurrent submitters could steal the slot freed by `poll()`. Originally, the initial `offer()` was outside the lock, allowing a race: thread A's `offer()` fails, thread A enters the lock and `poll()`s a slot, but thread B's unlocked `offer()` steals that slot before thread A can re-offer. Fix: move the initial `offer()` inside the synchronized block.
- Dedicated daemon consumer thread with 100ms poll timeout
- Metrics: `totalSubmitted`, `totalProcessed`, `droppedCount`, `queueSize`
- `@PreDestroy shutdown()`  sets `running = false`, interrupts consumer thread
- StructuredArguments logging on drops
- Spring-wired: auto-registers as FeedManager global tick listener

**AlertGenerator**  Automatic alert creation:
- Listens to `ValidatorEngine` results after every tick
- Creates `Alert` objects when a validator enters FAIL or WARN state
- Checks `AlertStore` for existing unacknowledged alerts (10-second throttle) to avoid duplicates
- If an alert is acknowledged/deleted while failure persists, a new alert is re-created
- Clears stale alerts from previous server runs on startup

### 5.4 controller/  REST API Layer (7 Controllers)

| Controller | Base Path | Responsibilities |
|-----------|-----------|-----------------|
| `FeedController` | `/api/feeds` | Feed CRUD, start/stop, subscribe/unsubscribe, SSRF validation |
| `ValidationController` | `/api/validation` | Summary, history, config update, reset |
| `SessionController` | `/api/sessions` | Start/stop recording, export (JSON/CSV), replay, delete |
| `StreamController` | `/api/stream` | SSE endpoints for live ticks, validation, latency, throughput, alerts; `@PreDestroy` shuts down the stats scheduler |
| `AlertController` | `/api/alerts` | Alert CRUD, acknowledge, bulk operations |
| `CompareController` | `/api/compare` | Compare two sessions across 5 dimensions |
| `MetricsController` | `/api/metrics` | System metrics JSON (tick counts, pass rates, queue stats) |

**Security:** `FeedController.validateFeedUrl()` prevents SSRF by blocking loopback, site-local, link-local, and wildcard addresses via `InetAddress` resolution. Only `ws://` and `wss://` schemes allowed.

### 5.5 store/  Persistence Layer

SQLite via `JdbcTemplate` (no JPA/Hibernate  explicit SQL for simplicity and speed):

| Store | Table | Key Operations |
|-------|-------|---------------|
| `TickStore` | `ticks` | `save(tick)`, `saveBatch(ticks)` **(@Transactional)**, `findBySessionId(id)`, `deleteBySessionId(id)` |
| `SessionStore` | `sessions` | `create(session)`, `findById(id)`, `findAll()`, `updateStatus()`, `delete(id)` |
| `AlertStore` | `alerts` | `save(alert)`, `findAll()`, `findUnacknowledged()`, `acknowledge(id)`, `delete(id)` |
| `ConnectionStore` | `connections` | `save(connection)`, `findAll()`, `findById(id)`, `delete(id)` |

SQLite constraint: `maximum-pool-size=1` (single writer). `TickStore.saveBatch()` is annotated `@Transactional` for all-or-nothing batch inserts  prevents partial commits on failure. The `DataSourceTransactionManager` is auto-configured by `spring-boot-starter-jdbc`.

### 5.6 session/  Recording & Replay

| Class | Purpose |
|-------|---------|
| `SessionRecorder` | Registers as a global tick listener via `FeedManager.addGlobalTickListener(this::onTick)` in its constructor. Batches incoming ticks (100 at a time or 1-second flush interval); tracks byte size; flush errors are caught and discarded to prevent retry storms; `@PreDestroy` flushes remaining buffer on shutdown |
| `SessionReplayer` | Replays ticks with original timing gaps; speed control (1x, 2x, 5x); feeds into ValidatorEngine; state machine: `IDLE -> REPLAYING -> COMPLETED/FAILED`, with `PAUSED` reachable from `REPLAYING` |
| `SessionExporter` | `exportAsJson(session, ticks)` -> Map; `exportAsCsv(ticks)` -> String with headers |

### 5.7 config/  Application Configuration

`SqliteConfig`  Ensures the `data/` directory exists and runs `schema.sql` on startup via a `CommandLineRunner` bean. After schema initialization, applies production safety PRAGMAs: `journal_mode = WAL` (write-ahead logging for concurrent read/write safety) and `busy_timeout = 5000` (5-second wait on lock contention instead of immediate `SQLITE_BUSY` failure). DataSource and JdbcTemplate are auto-configured by Spring Boot from `application.properties`.

---

## 6. Frontend — Component by Component

### Tech Stack

- React 18.3.1 (no TypeScript  JSX only)
- Vite 5.4.21 for build/dev server
- Vitest 4.0.0 + React Testing Library for tests
- Custom `useSSE` hook for Server-Sent Events
- No external charting library  built with CSS/SVG
- API proxy: Vite proxies `/api/**` to `http://localhost:8082`

### Components

| Component | Purpose | Key Features |
|-----------|---------|-------------|
| `App.jsx` | Root layout | Tab navigation between views; polls `/api/alerts/count` every 3s for unacknowledged alert badge |
| `ConnectionManager.jsx` | Feed management | Add/edit/start/stop feeds; shows status indicators |
| `LiveTickFeed.jsx` | Tick display | Real-time table with auto-scroll, pause, symbol filter |
| `ValidationDashboard.jsx` | 8-area overview | Color-coded cards (green/yellow/red); click to expand |
| `LatencyChart.jsx` | Latency visualization | Live p50/p95/p99 with threshold line |
| `ThroughputGauge.jsx` | Throughput meter | Current msg/sec with rolling average |
| `SessionManager.jsx` | Session management | Record/stop/replay/export/compare sessions |
| `AlertPanel.jsx` | Alert notifications | List with severity colors; acknowledge/dismiss |
| `StatusBar.jsx` | Connection status | Connected count, throughput, overall health |

### Data Flow (Frontend)

```
useSSE("/api/stream/ticks")         -> LiveTickFeed
useSSE("/api/stream/validation")    -> ValidationDashboard
useSSE("/api/stream/latency")       -> LatencyChart
useSSE("/api/stream/throughput")    -> ThroughputGauge
useSSE("/api/stream/alerts")        -> AlertPanel
fetch("/api/feeds")                 -> ConnectionManager
fetch("/api/sessions")              -> SessionManager
fetch("/api/metrics")               -> StatusBar
poll("/api/alerts/count", 3s)       -> App.jsx alert badge
```

---

## 7. Database Schema

```sql
-- 5 tables in SQLite, all prices stored as TEXT for BigDecimal precision

CREATE TABLE IF NOT EXISTS connections (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    adapter_type TEXT NOT NULL,          -- BINANCE, FINNHUB, GENERIC
    symbols TEXT NOT NULL,               -- JSON array: ["BTCUSDT","ETHUSDT"]
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    feed_id TEXT NOT NULL,
    status TEXT NOT NULL,                -- RECORDING, COMPLETED, FAILED
    started_at TEXT NOT NULL,
    ended_at TEXT,
    tick_count INTEGER DEFAULT 0,
    byte_size INTEGER DEFAULT 0,
    FOREIGN KEY (feed_id) REFERENCES connections(id)
);

CREATE TABLE IF NOT EXISTS ticks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER,
    feed_id TEXT NOT NULL,
    symbol TEXT NOT NULL,
    price TEXT NOT NULL,                 -- TEXT for BigDecimal precision
    bid TEXT,
    ask TEXT,
    volume TEXT,
    sequence_num INTEGER,
    exchange_ts TEXT NOT NULL,           -- ISO-8601 instant
    received_ts TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);

CREATE INDEX IF NOT EXISTS idx_ticks_session ON ticks(session_id);
CREATE INDEX IF NOT EXISTS idx_ticks_symbol ON ticks(symbol, exchange_ts);
CREATE INDEX IF NOT EXISTS idx_ticks_feed ON ticks(feed_id, exchange_ts);

CREATE TABLE IF NOT EXISTS validations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    area TEXT NOT NULL,
    status TEXT NOT NULL,
    message TEXT,
    metric REAL,
    threshold REAL,
    details TEXT,                        -- JSON blob
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS alerts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    area TEXT NOT NULL,
    severity TEXT NOT NULL,              -- INFO, WARN, CRITICAL
    message TEXT NOT NULL,
    acknowledged INTEGER DEFAULT 0,
    created_at TEXT NOT NULL
);
```

**Important:** Prices stored as TEXT, not REAL. Financial data uses BigDecimal in Java and TEXT in SQLite to avoid floating-point precision loss. Foreign key constraints are declared in the schema but not enforced at runtime (`PRAGMA foreign_keys` is off) — see Section 15 Known Caveats.

---

## 8. API Endpoints

### Feed Management  FeedController (8 endpoints)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/feeds` | List all configured feeds |
| POST | `/api/feeds` | Add a new feed (with SSRF validation) |
| PUT | `/api/feeds/{id}` | Update feed config (must stop first to change URL) |
| DELETE | `/api/feeds/{id}` | Stop and remove feed |
| POST | `/api/feeds/{id}/start` | Start WebSocket connection |
| POST | `/api/feeds/{id}/stop` | Stop WebSocket connection |
| POST | `/api/feeds/{id}/subscribe` | Subscribe to additional symbols |
| POST | `/api/feeds/{id}/unsubscribe` | Unsubscribe from symbols |

### Validation  ValidationController (4 endpoints)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/validation/summary` | Current state of all 8 validators + overall status |
| GET | `/api/validation/history` | Ordered list of all current results |
| PUT | `/api/validation/config` | Update thresholds (global or per-area) |
| POST | `/api/validation/reset` | Reset all validator state |

### Sessions  SessionController (7 endpoints)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/sessions` | List all recorded sessions |
| POST | `/api/sessions/start` | Start recording (body: name, feedId) |
| POST | `/api/sessions/{id}/stop` | Stop recording |
| GET | `/api/sessions/{id}/ticks` | Get all ticks from a session |
| GET | `/api/sessions/{id}/export?format=` | Export as JSON (default) or CSV |
| POST | `/api/sessions/{id}/replay` | Replay through validators |
| DELETE | `/api/sessions/{id}` | Delete session + ticks |

### Streaming  StreamController (5 SSE endpoints)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/stream/ticks` | SSE stream of live ticks (optional: ?symbol= filter) |
| GET | `/api/stream/validation` | SSE stream of validation result updates |
| GET | `/api/stream/latency` | SSE stream of latency stats (every 1s) |
| GET | `/api/stream/throughput` | SSE stream of throughput stats (every 1s) |
| GET | `/api/stream/alerts` | SSE stream of live alerts |

### Alerts  AlertController (7 endpoints)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/alerts` | List all alerts |
| GET | `/api/alerts/unacknowledged` | List unacknowledged alerts |
| GET | `/api/alerts/count` | Count of unacknowledged alerts |
| POST | `/api/alerts/{id}/acknowledge` | Acknowledge a single alert |
| POST | `/api/alerts/acknowledge-all` | Acknowledge all alerts |
| DELETE | `/api/alerts/{id}` | Delete a single alert |
| DELETE | `/api/alerts` | Delete all alerts |

### Compare  CompareController (1 endpoint)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/compare` | Compare two sessions (body: sessionIdA, sessionIdB) |

Returns 5 comparison dimensions: price differences, volume differences, sequence gaps, latency patterns, missing symbols.

### Metrics  MetricsController (1 endpoint)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/metrics` | System metrics JSON |

Returns:
```json
{
  "timestamp": "ISO-8601",
  "tick_count_total": 125000,
  "validation_pass_rate": 0.875,
  "validator_count": 8,
  "validator_statuses": { "ACCURACY": "PASS", "LATENCY": "WARN", ... },
  "backpressure_queue": {
    "submitted": 125000, "processed": 124990, "dropped": 10,
    "queue_size": 5, "capacity": 10000, "drop_policy": "DROP_OLDEST", "running": true
  },
  "feeds": {
    "total_connections": 3, "active_connections": 2, "stale_feeds": 0
  }
}
```

---

## 9. The 8 Validators — Detailed Logic

### 9.1 AccuracyValidator

```
Purpose: Validate price data correctness

On each tick (with sequence dedup):
  1. price > 0? (negative/zero = invalid)
  2. bid <= ask? (crossed spread = violation)
  3. |price - lastPrice| / lastPrice > 10%? (spike detection)

State: totalTicks, validTicks, bidAskViolations, spikeCount, lastPriceBySymbol
Thresholds: PASS >= 99.99%, WARN >= 99.0%, FAIL < 99.0%
```

### 9.2 LatencyValidator

```
Purpose: Track end-to-end latency percentiles

On each tick:
  1. Calculate latency = receivedTimestamp - exchangeTimestamp
  2. Add to bounded sliding window (size 10,000)
  3. Compute p50, p95, p99 from sorted window

State: CircularBuffer<Long> latencyWindow, LatencyStats snapshot
Thresholds: PASS p95 < 100ms, WARN p95 < 200ms, FAIL p95 >= 200ms
```

### 9.3 CompletenessValidator

```
Purpose: Detect missing data (sequence gaps) and stale symbols

On each tick:
  1. Per symbol: is sequenceNum == lastSequence + 1?
  2. If gap: gapCount += (current - last - 1)
  3. Track completeness rate = 100.0 * totalTicks / (totalTicks + gapCount)
  4. Stale detection: if time since last tick > 10s, mark symbol stale
     (symbol recovers automatically when next tick arrives)

State: lastSequenceBySymbol, totalTicks, gapCount, lastTickTimeBySymbol, staleSymbols
Thresholds: PASS rate >= 99.99% AND no stale symbols,
            WARN rate >= 99.0%,
            FAIL rate < 99.0% OR any stale symbol
Heartbeat threshold: DEFAULT_HEARTBEAT_THRESHOLD_MS = 10,000 (10 seconds)
```

### 9.4 OrderingValidator

```
Purpose: Ensure ticks arrive in timestamp order

On each tick:
  1. Per symbol: current.exchangeTimestamp >= last.exchangeTimestamp?
  2. Also validates: bid <= ask (if both present), volume >= 0

State: lastTimestampBySymbol, totalTicks, outOfOrderCount, bidAskViolations, volumeViolations
Thresholds: PASS rate >= 99.99%, WARN >= 99.0%, FAIL < 99.0%
```

### 9.5 ThroughputValidator

```
Purpose: Monitor message rate and detect drops

Every tick:
  1. Increment current-second counter
  2. On second boundary: push to rolling window (60 seconds)
  3. Compare current throughput to rolling average
  4. If current < 50% of average: flag WARN

State: currentSecondCount, secondCounts buffer (60), rollingAverage, maxThroughput
Thresholds: PASS = throughput > 0 and no drops, WARN = 50%+ drop from average, FAIL = zero throughput > 5 seconds
```

### 9.6 ReconnectionValidator

```
Purpose: Verify connection resilience (event-driven, not tick-driven)

Tracks reconnection events from FeedConnection:
  1. Connection dropped -> did it auto-reconnect?
  2. How long did reconnection take?
  3. After reconnect, were subscriptions restored?

State: disconnectCount, reconnectCount, reconnectTimes, failedReconnects
Thresholds: PASS = reconnectCount == disconnectCount AND avg time < 5s
            WARN = reconnectCount < disconnectCount OR avg > 5s
            FAIL = failedReconnects > 0
```

### 9.7 SubscriptionValidator

```
Purpose: Verify subscribe/unsubscribe correctness

Tracks:
  1. Subscribe sent -> first tick within 5 seconds?
  2. Unsubscribe sent -> no more ticks within 3 seconds?
  3. All subscribed symbols receiving data?
  4. No data for unsubscribed symbols? (leaky unsubscribe)

State: subscribedSymbols, activeSymbols, subscribeLatencies, leakyUnsubscribes
Thresholds: PASS = activeSymbols == subscribedSymbols AND no leaks
            WARN = some symbols not yet active
            FAIL = leaky unsubscribes OR most symbols inactive
```

### 9.8 StatefulValidator (Critical for FinTech)

```
Purpose: Validate correctness of reconstructed state from streaming updates

On each tick (with sequence-based idempotency):
  1. Maintain per-symbol SymbolState:
     - lastPrice, cumulativeVolume, open, high, low, vwap
     - priceVolumeSum, volumeSum (for VWAP computation)
     - lastTickTime, tickCount
     (VWAP = priceVolumeSum / volumeSum)

  2. Validate state consistency:
     - PRICE_NON_POSITIVE: price > 0
     - OHLC_OPEN_OUT_OF_RANGE: low <= open <= high
     - PRICE_OUT_OF_OHLC_RANGE: low <= lastPrice <= high
     - NEGATIVE_CUMULATIVE_VOLUME: cumulativeVolume >= 0
     - VOLUME_DECREASED: cumulativeVolume non-decreasing
     - VWAP_OUT_OF_RANGE: vwap between low and high

  3. Stale data detection:
     - If now - lastTickTime > 30s: mark symbol stale
     - If ALL symbols stale: feed-level alert

State: stateBySymbol Map<String, SymbolState>, totalChecks, consistentChecks,
       violations ring buffer (bounded, max 100), staleSymbols set
Thresholds: PASS rate >= 99.99% AND no stale symbols
            WARN rate >= 99.9% OR <= 2 stale symbols
            FAIL rate < 99.9% OR > 2 stale symbols
```

---

## 10. Test Suite

### Backend  597 Tests (JUnit 5 + Mockito + AssertJ)

| Package | Test Class | Count | What It Covers |
|---------|-----------|-------|----------------|
| model | TickTest | 11 | BigDecimal precision, latency calc, null handling, correlationId/traceId, negative latency clock-skew clamping |
| model | ConnectionTest | - | ID generation, status, recordTick |
| model | LatencyStatsTest | 5 | Percentile computation |
| model | ValidationResultTest | 11 | Area enum, status, factory methods |
| feed | BinanceAdapterTest | - | JSON parsing, heartbeat, malformed, edge cases |
| feed | FinnhubAdapterTest | 15 | Trade arrays, empty data, ping, multi-trade |
| feed | GenericAdapterTest | 17 | Default mappings, custom fields, heartbeat types |
| feed | FeedConnectionTest | 21 | Backoff calculation, connect/disconnect, reconnect CAS guard (double/triple/concurrent `handleDisconnect`, intentional-disconnect precedence — CyclicBarrier 10-thread proof) |
| feed | FeedManagerTest | 22 | CRUD, health check, adapter creation for all 3 types, `destroy()` per-connection exception isolation (one-throws, all-throw, empty-map edge cases via reflection-injected mocks) |
| validator | AccuracyValidatorTest | 22 | Negative price, bid>ask, spikes, accuracy rate |
| validator | LatencyValidatorTest | 25 | Percentiles, sliding window, threshold transitions, negative latency clock-skew clamping, mixed normal+skew ticks |
| validator | CompletenessValidatorTest | 25 | Gaps, multi-symbol, stale, massive gaps |
| validator | OrderingValidatorTest | 17 | Timestamp order, bid/ask, volume |
| validator | ThroughputValidatorTest | 27 | Rate, drops, zero throughput, rolling average |
| validator | ReconnectionValidatorTest | 18 | Events, timing, subscription restoration |
| validator | SubscriptionValidatorTest | 25 | Subscribe/unsub, leaky, latency tracking |
| validator | StatefulValidatorTest | 38 | VWAP, OHLC, cumVol, stale, high-precision, 100-symbol |
| validator | ValidatorEngineTest | 25 | Fan-out, reset, null tick, 8-validator wiring, burst |
| validator | BackpressureQueueTest | 23 | DROP_OLDEST/NEWEST, concurrent overflow metric consistency (CyclicBarrier + CountDownLatch — no sleep-based timing), shutdown, metrics |
| session | SessionRecorderTest | 18 | Start/stop, batch, tick count, flush buffer behavior, destroy lifecycle |
| session | SessionRecorderFlushFailureTest | 7 | Mock-based flush failure containment: pipeline stability on DB error, buffer discard-to-prevent-retry-storm, continued recording after failure, tickCount metadata divergence (pinned as known limitation), session completion after prior flush failure, `@PreDestroy` flush + failure containment |
| session | SessionReplayerTest | 35 | Timing, speed control, single-tick, stop-when-idle |
| session | SessionExporterTest | 7 | JSON metadata, CSV headers, precision, null fields |
| store | TickStoreTest | 12 | CRUD, batch, query by session |
| store | SessionStoreTest | 12 | CRUD, status update, find |
| store | AlertStoreTest | 17 | CRUD, acknowledge, bulk ops |
| controller | FeedControllerTest | - | SSRF: loopback, private IP, link-local, empty URL |
| controller | ValidationControllerTest | - | Summary, config, reset |
| controller | SessionControllerTest | - | Record, export (JSON/CSV via exporter mock), replay |
| controller | StreamControllerTest | - | SSE emitters, symbol filter, 404 |
| controller | AlertControllerTest | - | CRUD, acknowledge, bulk |
| controller | CompareControllerTest | - | 5-dimension comparison, edge cases |
| controller | MetricsControllerTest | 7 | Tick count, pass rate, queue/feed metrics |

### Frontend  185 Tests (Vitest + React Testing Library)

| Test File | Component | What It Covers |
|-----------|-----------|----------------|
| ConnectionManager.test.jsx | ConnectionManager | Render, add feed, start/stop, API failure, 10+ connections |
| LiveTickFeed.test.jsx | LiveTickFeed | Tick table, auto-scroll, pause, symbol filter, long symbol, 100-tick |
| ValidationDashboard.test.jsx | ValidationDashboard | 8 cards, PASS/WARN/FAIL colors, expand, mixed statuses, overall |
| LatencyChart.test.jsx | LatencyChart | Chart rendering, data points, threshold |
| ThroughputGauge.test.jsx | ThroughputGauge | Gauge rendering, updates |
| SessionManager.test.jsx | SessionManager | 33 tests  record, stop, replay, export, compare *(co-located in components/)* |
| AlertPanel.test.jsx | AlertPanel | 22 tests  list, acknowledge, dismiss, severity *(co-located in components/)* |
| StatusBar.test.jsx | StatusBar | Connection count, throughput display |
| useSSE.test.js | useSSE hook | Empty event, broken JSON, rapid events |

---

## 11. Docker Setup

### Dockerfile — 3-Stage Multi-Stage Build

```dockerfile
# Stage 1: Build frontend
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: Build backend (embed frontend)
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn dependency:go-offline -B
COPY backend/src ./src
COPY --from=frontend-build /app/frontend/dist ./src/main/resources/static
RUN mvn package -DskipTests -B

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=backend-build /app/backend/target/stream-validator-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### docker-compose.yml

```yaml
services:
  stream-validator:
    build: .
    ports:
      - "8082:8082"
    volumes:
      - ./data:/app/data         # SQLite DB persistence
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATASOURCE_URL=jdbc:sqlite:/app/data/stream-validator.db
    restart: unless-stopped
```

### Running

```bash
docker compose up --build          # Build and start
docker compose up -d               # Detached mode
docker compose down                # Stop
```

---

## 12. Build & Run Scripts

### build-dist.sh

```bash
#!/bin/bash
echo "=== Building Market Data Stream Validator ==="
echo "[1/3] Building frontend..."
cd frontend
npm install
npm run build
cd ..
echo "[2/3] Copying frontend to backend static..."
cp -r frontend/dist/* backend/src/main/resources/static/
echo "[3/3] Building backend JAR..."
cd backend
./mvnw clean package -DskipTests
cd ..
echo "=== Build complete ==="
echo "JAR: backend/target/stream-validator-0.0.1-SNAPSHOT.jar"
```

### dev-server-start.sh

Dev-mode launcher that starts both backend and frontend in separate processes:
- Checks if ports 8082 (backend) and 5174 (frontend) are already in use
- Starts `backend/run-backend.sh` (Spring Boot via Maven) and `frontend/run-frontend.sh` (Vite dev server)
- Skips already-running services
- **Not** a production JAR launcher — use `build-dist.sh` + Docker for production

### dev-server-stop.sh -- Stops the running server processes.

---

## 13. Compare Mode

### Purpose

Compare two recorded sessions to detect deployment regressions, exchange inconsistencies, or replay-vs-live deviations.

### Endpoint

```
POST /api/compare { "sessionIdA": 1, "sessionIdB": 2 }
```

### 5 Comparison Dimensions

| Dimension | What It Detects |
|-----------|----------------|
| Price Differences | Average price per symbol changed between sessions |
| Volume Differences | Unusual volume changes or data gaps |
| Sequence Gaps | New gaps in session B not present in session A |
| Latency Patterns | p95 latency degraded between sessions |
| Missing Symbols | Symbols in session A absent from session B (and vice versa) |

### Use Cases

1. **Before vs After Deployment** -- Record 30 min before and after a code change
2. **Exchange A vs Exchange B** -- Compare Binance feed with backup feed
3. **Replay vs Live** -- Compare recorded baseline with live data

---

## 14. Observability

### Structured Logging

**Library:** logstash-logback-encoder 7.4 with `StructuredArguments`

**Dual-mode configuration** (logback-spring.xml):
- **Dev** (`!prod` profile): Colored human-readable console (`HH:mm:ss.SSS LEVEL [thread] logger - message`) + rolling file appender (`stream-validator.log`, 10MB max file size, 7-day retention, 50MB total cap)
- **Prod** (`prod` profile): JSON via `LogstashEncoder` -- one JSON object per line, searchable in ELK/Splunk/CloudWatch

**What gets logged (structured JSON in prod):**
```json
{
  "timestamp": "2026-03-25T14:30:01.123Z",
  "level": "WARN",
  "logger_name": "c.m.s.validator.ValidatorEngine",
  "message": "Validation WARN: ...",
  "validator": "STATEFUL",
  "symbol": "BTCUSDT",
  "status": "WARN",
  "value": 0.998,
  "threshold": 0.999
}
```

**Where StructuredArguments are used:**
- `ValidatorEngine.logValidationEvent()` -- every tick through every validator
- `FeedConnection` -- connect, disconnect, reconnect, error events
- `BackpressureQueue` -- startup config, tick drops

### Tracing

- **correlationId** (on Tick) -- UUID assigned by `FeedAdapter.parseTick()`, links tick to source WebSocket message
- **traceId** (on Tick) -- UUID assigned by `ValidatorEngine.onTick()`, traces tick through the validation pipeline

### Metrics Endpoint

`GET /api/metrics` returns system health snapshot including tick counts, validation pass rates, per-validator statuses, backpressure queue metrics, and feed connection stats. See Section 8 for full JSON schema.

---

## 15. Performance & Memory Safety

### Design Rules

| Rule | Implementation |
|------|---------------|
| No unbounded lists | All buffers are bounded (sliding windows, ring buffers) |
| O(1) per tick | Validators use counters, maps, bounded buffers -- no sorting per tick |
| Aggregation > Storage | `totalVolume += tick.volume` (O(1)) instead of `volumes.add(tick.volume)` (O(N)) |
| Bounded violation buffer | StatefulValidator keeps max 100 violations (ring buffer) |
| Batched DB writes | SessionRecorder batches 100 ticks per INSERT |
| Backpressure | BackpressureQueue drops oldest tick when queue full |
| Sequence dedup | All tick-processing validators skip already-seen sequence numbers per symbol |

### Memory Budget

```
Component                Memory Budget    Notes
------------------------------------------------------
Latency sliding window   ~80 KB           10,000 x 8 bytes
Per-symbol state          ~1 KB/symbol    Price, volume, OHLC, VWAP
100 symbols              ~100 KB          Typical subscription set
Violation buffer         ~50 KB           Last 100 violations
SSE broadcast            ~10 KB           Current state snapshot
SQLite batch             ~500 KB          Pending tick batch

Total:                   < 5 MB           Even with 1000 symbols
```

### Thread Safety

- `ConcurrentHashMap` for connection management
- `CopyOnWriteArrayList` for listener lists
- `AtomicLong` for counters
- `AtomicBoolean` CAS guard for reconnect deduplication (`FeedConnection.handleDisconnect()`)
- `synchronized(dropLock)` for the DROP_OLDEST overflow critical section (scoped only to overflow path)
- `ReentrantLock` for SessionRecorder buffer access
- Single consumer thread for validation ordering
- `volatile` for shutdown flags
- `@PreDestroy` lifecycle hooks on 4 stateful components

### Phase 1 Production Hardening (Applied)

The system has undergone a production hardening pass focused on shutdown safety, concurrency correctness, and data reliability. All changes were applied as surgical, minimal-diff fixes to existing classes.

| Area | Fix | Component |
|------|-----|-----------|
| **Graceful shutdown** | `@PreDestroy` callbacks to stop threads, disconnect feeds, flush buffers, and shut down schedulers | `BackpressureQueue`, `FeedManager`, `SessionRecorder`, `StreamController` |
| **Reconnect race** | `AtomicBoolean` CAS guard prevents double reconnect handling when Reactor's `doOnError` and `doOnTerminate` both fire for the same WebSocket failure | `FeedConnection` |
| **Backpressure TOCTOU race** | Initial `offer()` moved inside `synchronized(dropLock)` to close the race between eviction and insertion by concurrent submitters | `BackpressureQueue` |
| **SQLite reliability** | `PRAGMA journal_mode = WAL` and `PRAGMA busy_timeout = 5000` applied at startup for write-ahead logging and lock-contention tolerance | `SqliteConfig` |
| **Batch transaction safety** | `@Transactional` on `saveBatch()` ensures all-or-nothing batch inserts — no partial commits on failure | `TickStore` |
| **Flush error containment** | `flush()` catches persistence exceptions, logs the failure, and clears the buffer to prevent retry storms from crashing the ingestion pipeline | `SessionRecorder` |
| **SessionRecorder wiring** | Injected `FeedManager` as constructor dependency and registered `this::onTick` as a global tick listener — previously `onTick()` was never called during live operation, resulting in 0 ticks recorded | `SessionRecorder` |

### Known Caveats / Deferred Hardening

These items were identified during strict senior review and intentionally left unresolved. They are documented here for honest assessment and future prioritization.

**1. Shutdown ordering is not explicitly orchestrated.**
Spring's `@PreDestroy` fires in reverse-dependency order, but no `@DependsOn` annotations enforce ordering between `FeedManager`, `BackpressureQueue`, `SessionRecorder`, and `StreamController`. In theory, the consumer thread could be interrupted while feeds are still submitting ticks. For a single-user portfolio application with no live feeds running at shutdown time, this is unlikely to manifest. A formal `SmartLifecycle` implementation would resolve it.

**2. ~~`FeedManager.destroy()` does not isolate per-connection exceptions.~~ RESOLVED.**
`destroy()` now wraps each `disconnect()` in a per-connection try-catch. One failing disconnection no longer aborts cleanup of remaining connections. Verified by `FeedManagerTest.destroyDisconnectsAllConnectionsEvenWhenOneThrows()` and `destroyHandlesAllConnectionsThrowing()`.

**3. Backpressure overflow synchronization reduces but does not fully eliminate metric drift.**
The `dropLock` mutex covers the overflow code path, but the consumer thread's `queue.poll()` is not under the same lock. Under extreme contention (multiple threads submitting while the consumer is actively draining), the `droppedCount` metric can drift by ±1. This is a best-effort metric, not a financial ledger — acceptable for the system's purpose.

**4. SQLite PRAGMAs rely on the single-connection pool design.**
`PRAGMA journal_mode = WAL` and `PRAGMA busy_timeout = 5000` are executed once at startup on whichever connection HikariCP provides. With `maximum-pool-size=1`, this is the only connection, so the PRAGMAs persist for the application's lifetime. If pool size is ever increased or connection recycling behavior changes, PRAGMAs would need to be applied via `spring.datasource.hikari.connection-init-sql` instead.

**5. Foreign key enforcement remains intentionally deferred.**
The schema declares `FOREIGN KEY` constraints on `sessions.feed_id` and `ticks.session_id`, but SQLite's `PRAGMA foreign_keys` is not enabled at runtime. Enabling it broke 35 existing tests because the test fixtures create sessions and ticks without first inserting matching parent rows. Resolving this requires test fixture refactoring across the persistence and session test suites — deferred to a future pass.

**6. Flush failure prioritizes pipeline stability over tick durability.**
When `SessionRecorder.flush()` catches a persistence exception, it logs the error and clears the buffer to prevent a retry storm. This means the failed batch of ticks (up to 100) is permanently lost. Additionally, the in-memory `tickCount` — which was incremented in `onTick()` — is not decremented on flush failure. If `stop()` is called after a failed flush, the session metadata will report a higher tick count than what was actually persisted. This is a known data-integrity trade-off: pipeline liveness is prioritized over strict durability guarantees.

**7. Hardening tests now include targeted behavioral proofs, with some areas remaining correct-by-inspection.**
A follow-up test pass added 14 tests (4 reconnect CAS, 7 flush failure, 3 destroy resilience) and strengthened 1 existing concurrency test. Key improvements:
- **Reconnect CAS guard:** `handleDisconnect()` was made package-visible, enabling direct invocation. 4 tests cover double-fire, triple-fire, intentional-disconnect precedence, and a 10-thread CyclicBarrier race — all fully deterministic.
- **Flush failure containment:** 7 mock-based unit tests in `SessionRecorderFlushFailureTest` cover pipeline stability, buffer discard, continued recording, metadata divergence (pinned), session completion after failure, and `@PreDestroy` shutdown paths.
- **Destroy resilience:** 3 tests verify per-connection try-catch via reflection-injected mocks.
- **BackpressureQueue:** Concurrent overflow test now uses CyclicBarrier + CountDownLatch instead of sleep-based timing. Metric accounting invariant (`submitted == dropped + queued + processed`) is proved under 5×10 simultaneous submitters. Note: the consumer is stopped during this test, so the submitter-vs-consumer race that `dropLock` specifically guards against is not exercised — that fix remains correct-by-inspection.

**Remaining areas that resist deterministic unit testing:**
- Reconnect backoff timer scheduling (actual `Mono.delay()` / `Thread.sleep()` in a spawned virtual thread)
- CAS guard re-arm after a successful reconnect (requires a real WebSocket round-trip)
- SQLite WAL PRAGMA behavior (requires integration test with real database)
- Spring `@PreDestroy` ordering between `FeedManager`, `BackpressureQueue`, `SessionRecorder`, and `StreamController` (framework-managed lifecycle)

---

## 16. Dependency Manifest

### Backend (pom.xml)

| Dependency | Version | Purpose |
|-----------|---------|---------|
| spring-boot-starter-parent | 3.3.0 | Parent POM, dependency management |
| spring-boot-starter-web | (managed) | REST controllers, SSE, Jackson |
| spring-boot-starter-webflux | (managed) | WebSocketClient for outbound connections |
| spring-boot-starter-validation | (managed) | Bean validation annotations |
| spring-boot-starter-jdbc | (managed) | JdbcTemplate for SQLite |
| sqlite-jdbc | 3.45.3.0 | SQLite JDBC driver |
| logstash-logback-encoder | 7.4 | Structured JSON logging |
| spring-boot-starter-test | (managed) | JUnit 5, Mockito, AssertJ, MockMvc |

**Java version:** 21 LTS

### Frontend (package.json)

| Dependency | Version | Purpose |
|-----------|---------|---------|
| react | ^18.3.1 | UI framework |
| react-dom | ^18.3.1 | DOM rendering |
| @vitejs/plugin-react | ^4.3.4 | Vite React plugin |
| vite | ^5.4.21 | Build tool + dev server |
| vitest | ^4.0.0 | Test runner |
| @testing-library/react | ^16.3.0 | Component testing |
| @testing-library/jest-dom | ^6.6.3 | DOM matchers |
| jsdom | ^28.0.0 | Browser environment for tests |

---

## End of Blueprint

This document reflects the exact state of the codebase as of the Phase 1 final commit.
Every class, endpoint, test, and configuration described above exists and works.
782 tests pass (597 backend + 185 frontend).

Phase 1 production hardening has been applied and E2E verified against live Binance WebSocket feeds. A follow-up hardening-test pass added 14 targeted behavioral tests and 2 minimal production changes (package-visible `handleDisconnect()`, per-connection try-catch in `destroy()`). An E2E verification pass discovered and fixed 4 real bugs: Mockito SPI misconfiguration, SpringBootTest+MockBean NullBean interaction, BackpressureQueue TOCTOU race, and SessionRecorder missing FeedManager wiring. See Section 15 for details and known caveats.