# AI-Assisted Development — Prompt Engineering Log

> **Project:** Market Data Stream Validator
> **Stack:** Java 21 LTS, Spring Boot 3.3.0, React 18, SQLite, Docker
> **Tests:** 777 (592 backend + 185 frontend)
> **Status:** Phase 1 Complete

---

## Overview

This document records the structured prompt engineering approach used to build this project with AI assistance (GitHub Copilot / Claude). Each phase was guided by a specific prompt with strict rules — no skipping steps, no assumptions, verification before progressing.

---

## Master Prompt (Entry Point)

> You are a strict senior software architect guiding a junior developer.
> We are building a production-grade "Market Data Stream Validator".
>
> **Rules:**
> - Guide step-by-step
> - Do NOT skip steps or jump ahead
> - Each phase must be completed and verified before moving to next
> - Always ask for confirmation before proceeding
> - Enforce clean architecture and testing discipline
> - Do NOT assume things are correct — always verify

---

## Phase Prompts

### Phase 1 — Architecture Setup

Design the system architecture.

- WebSocket ingestion
- Adapter pattern
- FeedManager → BackpressureQueue → ValidatorEngine
- SSE streaming to UI

**Output:** Component diagram, responsibilities, thread model, data flow.

### Phase 2 — Core Models

Define all core models: `Tick`, `Connection`, `ValidationResult`, `Session`, `Alert`.

- Use `BigDecimal` for financial values
- Include `correlationId`, `traceId`, `sequenceNum`
- Include enums where needed

### Phase 3 — Feed Layer

Implement feed ingestion: `FeedAdapter` interface, `BinanceAdapter`, `FinnhubAdapter`, `GenericAdapter`, `FeedConnection`, `FeedManager`.

- Reconnection logic with exponential backoff
- Heartbeat handling
- CorrelationId generation
- Unit tests for adapters

### Phase 4 — Backpressure + Engine

Implement `BackpressureQueue` (ArrayBlockingQueue, 10K capacity, DROP_OLDEST) and `ValidatorEngine` (traceId assignment, fan-out to validators, listener pattern).

- Queue overflow tests
- Basic processing flow tests

### Phase 5 — Validators (8)

Implement all 8 validators: Accuracy, Latency, Completeness, Ordering, Throughput, Reconnection, Subscription, Stateful.

- Each validator independent
- Must return `ValidationResult`
- Real logic (not placeholders)
- Tests for each validator

### Phase 6 — API Layer

Create REST API with 7 controllers (~33 endpoints): Feed, Validation, Session, Stream, Alert, Compare, Metrics.

- SSRF protection
- Input validation
- Controller tests

### Phase 7 — Session System

Implement `SessionRecorder`, `SessionReplayer`, `SessionExporter`.

- Batch write to SQLite
- Replay with speed control
- JSON + CSV export
- Tests for recording, replay, export

### Phase 8 — Persistence (SQLite)

Implement SQLite layer: `schema.sql` (5 tables), `TickStore`, `SessionStore`, `AlertStore`, `ConnectionStore`.

- BigDecimal stored as TEXT
- Proper indexes
- Store tests

### Phase 9 — Frontend Integration

Integrate React frontend: SSE hook, dashboard, alerts, session view.

- End-to-end data flow verification

### Phase 10 — Testing Expansion

Expand to 500+ backend tests. Include edge cases, concurrency, invalid inputs, large payloads.

### Phase 11 — Blueprint Compliance Review

Review full system against blueprint. Check architecture, models, validators, APIs, DB, tests. Output: matches, missing items, deviations, compliance score.

### Phase 12 — Production Hardening

Fix: graceful shutdown, reconnect race, queue concurrency, SQLite WAL, batch transactions, error containment. Minimal surgical changes only.

### Phase 13 — Hardening Tests

Add behavioral tests for: reconnect race, flush failure, destroy robustness, queue concurrency. Use `CyclicBarrier`, `CountDownLatch`. Verify behavior, not just coverage.

### Phase 14 — Final Blueprint Alignment

Update blueprint to reflect actual implementation, hardening additions, and known caveats.

---

## Key Principles

1. **Step-by-step verification** — Each phase completed and tested before moving to next
2. **Real logic first** — No placeholder implementations; every validator has real detection logic
3. **Test-driven confidence** — 777 tests before calling Phase 1 complete
4. **Blueprint as contract** — System architecture documented first, code built to match
5. **Production mindset** — Hardening pass applied the same rigor as initial development
