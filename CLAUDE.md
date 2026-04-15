# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Market Data Stream Validator — a full-stack system that connects to live WebSocket market data feeds (Binance, Finnhub, custom) and validates them across 8 dimensions in real-time. Backend: Java 21 + Spring Boot 3.3.0. Frontend: React 18 + Vite. Persistence: SQLite with WAL mode.

## Commands

### Backend
```bash
cd backend
./mvnw spring-boot:run                              # Start backend (port 8082)
./mvnw test                                         # Run all 603 tests
./mvnw -Dtest=AccuracyValidatorTest test            # Run single test class
./mvnw -Dtest=AccuracyValidatorTest#methodName test # Run single test method
./mvnw clean package -DskipTests                    # Build JAR
```

### Frontend
```bash
cd frontend
npm run dev                    # Start dev server (port 5174, proxies /api → 8082)
npm test                       # Run all 185 tests (Vitest)
npm test -- useSSE             # Run single test file
```

### Full Build & Docker
```bash
./build-dist.sh                           # Build frontend → embed in backend JAR
docker compose up --build                 # Recommended: build + run everything
java -jar backend/target/stream-validator-0.0.1-SNAPSHOT.jar  # Run production JAR
```

## Architecture

### Data Flow
```
WebSocket Feed → FeedAdapter → FeedConnection → FeedManager
                                                    │
                                          BackpressureQueue (10K, DROP_OLDEST)
                                                    │
                                            ValidatorEngine
                                           (fans out to 8 validators)
                                                    │
                                    ┌───────────────┼───────────────┐
                               SSE → React      AlertGenerator   SessionRecorder → SQLite
```

### Key Design Decisions

**BackpressureQueue** (`validator/BackpressureQueue.java`) — Bounded queue (10K capacity) decoupling WebSocket ingestion from validation. Uses `DROP_OLDEST` when full (freshness over completeness). The TOCTOU race between `offer()` and overflow eviction is guarded with `synchronized(dropLock)`.

**Per-feed symbol scoping** — `Tick.getFeedScopedSymbol()` returns a composite `feedId:symbol` key. All validators use this instead of raw symbol to prevent collision when the same symbol appears on multiple feeds.

**ValidatorEngine throttling** — Listener notifications are throttled to 250ms intervals to avoid overwhelming SSE clients. The ThroughputValidator snapshot timer (1-second) is also driven here.

**Clock offset estimation** — `FeedConnection` calibrates exchange clock offset from the first 20 ticks before reporting real latency. Latency is clamped to 0 for negative values (clock skew).

**SQLite concurrency** — WAL mode + `busy_timeout(5000)`. Hikari pool max-size=1 (SQLite doesn't support concurrent writers). `TickStore.saveBatch()` is `@Transactional`.

**Session wiring** — `SessionRecorder` self-registers as a `FeedManager` listener in its constructor. No explicit wiring needed elsewhere.

### The 8 Validators
| Validator | Key Logic |
|-----------|-----------|
| Accuracy | Price validity, bid ≤ ask, spike detection (>10% deviation from rolling window) |
| Latency | Percentiles (p50/p95/p99); uses clock-offset-adjusted timestamps |
| Completeness | Sequence gap detection, stale symbol detection |
| Ordering | Per-symbol timestamp ordering |
| Throughput | Rolling message rate, drop detection |
| Reconnection | Auto-reconnect tracking, subscription restoration after reconnect |
| Subscription | Subscribe/unsubscribe lifecycle correctness |
| Stateful | VWAP, OHLC, cumulative volume tracking |

### Feed Adapters
- `BinanceAdapter` — Crypto WebSocket (JSON parsing for Binance stream format)
- `FinnhubAdapter` — Stock feed
- `GenericAdapter` — Custom JSON feeds with configurable field mapping
- `LVWRSimulatorAdapter` — LVWR_T chaos simulator (internal; injects failures for testing)

### SSE Streaming
`StreamController` exposes 5 SSE endpoints (`/api/stream/ticks`, `/api/stream/validations`, `/api/stream/latency`, `/api/stream/throughput`, `/api/stream/alerts`). The frontend `useSSE` hook connects to these. Vite dev proxy disables buffering for SSE to work correctly in development.

## Package Structure

```
backend/src/main/java/com/marketdata/validator/
├── config/       — SqliteConfig (schema init, WAL setup)
├── controller/   — 7 REST controllers + StreamController (SSE)
├── feed/         — FeedAdapter (interface), adapters, FeedConnection, FeedManager
├── model/        — Tick, Connection, Session, ValidationResult, Alert, LatencyStats
├── session/      — SessionRecorder, SessionReplayer, SessionExporter
├── simulator/    — LVWRChaosSimulator, ScenarioEngine, FailureType
├── store/        — TickStore, SessionStore, AlertStore, ConnectionStore, ValidationStore
└── validator/    — Validator (interface), 8 validators, ValidatorEngine, BackpressureQueue, AlertGenerator
```

## Production Hardening
- `@PreDestroy` on FeedManager, BackpressureQueue, SessionRecorder, StreamController for graceful shutdown
- `AtomicBoolean` CAS guard in `FeedConnection` to prevent reconnect races
- Flush error containment in SessionRecorder: catch + log + clear buffer (prevents retry storms)
- Connections saved to SQLite are auto-reconnected at startup by FeedManager

## Database
SQLite at `data/stream-validator.db` (auto-created). 5 tables: `connections`, `sessions`, `ticks`, `validations`, `alerts`. Schema initialized by `SqliteConfig` on startup.
