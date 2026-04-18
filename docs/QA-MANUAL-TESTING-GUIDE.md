# QA Manual Testing Guide — Market Data Stream Validator

> **Audience:** Beginner QA tester. No prior knowledge of this project is assumed.
> **Purpose:** Step-by-step instructions to verify every feature works correctly.
> **Last Updated:** April 2026 (Round 3 — SonarCloud maintainability refactor, security hardening, code-quality regressions added)

---

## Table of Contents

1. [Introduction](#1-introduction)
   - [What This Application Does](#11-what-this-application-does)
   - [How the System Works](#12-how-the-system-works)
   - [Glossary of Terms](#13-glossary-of-terms)
   - [Prerequisites](#14-prerequisites)
   - [How to Start the Application](#15-how-to-start-the-application)
   - [Verifying the App is Running](#16-verifying-the-app-is-running)
2. [Smoke Tests](#2-smoke-tests)
3. [Feed Management Tests](#3-feed-management-tests)
4. [Simulator Tests](#4-simulator-tests)
5. [Validator Tests](#5-validator-tests)
   - [ACCURACY](#51-accuracy-validator)
   - [LATENCY](#52-latency-validator)
   - [COMPLETENESS](#53-completeness-validator)
   - [ORDERING](#54-ordering-validator)
   - [THROUGHPUT](#55-throughput-validator)
   - [RECONNECTION](#56-reconnection-validator)
   - [SUBSCRIPTION](#57-subscription-validator)
   - [STATEFUL](#58-stateful-validator)
6. [Session Recording Tests](#6-session-recording-tests)
7. [Session Replay Tests](#7-session-replay-tests)
8. [Alert Tests](#8-alert-tests)
9. [SSE Stream Tests](#9-sse-stream-tests)
10. [Validation Configuration Tests](#10-validation-configuration-tests)
11. [Error Handling Tests](#11-error-handling-tests)
12. [Advanced / Stress Tests](#12-advanced--stress-tests)
13. [Regression Tests](#13-regression-tests) *(REG-01 – REG-10)*
14. [API Quick Reference](#14-api-quick-reference)
15. [UI Manual Testing Guide](#15-ui-manual-testing-guide)
    - [Getting Started With the UI](#151-getting-started-with-the-ui)
    - [Live Feed Tab](#152-live-feed-tab-)
    - [Connections Tab](#153-connections-tab-)
    - [Validation Tab](#154-validation-tab-)
    - [Latency Tab](#155-latency-tab-)
    - [Sessions Tab](#156-sessions-tab-)
    - [Alerts Tab](#157-alerts-tab-)
    - [Simulator Tab](#158-simulator-tab-)
    - [Compliance Tab](#159-compliance-tab-️)
    - [Browser Console Checks](#1510-browser-console-checks)
    - [SSE Connection Visual Check in Network Tab](#1511-sse-connection-visual-check-in-network-tab)
    - [End-to-End Visual Walkthrough](#1512-end-to-end-visual-walkthrough-full-scenario)

---

## 1. Introduction

### 1.1 What This Application Does

The **Market Data Stream Validator** is a tool that connects to live financial market data feeds — real-time price streams for assets like Bitcoin (BTCUSDT) — and continuously checks whether the data arriving is good quality.

Think of it like a quality inspector on a factory assembly line. Data (called "ticks") comes in from exchanges like Binance. The validator checks each tick for problems: Is the price sensible? Did anything arrive out of order? Is the feed still alive? Is the data arriving fast enough?

The results are shown on a web dashboard in real time. Testers, engineers, and traders can see at a glance whether the data feed is healthy or has problems.

---

### 1.2 How the System Works

```
Live Exchange (e.g. Binance)
        │
        ▼  WebSocket connection
  Feed Adapter (translates raw JSON into ticks)
        │
        ▼
  BackpressureQueue (buffer of 10,000 ticks; drops oldest when full)
        │
        ▼
  ValidatorEngine (fans out to 8 validators simultaneously)
        │
  ┌─────┼──────────────────────────┐
  │     │                          │
  ▼     ▼                          ▼
SSE   AlertGenerator          SessionRecorder
Stream  (writes alerts to DB)   (saves ticks to DB)
  │
  ▼
React Dashboard (browser)
```

**In plain English:**
1. The app connects to a data source (a real exchange, or the built-in simulator)
2. Each price update ("tick") flows through 8 validators at once
3. The validators produce PASS / WARN / FAIL results
4. Results stream to your browser in real time via SSE (Server-Sent Events)
5. Alerts are saved when something goes wrong
6. Sessions can record ticks and be replayed later for analysis

---

### 1.3 Glossary of Terms

| Term | Plain English Meaning |
|------|-----------------------|
| **Tick** | One price update from an exchange. Example: "BTCUSDT just traded at $68,572 at 10:00:00.123" |
| **Feed** | A connection to a data source (e.g. Binance WebSocket). Multiple feeds can be running simultaneously. |
| **Validator** | A check that runs on every incoming tick. There are 8 validators, each checking something different. |
| **PASS** | The validator is healthy — no problems detected. Shown in green. |
| **WARN** | The validator has detected something suspicious but not critical. Shown in yellow/orange. |
| **FAIL** | The validator has detected a real problem. Shown in red. |
| **SSE** | Server-Sent Events. A way for the server to push live updates to your browser without you needing to refresh. |
| **Session** | A named recording of ticks over a time period, saved to the database for later replay. |
| **Simulator (LVWR_T)** | A built-in fake feed that generates artificial ticks. Used for testing without needing a real exchange connection. |
| **Latency** | The time delay between when an exchange sends a tick and when this app receives it. Measured in milliseconds (ms). |
| **Sequence number (seqNum)** | A counter attached to each tick. Normally increases by 1 each time (1, 2, 3...). A jump (1, 2, 500) indicates missing ticks. |
| **p50 / p95 / p99** | Percentiles. p95 = 95% of ticks arrive within this many milliseconds. A higher number = slower. |
| **OHLC** | Open, High, Low, Close — the four price points tracked per trading session for each symbol. |
| **VWAP** | Volume-Weighted Average Price. A mathematical check that the average price makes sense given the trading volumes. |
| **API** | Application Programming Interface — a URL you can call programmatically to get or send data. |
| **curl** | A command-line tool for making HTTP requests. Used in this guide to test API endpoints directly. |
| **Feed ID** | A unique identifier (UUID) for each feed connection, like `7cdebc91-b49f-4463-b395-1048d6bf8fb2`. |
| **Adapter** | The code that translates a specific exchange's data format into the common tick format. Types: BINANCE, FINNHUB, GENERIC, LVWR_T. |

---

### 1.4 Prerequisites

Before you can run this application, you need the following installed on your machine:

1. **Java 21 or higher**
   - Check: open a terminal and type `java --version`
   - You should see something like: `openjdk 21.0.x`

2. **Node.js 18 or higher** (for the frontend)
   - Check: `node --version`
   - You should see: `v18.x.x` or higher

3. **Git** (to clone the project)
   - Check: `git --version`

4. **curl** (for API testing)
   - On Mac/Linux it is built in. Check: `curl --version`
   - On Windows, install from https://curl.se/

5. **A web browser** (Chrome or Firefox recommended)

6. **A terminal / command prompt**

---

### 1.5 How to Start the Application

#### Step 1 — Navigate to the project folder

```bash
cd /path/to/market-data-validator
```

#### Step 2 — Start the Backend (Java / Spring Boot)

Open a terminal window and run:

```bash
cd backend
./mvnw spring-boot:run
```

On Windows:
```cmd
cd backend
mvnw.cmd spring-boot:run
```

**What you will see:** A lot of log output. Look for this line near the end:
```
Started StreamValidatorApplication in X.XXX seconds
```

The backend runs on **port 8082**.

> **Note:** The first time you run this, Maven downloads dependencies. This can take 2–5 minutes. Subsequent starts take about 5 seconds.

#### Step 3 — Start the Frontend (React / Vite)

Open a **second** terminal window (keep the backend running) and run:

```bash
cd frontend
npm install       # only needed first time
npm run dev
```

**What you will see:**
```
VITE v5.x.x  ready in XXX ms
➜  Local:   http://localhost:5174/
```

The frontend runs on **port 5174**.

#### Step 4 — Open the Dashboard

Open your browser and go to: **http://localhost:5174**

---

### 1.6 Verifying the App is Running Before Testing

Run these checks before starting any tests. If any of these fail, do not proceed.

**Check 1 — Backend health:**
```bash
curl http://localhost:8082/api/feeds
```
**Expected:** A JSON array (may be empty `[]` or contain feed objects). You should NOT see a "connection refused" error.

**Check 2 — Frontend loads:**
Open http://localhost:5174 in your browser.
**Expected:** A page with the title "Market Data Stream Validator" and a sidebar with tabs: 📡 Live Feed, 🔌 Connections, 📊 Validation, 📈 Latency, 💾 Sessions, 🔔 Alerts, 🧪 Simulator, ⚖️ Compliance.

**Check 3 — Validation summary responds:**
```bash
curl http://localhost:8082/api/validation/summary
```
**Expected:** A JSON object with keys: `results`, `overallStatus`, `timestamp`, `ticksProcessed`.

---

## 2. Smoke Tests

> Run these first. They take under 2 minutes. If any smoke test fails, stop and investigate before running deeper tests.

### TEST S-01: Dashboard Page Loads

1. Open your browser and navigate to http://localhost:5174
2. Wait up to 5 seconds for the page to fully load

**Expected Result (PASS):**
- [ ] The page title "Market Data Stream Validator" is visible at the top
- [ ] The left sidebar shows 8 navigation tabs
- [ ] The 🧪 Simulator and ⚖️ Compliance tabs show a "NEW" badge
- [ ] No error messages or blank white screens appear

**Failure Result (FAIL):**
- The page shows "Cannot connect to server" → The frontend is not running. Re-run `npm run dev`.
- The page loads but shows no data at all → The backend may be down. Check `curl http://localhost:8082/api/feeds`.

---

### TEST S-02: At Least One Feed Is Connected

1. Click the **🔌 Connections** tab in the sidebar
2. Look at the list of feeds shown

**Expected Result (PASS):**
- [ ] At least one feed is listed (if this is a fresh install, the list may be empty — that is also acceptable for this smoke test)
- [ ] If feeds exist, they show a status (CONNECTED or DISCONNECTED)

---

### TEST S-03: Validation Tab Shows Validator Cards

1. Click the **📊 Validation** tab
2. Look at the cards displayed on the page

**Expected Result (PASS):**
- [ ] 8 status cards are visible, one for each validator: ACCURACY, LATENCY, COMPLETENESS, ORDERING, THROUGHPUT, RECONNECTION, SUBSCRIPTION, STATEFUL
- [ ] Each card shows either a green PASS, yellow WARN, or red FAIL badge
- [ ] A "Overall Status" indicator is visible at the top

**Failure Result (FAIL):**
- No cards visible → SSE connection failed. Check browser console (F12) for errors.

---

### TEST S-04: SSE Connection Is Live

1. Click the **📡 Live Feed** tab
2. Look at the top of the panel for a connection indicator

**Expected Result (PASS):**
- [ ] The indicator shows "Connected" or a green dot
- [ ] If any feed is connected, ticks begin appearing in the list within a few seconds
- [ ] If no feeds are connected, the list is empty but no error is shown

---

### TEST S-05: Backend API Responds

Run these commands in your terminal:

```bash
curl -s http://localhost:8082/api/feeds
curl -s http://localhost:8082/api/alerts/count
curl -s http://localhost:8082/api/sessions
```

**Expected Result (PASS):**
- [ ] First command returns a JSON array (e.g. `[]` or `[{...}]`)
- [ ] Second command returns a number (e.g. `0` or `3`)
- [ ] Third command returns a JSON array

**Failure Result (FAIL):**
- Any command returns `curl: (7) Failed to connect` → Backend is not running.

---

## 3. Feed Management Tests

> A **feed** is a connection to a data source. This section tests adding, connecting, updating, and removing feeds.

---

### TEST F-01: Add a New Feed (Valid URL)

1. Open a terminal and run:
```bash
curl -s -X POST http://localhost:8082/api/feeds \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test ETH Feed",
    "url": "wss://stream.binance.com:9443/ws/ethusdt@trade",
    "adapterType": "BINANCE",
    "symbols": ["ETHUSDT"]
  }'
```

2. Note the `id` value in the response (you will need it for later tests).

**Expected Result (PASS):**
- [ ] HTTP response code is `201 Created`
- [ ] Response body contains the feed with status `"DISCONNECTED"`
- [ ] The `id` field contains a UUID string (e.g. `"d60fcbd3-44c2-41fc-8140-9433ed066aca"`)

**Expected response body example:**
```json
{
  "id": "d60fcbd3-44c2-41fc-8140-9433ed066aca",
  "name": "Test ETH Feed",
  "url": "wss://stream.binance.com:9443/ws/ethusdt@trade",
  "adapterType": "BINANCE",
  "symbols": ["ETHUSDT"],
  "status": "DISCONNECTED",
  "connectedAt": null,
  "lastTickAt": null,
  "tickCount": 0
}
```

**Failure Result (FAIL):**
- HTTP 400 → Check that your JSON is valid and the URL starts with `wss://`
- HTTP 500 → Check the backend log for errors

---

### TEST F-02: Connect a Feed

Using the feed ID from TEST F-01 (replace `YOUR_FEED_ID` below):

```bash
curl -s -X POST http://localhost:8082/api/feeds/YOUR_FEED_ID/start
```

Wait 3–5 seconds for the connection to establish, then check:

```bash
curl -s http://localhost:8082/api/feeds | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] After 3–5 seconds, the feed's `status` field changes to `"CONNECTED"`
- [ ] The `connectedAt` field is set to a recent timestamp
- [ ] The `tickCount` begins increasing within seconds (if a real exchange is accessible)

**Failure Result (FAIL):**
- Status stays `"DISCONNECTED"` → Possible network issue. Check that the Binance WebSocket URL is reachable from your network.
- HTTP 400 "Connection already active" → The feed was already started.

---

### TEST F-03: Disconnect a Feed

```bash
curl -s -X POST http://localhost:8082/api/feeds/YOUR_FEED_ID/stop
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] Response body shows `"status": "DISCONNECTED"`
- [ ] The feed stops receiving ticks

**Failure Result (FAIL):**
- HTTP 400 "Connection already stopped" → Feed was already stopped.

---

### TEST F-04: Rename / Update a Feed

```bash
curl -s -X PUT http://localhost:8082/api/feeds/YOUR_FEED_ID \
  -H "Content-Type: application/json" \
  -d '{"name": "Renamed ETH Feed"}'
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] Response body shows `"name": "Renamed ETH Feed"`

---

### TEST F-05: Delete a Feed

```bash
curl -s -X DELETE http://localhost:8082/api/feeds/YOUR_FEED_ID \
  -w "HTTP %{http_code}\n"
```

Verify the feed is gone:
```bash
curl -s http://localhost:8082/api/feeds
```

**Expected Result (PASS):**
- [ ] HTTP response code is `204 No Content` (empty body is correct)
- [ ] The feed no longer appears in the list

---

### TEST F-06: Add Feed with Invalid URL (Not WebSocket)

```bash
curl -s -X POST http://localhost:8082/api/feeds \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bad Feed",
    "url": "http://stream.binance.com/ws",
    "adapterType": "BINANCE",
    "symbols": []
  }'
```

**Expected Result (PASS — the error is the correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body contains `"error": "URL must use ws:// or wss:// scheme"`

---

### TEST F-07: SSRF Protection — Private IP Address

Attempt to add a feed pointing to a private network address:

```bash
curl -s -X POST http://localhost:8082/api/feeds \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SSRF Test",
    "url": "wss://192.168.1.1:9999/feed",
    "adapterType": "GENERIC",
    "symbols": []
  }'
```

**Expected Result (PASS — the error is the correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body contains `"error": "Private/internal IP addresses are not allowed"`

---

### TEST F-08: SSRF Protection — Localhost

```bash
curl -s -X POST http://localhost:8082/api/feeds \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Loopback Test",
    "url": "wss://localhost:8082/internal",
    "adapterType": "GENERIC",
    "symbols": []
  }'
```

**Expected Result (PASS — the error is the correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body contains `"error": "Loopback addresses are not allowed"`

---

### TEST F-09: SSRF Protection — Non-WebSocket Scheme

```bash
curl -s -X POST http://localhost:8082/api/feeds \
  -H "Content-Type: application/json" \
  -d '{
    "name": "File SSRF",
    "url": "file:///etc/passwd",
    "adapterType": "GENERIC",
    "symbols": []
  }'
```

**Expected Result (PASS — the error is the correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body contains `"error": "URL must use ws:// or wss:// scheme"`

---

### TEST F-10: SSRF Protection — Unresolvable Hostname

```bash
curl -s -X POST http://localhost:8082/api/feeds \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Fake Host",
    "url": "wss://this-hostname-does-not-exist-xyz-abc.invalid/stream",
    "adapterType": "GENERIC",
    "symbols": []
  }'
```

**Expected Result (PASS — the error is the correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body contains `"error": "URL rejected: hostname could not be resolved"`

---

### TEST F-11: Subscribe to Symbols on a Connected Feed

First, ensure you have a connected LVWR_T simulator feed (see Section 4 to start one). Then:

```bash
curl -s -X POST http://localhost:8082/api/feeds/YOUR_FEED_ID/subscribe \
  -H "Content-Type: application/json" \
  -d '{"symbols": ["BTCUSDT", "ETHUSDT"]}'
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] Response shows `"symbols"` array now contains `"BTCUSDT"` and `"ETHUSDT"`

---

### TEST F-12: Unsubscribe from Symbols

```bash
curl -s -X POST http://localhost:8082/api/feeds/YOUR_FEED_ID/unsubscribe \
  -H "Content-Type: application/json" \
  -d '{"symbols": ["ETHUSDT"]}'
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] Response shows `"symbols"` array no longer contains `"ETHUSDT"`

---

### TEST F-13: Subscribe Without Symbols Array (Validation Error)

```bash
curl -s -X POST http://localhost:8082/api/feeds/YOUR_FEED_ID/subscribe \
  -H "Content-Type: application/json" \
  -d '{}'
```

**Expected Result (PASS — the error is the correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body contains `"error": "Request must include 'symbols' array"`

---

### TEST F-14: Update URL While Connected (Should Be Rejected)

1. Make sure the feed is in CONNECTED status
2. Attempt to change its URL:

```bash
curl -s -X PUT http://localhost:8082/api/feeds/YOUR_FEED_ID \
  -H "Content-Type: application/json" \
  -d '{"url": "wss://stream.binance.com:9443/ws/btcusdt@trade"}'
```

**Expected Result (PASS — the error is the correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body contains `"error": "Stop connection before changing URL"`

---

## 4. Simulator Tests

> The **LVWR_T simulator** is a built-in fake market data feed. It generates artificial ticks without needing a real exchange connection. This is the primary tool for testing the validators.
>
> **Why a simulator?** Real exchanges are not always predictable. The simulator lets you inject specific failures on demand, with precise timing.

---

### 4.1 Understanding the Simulator

The simulator has 4 modes:

| Mode | Behaviour | When to Use |
|------|-----------|-------------|
| **CLEAN** | All ticks are perfect — no errors | Baseline: all 8 validators should show PASS |
| **NOISY** | 10–20% of ticks have random faults | Mild stress test |
| **CHAOS** | 50% of ticks have faults, cycling through all 12 fault types | Heavy stress test |
| **SCENARIO** | Only one specific fault type fires | Testing one validator in isolation |

---

### TEST SIM-01: Start the LVWR_T Simulator

First, find the LVWR_T feed ID:

```bash
curl -s http://localhost:8082/api/feeds | python3 -m json.tool
```

Look for the entry where `"adapterType": "LVWR_T"`. Note its `id`.

If no LVWR_T feed exists, create one:
```bash
curl -s -X POST http://localhost:8082/api/feeds \
  -H "Content-Type: application/json" \
  -d '{"name": "LVWR Simulator", "url": "lvwr://simulator", "adapterType": "LVWR_T", "symbols": []}'
```

Start the simulator (replace `LVWR_ID` with the actual UUID):
```bash
export LVWR_ID="your-lvwr-feed-uuid-here"

curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/start \
  -H "Content-Type: application/json" \
  -d '{"mode": "CLEAN", "ticksPerSecond": 50}'
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] Response shows `"status": "CONNECTED"`

Verify it is running:
```bash
curl -s "http://localhost:8082/api/simulator/status?connectionId=$LVWR_ID" | python3 -m json.tool
```

- [ ] `"running": true`
- [ ] `"mode": "CLEAN"`
- [ ] `"ticksSent"` is a positive and increasing number

---

### TEST SIM-02: CLEAN Mode — All Validators Should Pass

1. Ensure the simulator is running in CLEAN mode (from TEST SIM-01)
2. Reset validators to clear any previous state:
```bash
curl -s -X POST http://localhost:8082/api/validation/reset
```
3. Wait 15 seconds
4. Check validation results:
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] `"overallStatus": "PASS"` (or at worst WARN for LATENCY due to simulator spike timing)
- [ ] ACCURACY: `"status": "PASS"`
- [ ] COMPLETENESS: `"status": "PASS"`
- [ ] ORDERING: `"status": "PASS"`
- [ ] STATEFUL: `"status": "PASS"`

> **Note on LATENCY:** The simulator may show LATENCY = FAIL due to spike detection. This is expected simulator behaviour — it injects occasional high-latency ticks. A LATENCY FAIL in CLEAN mode with all other validators PASS is acceptable.

---

### TEST SIM-03: Switch to NOISY Mode

```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "NOISY", "failureRate": 0.2, "ticksPerSecond": 50}'
```

Wait 10 seconds, then check the simulator status:
```bash
curl -s "http://localhost:8082/api/simulator/status?connectionId=$LVWR_ID" | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] `"mode": "NOISY"`
- [ ] `"failuresInjected"` map shows multiple non-zero failure counts after 10 seconds
- [ ] At least 2 different failure types have been injected (e.g. SEQUENCE_GAP, PRICE_SPIKE)

---

### TEST SIM-04: Switch to CHAOS Mode

```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "CHAOS", "ticksPerSecond": 50}'
```

Wait 15 seconds, then check:
```bash
curl -s "http://localhost:8082/api/simulator/status?connectionId=$LVWR_ID" | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] `"mode": "CHAOS"`
- [ ] All 12 failure types in `"failuresInjected"` have a count greater than 0
- [ ] Checking `/api/validation/summary` shows multiple validators in WARN or FAIL state

---

### TEST SIM-05: Test Each SCENARIO Mode Failure Type

For each of the 12 failure types below, follow this procedure:

**Procedure for each scenario:**
1. Reset validators: `curl -s -X POST http://localhost:8082/api/validation/reset`
2. Set SCENARIO mode with the target failure:
```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "SCENARIO", "targetScenario": "FAILURE_NAME_HERE", "failureRate": 1.0, "ticksPerSecond": 50}'
```
3. Wait 10–15 seconds
4. Check results at `/api/validation/summary`

---

#### SIM-05-A: SEQUENCE_GAP

Configure:
```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "SCENARIO", "targetScenario": "SEQUENCE_GAP", "failureRate": 1.0, "ticksPerSecond": 50}'
```

**What it injects:** Skips 50,000–500,000 sequence numbers (e.g. goes from seq 100 to seq 500,100)

**Expected Result (PASS):**
- [ ] After 15s, COMPLETENESS validator shows `"status": "FAIL"` or `"WARN"`
- [ ] `"details".gapEventCount` is greater than 0
- [ ] `"details".missingSequenceCount` is a very large number

---

#### SIM-05-B: DUPLICATE_TICK

Configure with `"targetScenario": "DUPLICATE_TICK"`

**What it injects:** Same sequence number emitted 2–3 times in a row

**Expected Result (PASS):**
- [ ] ORDERING validator shows `"status": "WARN"` or `"FAIL"` (duplicate seqNum detected)
- [ ] COMPLETENESS `outOfOrderCount` or `gapEventCount` may increase

---

#### SIM-05-C: OUT_OF_ORDER

Configure with `"targetScenario": "OUT_OF_ORDER"`

**What it injects:** A tick's sequence number is lower than the previous one by 5–20

**Expected Result (PASS):**
- [ ] ORDERING validator shows `"status": "WARN"` or `"FAIL"`
- [ ] `"details".outOfOrderCount` is greater than 0

---

#### SIM-05-D: STALE_TIMESTAMP

Configure with `"targetScenario": "STALE_TIMESTAMP"`

**What it injects:** The exchange timestamp on the tick is set 5 seconds in the past, making latency appear as 5000ms+

**Expected Result (PASS):**
- [ ] LATENCY validator shows `"status": "WARN"` or `"FAIL"`
- [ ] `"details".p95` is a high value (500ms+)

---

#### SIM-05-E: MALFORMED_PAYLOAD

Configure with `"targetScenario": "MALFORMED_PAYLOAD"`

**What it injects:** A null tick is returned — the tick is silently dropped

**Expected Result (PASS):**
- [ ] COMPLETENESS validator may show WARN due to gaps from dropped ticks
- [ ] The simulator continues running (does not crash)

---

#### SIM-05-F: SYMBOL_MISMATCH

Configure with `"targetScenario": "SYMBOL_MISMATCH"`

**What it injects:** The aggregate instrument (ID 126) carries a wrong source instrument ID

**Expected Result (PASS):**
- [ ] STATEFUL validator shows `"status": "WARN"` or `"FAIL"`
- [ ] `"details".violationCount` is greater than 0

---

#### SIM-05-G: NEGATIVE_PRICE

Configure with `"targetScenario": "NEGATIVE_PRICE"`

**What it injects:** Price set to `-0.125` (negative, which is physically impossible)

**Expected Result (PASS):**
- [ ] ACCURACY validator shows `"status": "WARN"` or `"FAIL"`
- [ ] `"details".invalidPriceCount` is greater than 0

---

#### SIM-05-H: PRICE_SPIKE

Configure with `"targetScenario": "PRICE_SPIKE"`

**What it injects:** Price jumps more than 14% in a single tick

**Expected Result (PASS):**
- [ ] ACCURACY validator shows `"status": "WARN"` or `"FAIL"`
- [ ] `"details".largeMoveCount` is greater than 0

---

#### SIM-05-I: DISCONNECT

Configure with `"targetScenario": "DISCONNECT"`:
```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "SCENARIO", "targetScenario": "DISCONNECT", "failureRate": 0.5, "disconnectDurationMs": 9000, "ticksPerSecond": 50}'
```

Wait 30 seconds for the pause to fire.

**What it injects:** The simulator stops sending ticks for ~9 seconds

**Expected Result (PASS):**
- [ ] THROUGHPUT validator shows `"status": "WARN"` or `"FAIL"` during the pause
- [ ] `"details".consecutiveZeroSeconds` reaches 5 or more
- [ ] After the pause ends, THROUGHPUT may recover

---

#### SIM-05-J: RECONNECT_STORM

Configure with `"targetScenario": "RECONNECT_STORM"`

**What it injects:** Simulates 5 rapid disconnects and reconnects

**Expected Result (PASS):**
- [ ] RECONNECTION validator shows `"status": "WARN"` or `"FAIL"` (multiple disconnect events)
- [ ] `"details".disconnectCount` increases

---

#### SIM-05-K: THROTTLE_BURST

Configure with `"targetScenario": "THROTTLE_BURST"`

**What it injects:** 800 ticks with the same timestamp burst through at once

**Expected Result (PASS):**
- [ ] THROUGHPUT validator may show irregular `messagesPerSecond` spikes
- [ ] The BackpressureQueue handles the burst without crashing
- [ ] The app continues running after the burst

---

#### SIM-05-L: CUMVOL_BACKWARD

Configure with `"targetScenario": "CUMVOL_BACKWARD"`

**What it injects:** Cumulative volume decreases from the previous value (physically impossible — volume can only increase)

**Expected Result (PASS):**
- [ ] STATEFUL validator shows `"status": "WARN"` or `"FAIL"`
- [ ] `"details".violationCount` is greater than 0

---

### TEST SIM-06: Reset Back to CLEAN Mode

After testing all scenarios, reset to clean baseline:

```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "CLEAN", "failureRate": 0.1, "ticksPerSecond": 50}'
```

Then reset validators:
```bash
curl -s -X POST http://localhost:8082/api/validation/reset
```

**Expected Result (PASS):**
- [ ] `"mode": "CLEAN"` confirmed in status response
- [ ] After 15 seconds, most validators return to PASS

---

### TEST SIM-07: List All Available Scenarios

```bash
curl -s http://localhost:8082/api/simulator/scenarios | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] Returns a JSON array of exactly **12 entries**
- [ ] Each entry has a `"name"` and `"description"` field
- [ ] Names include: SEQUENCE_GAP, DUPLICATE_TICK, OUT_OF_ORDER, STALE_TIMESTAMP, MALFORMED_PAYLOAD, SYMBOL_MISMATCH, NEGATIVE_PRICE, PRICE_SPIKE, DISCONNECT, RECONNECT_STORM, THROTTLE_BURST, CUMVOL_BACKWARD

---

### TEST SIM-08: Stop the Simulator

```bash
curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/stop
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] Response shows `"status": "DISCONNECTED"`

Verify in the simulator status:
```bash
curl -s "http://localhost:8082/api/simulator/status?connectionId=$LVWR_ID" | python3 -m json.tool
```

- [ ] `"running": false`

---

## 5. Validator Tests

> This section tests each of the 8 validators individually. Before each test, start the simulator and reset validators.

**Setup before each validator section:**
```bash
# Start simulator in CLEAN mode
curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/start \
  -H "Content-Type: application/json" \
  -d '{"mode": "CLEAN", "ticksPerSecond": 100}'

# Reset validators
curl -s -X POST http://localhost:8082/api/validation/reset
```

---

### 5.1 ACCURACY Validator

**What this validator checks (plain English):**
> Every price update must be physically valid: prices must be positive, bid price must be less than or equal to ask price, and prices must not jump by more than 10% in a single tick.

**Thresholds:**
- PASS: 99.99% or more of ticks are accurate
- WARN: 99.0% – 99.99% of ticks are accurate
- FAIL: below 99.0% of ticks are accurate

---

#### TEST V-ACC-01: Make ACCURACY PASS

1. Ensure simulator is running in CLEAN mode
2. Reset validators
3. Wait 15 seconds
4. Check results:
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['results'].get('ACCURACY', {})
print('Status:', r.get('status'))
print('Message:', r.get('message'))
print('Details:', r.get('details'))
"
```

**Expected Result (PASS):**
- [ ] `"status": "PASS"`
- [ ] `accuracyRate` is `100.0` or close to it
- [ ] `invalidPriceCount` is `0`
- [ ] `bidAskViolations` is `0`
- [ ] `largeMoveCount` is `0`

---

#### TEST V-ACC-02: Make ACCURACY WARN or FAIL — Via NEGATIVE_PRICE Scenario

1. Set simulator to NEGATIVE_PRICE scenario with high rate:
```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "SCENARIO", "targetScenario": "NEGATIVE_PRICE", "failureRate": 0.1, "ticksPerSecond": 100}'
```
2. Reset validators, wait 20 seconds
3. Check ACCURACY results

**Expected Result (PASS):**
- [ ] `"status"` is `"WARN"` or `"FAIL"` (not PASS)
- [ ] `invalidPriceCount` is greater than `0`

---

#### TEST V-ACC-03: Make ACCURACY WARN or FAIL — Via PRICE_SPIKE Scenario

1. Set simulator to PRICE_SPIKE with `failureRate: 0.5`
2. Reset validators, wait 20 seconds
3. Check ACCURACY results

**Expected Result (PASS):**
- [ ] `"status"` is `"WARN"` or `"FAIL"`
- [ ] `largeMoveCount` is greater than `0`

---

#### TEST V-ACC-04: Common False Positive — Feed Reconnect Gap

**Understanding:** When a feed disconnects and reconnects, the price may have moved a lot during the gap. The ACCURACY validator has a 60-second reconnect gap protection: if a symbol hasn't been seen for 60+ seconds, the first tick after reconnect does NOT trigger a large-move alert.

**Test:**
1. Start simulator in CLEAN mode
2. Let it run for 30 seconds
3. Stop the simulator: `curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/stop`
4. Wait 65 seconds
5. Restart simulator: `curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/start -H "Content-Type: application/json" -d '{"mode":"CLEAN","ticksPerSecond":50}'`
6. Wait 10 seconds, check ACCURACY

**Expected Result (PASS):**
- [ ] ACCURACY does NOT jump to FAIL because of the reconnect gap
- [ ] `largeMoveCount` stays the same as before the gap

---

### 5.2 LATENCY Validator

**What this validator checks (plain English):**
> How long does it take from when the exchange creates a tick to when this app receives it? It tracks this in percentiles. If the median (p50) is 50ms, it means 50% of ticks arrive within 50ms.

**Thresholds (with default 500ms threshold):**
- PASS: p95 < 500ms AND fewer than 5 spikes
- WARN: p95 < 1000ms OR 5–19 spikes
- FAIL: p95 ≥ 1000ms OR 20+ spikes

**A "spike"** = one tick whose latency is more than 3× the p99 (99th percentile)

---

#### TEST V-LAT-01: View Current Latency Stats

```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['results'].get('LATENCY', {})
print('Status:', r.get('status'))
print('Message:', r.get('message'))
det = r.get('details', {})
print('p50:', det.get('p50'), 'ms')
print('p95:', det.get('p95'), 'ms')
print('p99:', det.get('p99'), 'ms')
print('min:', det.get('min'), 'ms')
print('max:', det.get('max'), 'ms')
print('Spikes:', det.get('spikeCount'))
"
```

**Expected Result (PASS — CLEAN mode):**
- [ ] `p95` is less than 500 (the default threshold)
- [ ] `spikeCount` is less than 5 (for clean PASS) OR the app tolerates some spikes from the simulator

---

#### TEST V-LAT-02: Make LATENCY FAIL — Via STALE_TIMESTAMP Scenario

1. Set simulator to STALE_TIMESTAMP with `failureRate: 1.0`
2. Reset validators, wait 20 seconds
3. Check LATENCY results

**Expected Result (PASS):**
- [ ] `"status"` is `"WARN"` or `"FAIL"`
- [ ] `p95` is much higher than 500ms (the stale timestamp adds ~5000ms)

---

#### TEST V-LAT-03: Change Latency Threshold

```bash
# Set a very strict threshold of 50ms
curl -s -X PUT http://localhost:8082/api/validation/config \
  -H "Content-Type: application/json" \
  -d '{"area": "LATENCY", "config": {"thresholdMs": 50}}'
```

Wait 10 seconds and check LATENCY. If the simulator produces ticks with latency > 50ms, LATENCY should now show WARN or FAIL.

**Expected Result (PASS):**
- [ ] `"message": "Configuration updated"` returned from PUT
- [ ] After 10 seconds, LATENCY may show `"status": "WARN"` if p95 > 50ms

Reset threshold:
```bash
curl -s -X PUT http://localhost:8082/api/validation/config \
  -H "Content-Type: application/json" \
  -d '{"area": "LATENCY", "config": {"thresholdMs": 500}}'
```

---

### 5.3 COMPLETENESS Validator

**What this validator checks (plain English):**
> Every tick has a sequence number (like a ticket number). If numbers skip (1, 2, 500, 501), we know we missed ticks 3–499. This validator counts missing ticks and also detects symbols that go silent for more than 10 seconds.

**Thresholds:**
- PASS: 99.99%+ complete AND no stale symbols
- WARN: 99.0%–99.99% complete
- FAIL: below 99.0% complete OR any stale symbols

---

#### TEST V-COM-01: Make COMPLETENESS PASS

1. Start simulator in CLEAN mode
2. Reset validators, wait 15 seconds
3. Check COMPLETENESS

**Expected Result (PASS):**
- [ ] `"status": "PASS"`
- [ ] `gapEventCount` is `0`
- [ ] `staleSymbolCount` is `0`
- [ ] `completenessRate` is `100.0`

---

#### TEST V-COM-02: Make COMPLETENESS FAIL — Via SEQUENCE_GAP

```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "SCENARIO", "targetScenario": "SEQUENCE_GAP", "failureRate": 1.0, "ticksPerSecond": 50}'
```

Reset validators, wait 15 seconds, check COMPLETENESS.

**Expected Result (PASS):**
- [ ] `"status": "FAIL"`
- [ ] `gapEventCount` is greater than 0
- [ ] `missingSequenceCount` is a very large number (50,000+)
- [ ] `completenessRate` is near 0%

---

#### TEST V-COM-03: Stale Symbol Detection

1. Start simulator in CLEAN mode, wait for it to produce ticks for 15 seconds
2. Stop the simulator: `curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/stop`
3. Wait 12 seconds (longer than the 10-second stale threshold)
4. Check COMPLETENESS:
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['results'].get('COMPLETENESS', {})
print('Status:', r.get('status'))
det = r.get('details', {})
print('Stale symbols:', det.get('staleSymbols', []))
print('Stale count:', det.get('staleSymbolCount'))
"
```

**Expected Result (PASS):**
- [ ] `"status": "FAIL"` (stale symbols detected)
- [ ] `staleSymbols` list is non-empty
- [ ] `staleSymbolCount` is greater than 0

---

#### TEST V-COM-04: Common False Positive — Binance Sequence Gaps

**Understanding:** The Binance exchange uses a global sequence number counter that is shared across many streams. If you connect to only one symbol stream (e.g. `btcusdt@trade`), the sequence numbers may skip because other symbol streams are using those numbers. This is **not** a real data loss — it is a known Binance API behaviour.

**What to do:** If COMPLETENESS shows FAIL or WARN when using a real Binance feed (not the simulator), check whether the gaps are from a single-symbol filtered stream. This is a false positive.

---

### 5.4 ORDERING Validator

**What this validator checks (plain English):**
> Ticks should arrive in time order. If a tick timestamped at 10:00:02 arrives after a tick timestamped at 10:00:05, that is out-of-order and suspicious.

**Thresholds:**
- PASS: 99.99%+ in order
- WARN: 99.0%–99.99% in order
- FAIL: below 99.0% in order

---

#### TEST V-ORD-01: Make ORDERING PASS

1. Start simulator in CLEAN mode, wait 15 seconds
2. Check ORDERING

**Expected Result (PASS):**
- [ ] `"status": "PASS"`
- [ ] `outOfOrderCount` is `0`
- [ ] `orderingRate` is `100.0`

---

#### TEST V-ORD-02: Make ORDERING FAIL — Via OUT_OF_ORDER

```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "SCENARIO", "targetScenario": "OUT_OF_ORDER", "failureRate": 0.2, "ticksPerSecond": 100}'
```

Reset validators, wait 20 seconds, check ORDERING.

**Expected Result (PASS):**
- [ ] `"status": "WARN"` or `"FAIL"`
- [ ] `outOfOrderCount` is greater than 0

---

### 5.5 THROUGHPUT Validator

**What this validator checks (plain English):**
> How many ticks per second is the feed sending? If the rate drops by more than 50% compared to the recent average, something may be wrong. If no ticks arrive for 5+ consecutive seconds while the feed is still "connected", that is a serious failure.

**Thresholds:**
- PASS: throughput is stable (no large drops, no zero periods)
- WARN: throughput drops more than 50% below recent average
- FAIL: zero ticks for 5+ consecutive seconds while feed is connected

---

#### TEST V-THR-01: Make THROUGHPUT PASS

1. Start simulator at `ticksPerSecond: 100`, wait 15 seconds
2. Check THROUGHPUT

**Expected Result (PASS):**
- [ ] `"status": "PASS"`
- [ ] `messagesPerSecond` is approximately 100 (±20%)
- [ ] `rollingAverage` is approximately 100

---

#### TEST V-THR-02: Make THROUGHPUT FAIL — Via DISCONNECT Scenario

```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "SCENARIO", "targetScenario": "DISCONNECT", "failureRate": 1.0, "disconnectDurationMs": 8000, "ticksPerSecond": 100}'
```

Reset validators, wait 30 seconds (for the disconnect to fire and be detected), check THROUGHPUT.

**Expected Result (PASS):**
- [ ] `"status": "FAIL"` (during or after the 8-second pause)
- [ ] `consecutiveZeroSeconds` reaches 5 or more

---

### 5.6 RECONNECTION Validator

**What this validator checks (plain English):**
> When the connection to an exchange drops, does it reconnect successfully? How long does reconnection take? This validator tracks every disconnect/reconnect event.

**Thresholds:**
- PASS: All disconnects have been followed by successful reconnects, and reconnect time averaged under 5 seconds
- WARN: Some disconnects did not result in reconnects, OR reconnects are averaging over 5 seconds
- FAIL: Any "failed reconnect" (max retry attempts exhausted)

> **Important:** This validator is **event-driven**. It only changes when actual disconnect/reconnect events happen. It shows "No connection events yet" until the first disconnect occurs.

---

#### TEST V-REC-01: View Reconnection Status After RECONNECT_STORM

```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "SCENARIO", "targetScenario": "RECONNECT_STORM", "failureRate": 1.0, "ticksPerSecond": 50}'
```

Wait 30 seconds, check RECONNECTION:
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['results'].get('RECONNECTION', {})
print('Status:', r.get('status'))
print('Message:', r.get('message'))
"
```

**Expected Result (PASS):**
- [ ] `"status"` is something other than just "No connection events yet" (events have been logged)
- [ ] `disconnectCount` or `reconnectCount` is greater than 0

---

#### TEST V-REC-02: Reconnection PASS After Clean Reconnect

1. Stop the simulator feed: `curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/stop`
2. Reset validators: `curl -s -X POST http://localhost:8082/api/validation/reset`
3. Start it again within a few seconds: `curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/start`
4. Wait 5 seconds
5. Check RECONNECTION

**Expected Result (PASS):**
- [ ] `disconnectCount` = 1, `reconnectCount` = 1
- [ ] `"status": "PASS"` (one disconnect followed by one successful reconnect)

---

### 5.7 SUBSCRIPTION Validator

**What this validator checks (plain English):**
> When you subscribe to a symbol (say, "I want BTCUSDT data"), does the first tick arrive within 5 seconds? When you unsubscribe, do ticks actually stop? If a symbol keeps sending ticks 3+ seconds after unsubscribe, that is a "leaky unsubscribe."

**Thresholds:**
- PASS: All subscribed symbols receiving data, no leaky unsubscribes
- WARN: Some subscriptions timed out (no first tick in 5 seconds)
- FAIL: Leaky unsubscribes detected (data continues after unsubscription)

> **Note:** This validator primarily reacts to explicit subscribe/unsubscribe API calls. If no such calls are made, it stays at PASS with "All 0 subscribed symbols active."

---

#### TEST V-SUB-01: Subscribe and Verify Subscription

1. Make sure a feed is running
2. Call subscribe:
```bash
curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/subscribe \
  -H "Content-Type: application/json" \
  -d '{"symbols": ["BTCUSDT"]}'
```
3. Wait 10 seconds
4. Check SUBSCRIPTION

**Expected Result (PASS):**
- [ ] `"status": "PASS"`
- [ ] `subscribedSymbols` contains `"BTCUSDT"` (or the feed-scoped equivalent)
- [ ] `timedOutCount` is `0`

---

#### TEST V-SUB-02: Default State Without Subscriptions

With no explicit subscribe calls made, check SUBSCRIPTION:

**Expected Result (PASS):**
- [ ] `"status": "PASS"`
- [ ] Message says "All 0 subscribed symbols active"
- [ ] `subscribedCount` is `0`

---

### 5.8 STATEFUL Validator

**What this validator checks (plain English):**
> It maintains a running price history per symbol and checks internal consistency: the high price must always be ≥ the low, the current price must be within the high-low range, and cumulative volume must never decrease.

**Thresholds:**
- PASS: 99.99%+ consistent AND no stale symbols
- WARN: 99.9%–99.99% consistent OR ≤ 2 stale symbols
- FAIL: below 99.9% consistent OR more than 2 stale symbols

---

#### TEST V-STA-01: Make STATEFUL PASS

1. Start simulator in CLEAN mode, wait 15 seconds
2. Check STATEFUL:
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['results'].get('STATEFUL', {})
print('Status:', r.get('status'))
det = r.get('details', {})
print('Consistency:', det.get('consistencyRate'))
print('Tracked symbols:', det.get('trackedSymbols'))
"
```

**Expected Result (PASS):**
- [ ] `"status": "PASS"`
- [ ] `consistencyRate` is `100.0` or very close
- [ ] `violationCount` is `0`
- [ ] `trackedSymbols` is > 0 (simulator sends data for multiple symbols)

---

#### TEST V-STA-02: Make STATEFUL FAIL — Via CUMVOL_BACKWARD

```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "SCENARIO", "targetScenario": "CUMVOL_BACKWARD", "failureRate": 0.3, "ticksPerSecond": 100}'
```

Reset validators, wait 20 seconds, check STATEFUL.

**Expected Result (PASS):**
- [ ] `"status": "WARN"` or `"FAIL"`
- [ ] `violationCount` is greater than 0

---

## 6. Session Recording Tests

> A **session** is a named recording of ticks saved to the database. Sessions can be replayed later, exported as CSV or JSON, and used for post-mortem analysis.

---

### TEST SR-01: Start a Recording Session

Make sure the simulator is running first. Then:

```bash
# First, get a valid feed ID
FEED_ID=$(curl -s http://localhost:8082/api/feeds | python3 -c "
import sys, json
feeds = json.load(sys.stdin)
for f in feeds:
    if f['status'] == 'CONNECTED':
        print(f['id'])
        break
")
echo "Using feed: $FEED_ID"

# Start recording
curl -s -X POST http://localhost:8082/api/sessions/start \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"QA Test Session\", \"feedId\": \"$FEED_ID\"}" | python3 -m json.tool
```

Note the session `id` from the response.

**Expected Result (PASS):**
- [ ] HTTP response code is `201 Created`
- [ ] `"status": "RECORDING"`
- [ ] `"name": "QA Test Session"`
- [ ] `"tickCount": 0` (recording just started)

---

### TEST SR-02: Verify Ticks Are Being Recorded

Wait 10 seconds, then check the session:

```bash
curl -s http://localhost:8082/api/sessions | python3 -c "
import sys, json
sessions = json.load(sys.stdin)
for s in sessions:
    print(f'id={s[\"id\"]} name={s[\"name\"]} status={s[\"status\"]} ticks={s[\"tickCount\"]}')
"
```

**Expected Result (PASS):**
- [ ] Session with name "QA Test Session" shows `"status": "RECORDING"`
- [ ] `tickCount` is greater than `0` (ticks are being saved)

> **If tickCount stays 0:** This was a previous bug that has been fixed. If it stays 0, check that the simulator is actually running and connected (`status: CONNECTED`). Also check the backend logs for database errors.

---

### TEST SR-03: Stop a Recording Session

```bash
SESSION_ID=4  # Replace with the actual session ID from TEST SR-01

curl -s -X POST http://localhost:8082/api/sessions/$SESSION_ID/stop | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] `"status": "COMPLETED"`
- [ ] `"endedAt"` is set to a recent timestamp
- [ ] `"tickCount"` is greater than `0`
- [ ] `"byteSize"` is greater than `0`

**Failure Result (FAIL):**
- `"status": "FAILED"` → A database write error occurred during recording. Ticks were discarded to prevent retry storms. The session file is incomplete. This is **by design** — the fix intentionally marks sessions FAILED when data loss occurs rather than silently claiming they completed.

---

### TEST SR-04: List All Sessions

```bash
curl -s http://localhost:8082/api/sessions | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] Returns a JSON array
- [ ] Each session has: `id`, `name`, `feedId`, `status`, `startedAt`, `endedAt`, `tickCount`, `byteSize`
- [ ] Sessions are ordered newest first

---

### TEST SR-05: Export Session as CSV

```bash
curl -s "http://localhost:8082/api/sessions/$SESSION_ID/export?format=csv" | head -5
```

**Expected Result (PASS):**
- [ ] First line is a CSV header: `symbol,price,bid,ask,volume,sequenceNum,exchangeTimestamp,receivedTimestamp,feedId,correlationId`
- [ ] Subsequent lines contain tick data
- [ ] Number of data lines approximately equals `tickCount` from the session

---

### TEST SR-06: Export Session as JSON

```bash
curl -s "http://localhost:8082/api/sessions/$SESSION_ID/export?format=json" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('Keys:', list(d.keys()))
print('sessionId:', d.get('sessionId'))
print('name:', d.get('name'))
print('tickCount:', d.get('tickCount'))
print('ticks count:', len(d.get('ticks', [])))
"
```

**Expected Result (PASS):**
- [ ] Response is a JSON object (not an array)
- [ ] Keys include: `sessionId`, `name`, `feedId`, `startedAt`, `endedAt`, `tickCount`, `ticks`
- [ ] `ticks` is an array with length equal to `tickCount`

---

### TEST SR-07: Cannot Start Second Recording While One Is Active

1. Start a recording session (without stopping it)
2. Try to start another:
```bash
curl -s -X POST http://localhost:8082/api/sessions/start \
  -H "Content-Type: application/json" \
  -d '{"name": "Second Session", "feedId": "any-uuid"}'
```

**Expected Result (PASS — the error is correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Error message contains "Already recording session"

---

### TEST SR-08: Cannot Delete an Active Recording Session

```bash
curl -s -X DELETE http://localhost:8082/api/sessions/$SESSION_ID \
  -w "\nHTTP %{http_code}\n"
```
(while the session is RECORDING)

**Expected Result (PASS — the error is correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Error message contains "Stop recording first"

---

### TEST SR-09: Delete a Completed Session

1. First ensure the session is stopped (COMPLETED status)
2. Then delete:
```bash
curl -s -X DELETE http://localhost:8082/api/sessions/$SESSION_ID \
  -w "\nHTTP %{http_code}\n"
```

**Expected Result (PASS):**
- [ ] HTTP response code is `204 No Content` (empty body)
- [ ] Session no longer appears in `GET /api/sessions`

---

### TEST SR-10: Export Non-Existent Session

```bash
curl -s "http://localhost:8082/api/sessions/99999/export" \
  -w "\nHTTP %{http_code}\n"
```

**Expected Result (PASS — the error is correct behaviour):**
- [ ] HTTP response code is `404 Not Found`

---

## 7. Session Replay Tests

> **Replay** feeds a recorded session's ticks back through the validator engine. It is used to re-analyse historical data.

> **Important note on speed:** A `speed` of `1.0` means real-time (same timing as original). `speed=10.0` means 10 times faster. `speed=1000.0` is the maximum fast-forward (no timing delays).

---

### TEST REP-01: Replay a Session at Default Speed (1x)

First, make sure you have a completed session. Use one of the sessions from `GET /api/sessions`:

```bash
curl -s -X POST http://localhost:8082/api/sessions/1/replay | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('ticksReplayed:', d.get('ticksReplayed'))
print('Results:')
for r in d.get('results', []):
    print(f'  {r[\"area\"]}: {r[\"status\"]}')
"
```

> **Note:** For a 1x-speed replay, this command may take a long time if the session is long. Use the fastest speed (`speed=1000`) for quick tests.

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] `ticksReplayed` equals the `tickCount` of the session
- [ ] `results` array has 8 entries (one per validator)
- [ ] Each result has `area`, `status`, `message`, `metric`, `threshold`, `details`

---

### TEST REP-02: Replay at 5x Speed

```bash
curl -s -X POST "http://localhost:8082/api/sessions/1/replay?speed=5.0" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('ticksReplayed:', d.get('ticksReplayed'))
"
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] `ticksReplayed` is the same as in REP-01
- [ ] The command completes 5× faster than REP-01

---

### TEST REP-03: Replay at Maximum Speed (1000x)

```bash
curl -s -X POST "http://localhost:8082/api/sessions/1/replay?speed=1000" \
  -w "\nHTTP %{http_code}\n" | head -3
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] The replay completes almost instantly (no timing delays at 1000x)
- [ ] `ticksReplayed` is correct

---

### TEST REP-04: Replay Speed = 0 Should Be Rejected

```bash
curl -s -X POST "http://localhost:8082/api/sessions/1/replay?speed=0" | python3 -m json.tool
```

**Expected Result (PASS — the error is correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body contains `"error"` with text like "Replay speed must be between 0 and 1000"

---

### TEST REP-05: Replay Speed Above Maximum Should Be Rejected

```bash
curl -s -X POST "http://localhost:8082/api/sessions/1/replay?speed=1001" | python3 -m json.tool
```

**Expected Result (PASS — the error is correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body `"error"` contains "0 and 1000"

---

### TEST REP-06: Replay Non-Existent Session

```bash
curl -s -X POST http://localhost:8082/api/sessions/99999/replay \
  -w "\nHTTP %{http_code}\n"
```

**Expected Result (PASS — the error is correct behaviour):**
- [ ] HTTP response code is `404 Not Found`

---

### TEST REP-07: Validators React During Replay

1. Ensure you have a session recorded during CHAOS or NOISY mode (contains faults)
2. Reset validators: `curl -s -X POST http://localhost:8082/api/validation/reset`
3. Replay the session at 1000x speed
4. Immediately check validation results

**Expected Result (PASS):**
- [ ] At least one validator shows WARN or FAIL (reflecting the faults in the recorded session)
- [ ] The validator results are different from a clean-mode session replay

---

### TEST REP-08: Replay Result Contains All 8 Validators

```bash
curl -s -X POST "http://localhost:8082/api/sessions/1/replay?speed=1000" | python3 -c "
import sys, json
d = json.load(sys.stdin)
areas = [r['area'] for r in d.get('results', [])]
print('Areas found:', sorted(areas))
expected = ['ACCURACY','COMPLETENESS','LATENCY','ORDERING','RECONNECTION','STATEFUL','SUBSCRIPTION','THROUGHPUT']
missing = [e for e in expected if e not in areas]
print('Missing:', missing if missing else 'None')
"
```

**Expected Result (PASS):**
- [ ] All 8 validator areas are present in the results
- [ ] "Missing: None" is printed

---

## 8. Alert Tests

> **Alerts** are generated automatically when a validator transitions to WARN or FAIL. They are stored in the database and can be acknowledged (marked as "seen") or deleted.

---

### TEST AL-01: Generate Alerts Via CHAOS Mode

1. Start simulator in CHAOS mode (or SCENARIO mode with a failure that triggers a validator):
```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "CHAOS", "ticksPerSecond": 100}'
```
2. Reset validators, wait 30 seconds
3. Check for alerts:
```bash
curl -s http://localhost:8082/api/alerts | python3 -m json.tool | head -60
```

**Expected Result (PASS):**
- [ ] At least 1 alert is returned
- [ ] Each alert has: `id`, `area`, `severity`, `message`, `acknowledged`, `createdAt`
- [ ] `acknowledged` is `false` for new alerts
- [ ] `severity` is one of: `"INFO"`, `"WARN"`, `"CRITICAL"`

---

### TEST AL-02: View Unacknowledged Alert Count

```bash
curl -s http://localhost:8082/api/alerts/count
```

**Expected Result (PASS):**
- [ ] Returns a single integer (not an array)
- [ ] The number matches the number of unacknowledged alerts in TEST AL-01
- [ ] The **🔔 Alerts** tab in the UI shows a red badge with this count

---

### TEST AL-03: Acknowledge a Single Alert

1. Get an alert ID from TEST AL-01 (note the `id` value)
2. Acknowledge it:
```bash
ALERT_ID=73  # Replace with actual alert ID

curl -s -X POST http://localhost:8082/api/alerts/$ALERT_ID/acknowledge \
  -w "HTTP %{http_code}\n"
```
3. Verify:
```bash
curl -s http://localhost:8082/api/alerts | python3 -c "
import sys, json
alerts = json.load(sys.stdin)
for a in alerts:
    if a['id'] == $ALERT_ID:
        print('acknowledged:', a['acknowledged'])
"
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] The alert's `acknowledged` field is now `true`
- [ ] The unacknowledged count (from `/api/alerts/count`) has decreased by 1

---

### TEST AL-04: Acknowledge All Alerts

```bash
curl -s -X POST http://localhost:8082/api/alerts/acknowledge-all \
  -w "HTTP %{http_code}\n"
```

Then check count:
```bash
curl -s http://localhost:8082/api/alerts/count
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK`
- [ ] `count` is now `0` (all acknowledged)

---

### TEST AL-05: Delete a Single Alert

```bash
curl -s -X DELETE http://localhost:8082/api/alerts/$ALERT_ID \
  -w "HTTP %{http_code}\n"
```

**Expected Result (PASS):**
- [ ] HTTP response code is `204 No Content`
- [ ] Alert with that ID no longer appears in `GET /api/alerts`

---

### TEST AL-06: Delete All Alerts

```bash
curl -s -X DELETE http://localhost:8082/api/alerts \
  -w "HTTP %{http_code}\n"
```

Then verify:
```bash
curl -s http://localhost:8082/api/alerts
```

**Expected Result (PASS):**
- [ ] HTTP response code is `204 No Content`
- [ ] `GET /api/alerts` returns an empty array `[]`
- [ ] `GET /api/alerts/count` returns `0`

---

### TEST AL-07: Acknowledge Non-Existent Alert

```bash
curl -s -X POST http://localhost:8082/api/alerts/99999/acknowledge \
  -w "HTTP %{http_code}\n"
```

**Expected Result (PASS — the error is correct behaviour):**
- [ ] HTTP response code is `404 Not Found`

---

## 9. SSE Stream Tests

> **SSE (Server-Sent Events)** is a protocol where the server continuously pushes updates to your browser without you needing to request them. Think of it like a radio broadcast — you tune in and data flows to you.
>
> The app has 5 SSE endpoints. The frontend uses them to show live data without page refresh.
>
> **Note:** Standard `curl` struggles with chunked SSE responses. Use `nc` (netcat) for raw testing, as shown below.

---

### TEST SSE-01: Test /api/stream/ticks — Live Tick Stream

```bash
(echo -e "GET /api/stream/ticks HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 5) \
  | nc localhost 8082 2>/dev/null | head -40
```

**Expected Result (PASS):**
- [ ] HTTP response code is `200 OK` in the first line
- [ ] Header `Content-Type: text/event-stream` is present
- [ ] After 1–2 seconds, lines appear like `event:tick` followed by `data:{...json...}`
- [ ] Tick JSON contains fields: `symbol`, `price`, `latency`, `sequenceNum`, `exchangeTimestamp`, `receivedTimestamp`, `feedId`

---

### TEST SSE-02: Test /api/stream/ticks — Symbol Filter

```bash
(echo -e "GET /api/stream/ticks?symbol=BTCUSDT HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 5) \
  | nc localhost 8082 2>/dev/null | grep '"symbol"' | head -5
```

**Expected Result (PASS):**
- [ ] Every tick line that appears contains `"symbol":"BTCUSDT"` only
- [ ] No ticks from other symbols (ETHUSDT, etc.) appear

---

### TEST SSE-03: Test /api/stream/ticks — Invalid Symbol Filter

```bash
(echo -e "GET /api/stream/ticks?symbol=FAKEXYZ HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 5) \
  | nc localhost 8082 2>/dev/null | grep "^data:" | head -5
```

**Expected Result (PASS):**
- [ ] Connection is established (HTTP 200 + `:connected` heartbeat)
- [ ] No `data:` lines appear (no ticks match the fake symbol)
- [ ] The connection does NOT return a 404 or error — it silently returns no data

---

### TEST SSE-04: Test /api/stream/validation

```bash
(echo -e "GET /api/stream/validation HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 5) \
  | nc localhost 8082 2>/dev/null | grep -E "event:|\"area\"" | head -20
```

**Expected Result (PASS):**
- [ ] Events labelled `event:validation` appear
- [ ] Data contains `"area"` and `"status"` fields for each of the 8 validators
- [ ] Events arrive approximately every 250ms

---

### TEST SSE-05: Test /api/stream/latency

```bash
(echo -e "GET /api/stream/latency HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 5) \
  | nc localhost 8082 2>/dev/null | grep -E "event:|\"p95\"" | head -10
```

**Expected Result (PASS):**
- [ ] Events labelled `event:latency` appear approximately every second
- [ ] Data contains latency percentiles: `p50`, `p95`, `p99`, `min`, `max`

---

### TEST SSE-06: Test /api/stream/throughput

```bash
(echo -e "GET /api/stream/throughput HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 5) \
  | nc localhost 8082 2>/dev/null | grep -E "event:|\"messagesPerSecond\"" | head -10
```

**Expected Result (PASS):**
- [ ] Events labelled `event:throughput` appear approximately every second
- [ ] Data contains `messagesPerSecond`, `peakPerSecond`, `totalMessages`

---

### TEST SSE-07: Test /api/stream/alerts

```bash
(echo -e "GET /api/stream/alerts HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 5) \
  | nc localhost 8082 2>/dev/null | head -15
```

**Expected Result (PASS):**
- [ ] HTTP 200 response received
- [ ] `:connected` heartbeat appears
- [ ] If no alerts are currently firing, no data lines appear (this is correct — alerts are event-driven)

---

### TEST SSE-08: Test Dashboard Statistics Endpoint (REST, not SSE)

```bash
curl -s http://localhost:8082/api/stream/stats | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] Returns a JSON object with: `totalTicks`, `messagesPerSecond`, `peakPerSecond`, `activeConnections`, `overallStatus`, `activeEmitters`, `timestamp`
- [ ] `activeConnections` matches the number of CONNECTED feeds

---

## 10. Validation Configuration Tests

> You can change validator thresholds at runtime without restarting the server. This section tests those changes.

---

### TEST VC-01: Change Latency Threshold

1. First check current LATENCY status and note the current `metric` (p95):
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['results'].get('LATENCY', {})
print('Current status:', r.get('status'))
print('Current p95 (metric):', r.get('metric'))
print('Current threshold:', r.get('threshold'))
"
```

2. Set threshold to 1ms (an unrealistically strict threshold):
```bash
curl -s -X PUT http://localhost:8082/api/validation/config \
  -H "Content-Type: application/json" \
  -d '{"area": "LATENCY", "config": {"thresholdMs": 1}}'
```

3. Wait 5 seconds, then check LATENCY again

**Expected Result (PASS):**
- [ ] PUT returns `{"message": "Configuration updated", "area": "LATENCY"}`
- [ ] After 5 seconds, LATENCY shows `"status": "FAIL"` (even a 5ms p95 exceeds 1ms threshold × 2 = 2ms fail boundary)
- [ ] `"threshold"` in the response now shows `1.0` instead of `500.0`

4. Restore the default threshold:
```bash
curl -s -X PUT http://localhost:8082/api/validation/config \
  -H "Content-Type: application/json" \
  -d '{"area": "LATENCY", "config": {"thresholdMs": 500}}'
```

---

### TEST VC-02: Change Accuracy Spike Threshold

1. Set a very tight spike threshold (1% price move triggers alert):
```bash
curl -s -X PUT http://localhost:8082/api/validation/config \
  -H "Content-Type: application/json" \
  -d '{"area": "ACCURACY", "config": {"largeMovePercent": 0.01}}'
```

2. In CLEAN mode, even normal market volatility (>1%) may trigger large-move alerts
3. Wait 15 seconds, check ACCURACY — it may now show WARN even in CLEAN mode

**Expected Result (PASS):**
- [ ] PUT returns `{"message": "Configuration updated", "area": "ACCURACY"}`
- [ ] ACCURACY may change status vs. before the change

4. Restore:
```bash
curl -s -X PUT http://localhost:8082/api/validation/config \
  -H "Content-Type: application/json" \
  -d '{"area": "ACCURACY", "config": {"largeMovePercent": 0.10}}'
```

---

### TEST VC-03: Reset All Validators

1. Ensure at least one validator is not PASS (run some CHAOS ticks)
2. Reset:
```bash
curl -s -X POST http://localhost:8082/api/validation/reset | python3 -m json.tool
```
3. Immediately check summary:
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('ticksProcessed:', d.get('ticksProcessed'))
print('overallStatus:', d.get('overallStatus'))
"
```

**Expected Result (PASS):**
- [ ] Reset response contains `"message": "All validators reset"` and a `timestamp`
- [ ] `ticksProcessed` is `0` or a very small number (only ticks since reset)
- [ ] All validators return to PASS within 15 seconds (assuming clean feed)

---

### TEST VC-04: Config Without 'config' Key Should Fail

```bash
curl -s -X PUT http://localhost:8082/api/validation/config \
  -H "Content-Type: application/json" \
  -d '{"area": "LATENCY"}'
```

**Expected Result (PASS — the error is correct behaviour):**
- [ ] HTTP response code is `400 Bad Request`
- [ ] Response body contains `"error": "'config' map is required"`

---

### TEST VC-05: Startup Configuration From application.properties

1. Stop and restart the backend
2. Without making any API calls, immediately check the LATENCY threshold:
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['results'].get('LATENCY', {})
print('Threshold:', r.get('threshold'))
"
```

**Expected Result (PASS):**
- [ ] `threshold` is `500.0` (from `validator.latency.warn-threshold-ms=500` in application.properties)
- [ ] This confirms that startup configuration is applied automatically on launch

---

### TEST VC-06: View Validation History

```bash
curl -s http://localhost:8082/api/validation/history | python3 -c "
import sys, json
results = json.load(sys.stdin)
print(f'Number of results: {len(results)}')
for r in results:
    print(f'  {r[\"area\"]}: {r[\"status\"]}')
"
```

**Expected Result (PASS):**
- [ ] Returns exactly 8 results (one per validator)
- [ ] Each result has all required fields: `area`, `status`, `message`, `metric`, `threshold`

---

## 11. Error Handling Tests

> These tests verify that the application handles bad input gracefully — returning clear error messages instead of crashing.

---

### TEST ERR-01: Missing Required Parameter — Session Start

```bash
# Missing 'name'
curl -s -X POST http://localhost:8082/api/sessions/start \
  -H "Content-Type: application/json" \
  -d '{"feedId": "some-uuid"}'
```

**Expected Result (PASS):**
- [ ] HTTP 400 with `"error": "'name' is required"`

```bash
# Missing 'feedId'
curl -s -X POST http://localhost:8082/api/sessions/start \
  -H "Content-Type: application/json" \
  -d '{"name": "Test"}'
```

**Expected Result (PASS):**
- [ ] HTTP 400 with `"error": "'feedId' is required"`

---

### TEST ERR-02: 404 for Non-Existent Feed

```bash
curl -s http://localhost:8082/api/feeds/non-existent-uuid-here \
  -w "\nHTTP %{http_code}\n"
```

Wait — there is no single-feed GET endpoint. The list endpoint `/api/feeds` returns all. Test a modify operation:

```bash
curl -s -X PUT http://localhost:8082/api/feeds/non-existent-uuid \
  -H "Content-Type: application/json" \
  -d '{"name": "Test"}' \
  -w "\nHTTP %{http_code}\n"
```

**Expected Result (PASS):**
- [ ] HTTP 404

---

### TEST ERR-03: 404 for Non-Existent Session

```bash
curl -s http://localhost:8082/api/sessions/99999/ticks \
  -w "\nHTTP %{http_code}\n"
```

**Expected Result (PASS):**
- [ ] HTTP 404

---

### TEST ERR-04: Replay Speed = 0

```bash
curl -s -X POST "http://localhost:8082/api/sessions/1/replay?speed=0" | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] HTTP 400
- [ ] Error message mentions "between 0 and 1000"

---

### TEST ERR-05: Replay Speed = 1001

```bash
curl -s -X POST "http://localhost:8082/api/sessions/1/replay?speed=1001" | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] HTTP 400
- [ ] Error message contains "0 and 1000"

---

### TEST ERR-06: SSRF — All Blocked Patterns

| Test Input | Expected Error Message |
|------------|----------------------|
| `wss://192.168.1.1/feed` | "Private/internal IP addresses are not allowed" |
| `wss://10.0.0.1/feed` | "Private/internal IP addresses are not allowed" |
| `wss://172.16.0.1/feed` | "Private/internal IP addresses are not allowed" |
| `wss://127.0.0.1/feed` | "Loopback addresses are not allowed" |
| `wss://localhost/feed` | "Loopback addresses are not allowed" |
| `http://binance.com/feed` | "URL must use ws:// or wss:// scheme" |
| `file:///etc/passwd` | "URL must use ws:// or wss:// scheme" |
| `wss://this-does-not-resolve.invalid/feed` | "hostname could not be resolved" |
| `wss://` (no host) | "URL must have a valid host" |

Test each one:
```bash
for URL in \
  "wss://192.168.1.1/feed" \
  "wss://10.0.0.1/feed" \
  "wss://127.0.0.1/feed" \
  "wss://localhost/feed" \
  "http://binance.com/feed" \
  "wss://fake-hostname-xyz-123.invalid/feed"; do
  echo -n "URL: $URL → "
  curl -s -X POST http://localhost:8082/api/feeds \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"test\",\"url\":\"$URL\",\"adapterType\":\"GENERIC\",\"symbols\":[]}" | \
    python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('error','NO ERROR'))"
done
```

**Expected Result (PASS):**
- [ ] Every URL in the list is rejected with HTTP 400
- [ ] Each error message matches what is shown in the table above

---

### TEST ERR-07: Malformed JSON Body

```bash
curl -s -X POST http://localhost:8082/api/feeds \
  -H "Content-Type: application/json" \
  -d 'this is not json'
```

**Expected Result (PASS):**
- [ ] HTTP 400 (Spring Boot rejects unparseable JSON)

---

### TEST ERR-08: Subscribe Without Symbols

```bash
curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/subscribe \
  -H "Content-Type: application/json" \
  -d '{"symbols": []}' | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] HTTP 400
- [ ] Error: "Request must include 'symbols' array"

---

### TEST ERR-09: Stop a Feed That Is Already Stopped

```bash
# Ensure feed is disconnected first
curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/stop
# Try to stop again
curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/stop | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] HTTP 400
- [ ] Error: "Connection already stopped"

---

### TEST ERR-10: Start a Feed That Is Already Running

```bash
# Ensure feed is connected first
curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/start
# Try to start again
curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/start | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] HTTP 400
- [ ] Error: "Connection already active or failed to start"

---

## 12. Advanced / Stress Tests

> These tests push the system harder. They take longer to run. Run these after all basic tests pass.

---

### TEST ADV-01: CHAOS Mode for 60 Seconds — No Crashes

1. Start simulator in CHAOS mode at 200 ticks/sec:
```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode": "CHAOS", "ticksPerSecond": 200}'
```
2. Wait 60 seconds
3. Verify the app is still running:
```bash
curl -s http://localhost:8082/api/stream/stats | python3 -m json.tool
```
4. Check the backend terminal for any ERROR or FATAL log lines

**Expected Result (PASS):**
- [ ] The backend is still running after 60 seconds
- [ ] `totalTicks` in `/api/stream/stats` is high (200 ticks/sec × 60 sec = ~12,000 ticks)
- [ ] No FATAL or unhandled exception log lines appear in the backend terminal
- [ ] The dashboard in the browser is still updating

---

### TEST ADV-02: Open 5 Simultaneous SSE Connections

Run these 5 commands in parallel in separate terminals (or in background):

```bash
# Terminal 1
(echo -e "GET /api/stream/ticks HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 20) | nc localhost 8082 > /tmp/sse1.log 2>&1 &

# Terminal 2
(echo -e "GET /api/stream/validation HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 20) | nc localhost 8082 > /tmp/sse2.log 2>&1 &

# Terminal 3
(echo -e "GET /api/stream/latency HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 20) | nc localhost 8082 > /tmp/sse3.log 2>&1 &

# Terminal 4
(echo -e "GET /api/stream/throughput HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 20) | nc localhost 8082 > /tmp/sse4.log 2>&1 &

# Terminal 5
(echo -e "GET /api/stream/alerts HTTP/1.1\r\nHost: localhost:8082\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"; sleep 20) | nc localhost 8082 > /tmp/sse5.log 2>&1 &

# Wait 20 seconds, then verify each received data
sleep 22
for i in 1 2 3 4 5; do
  echo "=== SSE log $i ==="
  head -5 /tmp/sse$i.log
done
```

**Expected Result (PASS):**
- [ ] All 5 connections receive `HTTP/1.1 200` responses
- [ ] Each log file contains data lines (`data:` or `event:` lines)
- [ ] The backend does not crash or slow down
- [ ] Check `/api/stream/stats` — `activeEmitters` should be ≥ 5

---

### TEST ADV-03: Record Session During CHAOS Mode

1. Start simulator in CHAOS mode
2. Reset validators
3. Start a recording session:
```bash
curl -s -X POST http://localhost:8082/api/sessions/start \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"Chaos Recording\", \"feedId\": \"$LVWR_ID\"}"
```
4. Wait 30 seconds
5. Stop the session:
```bash
curl -s -X POST http://localhost:8082/api/sessions/CHAOS_SESSION_ID/stop | python3 -m json.tool
```

**Expected Result (PASS):**
- [ ] `"status": "COMPLETED"` (not FAILED — database writes should succeed even under chaos)
- [ ] `tickCount` is greater than 0 (ticks were recorded)
- [ ] `byteSize` is greater than 0

---

### TEST ADV-04: Replay Large Session at Max Speed

1. Find a session with 1000+ ticks:
```bash
curl -s http://localhost:8082/api/sessions | python3 -c "
import sys, json
sessions = json.load(sys.stdin)
for s in sessions:
    if s['tickCount'] >= 1000:
        print(f'id={s[\"id\"]} ticks={s[\"tickCount\"]} name={s[\"name\"]}')
"
```
2. Replay at max speed:
```bash
time curl -s -X POST "http://localhost:8082/api/sessions/SESSION_ID/replay?speed=1000" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('ticksReplayed:', d.get('ticksReplayed'))
print('Success!')
"
```

**Expected Result (PASS):**
- [ ] Replay completes in under 30 seconds (even for large sessions)
- [ ] `ticksReplayed` matches the session's `tickCount`
- [ ] No 500 error or timeout

---

### TEST ADV-05: Rapid Start/Stop Simulator

Run this 5 times:
```bash
for i in 1 2 3 4 5; do
  echo "=== Iteration $i ==="
  curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/start | python3 -c "import sys,json; print('status:', json.load(sys.stdin).get('status'))"
  sleep 1
  curl -s -X POST http://localhost:8082/api/feeds/$LVWR_ID/stop | python3 -c "import sys,json; print('status:', json.load(sys.stdin).get('status'))"
  sleep 1
done
```

**Expected Result (PASS):**
- [ ] Each start returns CONNECTED or immediately switches to CONNECTED
- [ ] Each stop returns DISCONNECTED
- [ ] The app does not freeze, throw errors, or accumulate duplicate connections
- [ ] After 5 cycles, the simulator starts and stops cleanly

---

## 13. Regression Tests

> These are specific bugs that were previously present in the system and have been fixed. Each must be tested to ensure the fix holds. A regression is when a bug that was fixed comes back.

---

### TEST REG-01: tickCount Must Be > 0 After Recording

**What was broken:** Sessions always showed `tickCount: 0` even after recording thousands of ticks. The batch flush was not being called correctly.

**Test:**
1. Start simulator in CLEAN mode
2. Start a recording session
3. Wait 15 seconds
4. Stop the recording session
5. Check `tickCount`

**Expected Result (PASS):**
- [ ] `tickCount` is significantly greater than 0 (should be approximately `ticksPerSecond × seconds`, e.g. 750+ for 50 ticks/sec × 15 sec)

**Failure Result (FAIL — regression detected):**
- `tickCount` is 0 → The flush fix has regressed. Check `SessionRecorder.java`.

---

### TEST REG-02: Speed=1001 Must Return HTTP 400

**What was broken:** `POST /api/sessions/{id}/replay?speed=1001` silently accepted any speed and ran the replay without validation.

**Test:**
```bash
curl -s -X POST "http://localhost:8082/api/sessions/1/replay?speed=1001" \
  -w "\nHTTP %{http_code}"
```

**Expected Result (PASS):**
- [ ] HTTP 400
- [ ] Response body contains error message with "0 and 1000"

**Failure Result (FAIL — regression detected):**
- HTTP 200 → The `SessionReplayer` speed validation is not being called. The `SessionController` must delegate to `SessionReplayer.replaySync()`.

---

### TEST REG-03: Unresolvable Hostname Must Return HTTP 400

**What was broken:** A URL like `wss://fake-internal-host.corp/stream` could bypass the IP-block list if the hostname didn't resolve, because the code only checked the resolved IP. Unresolvable hostnames would silently be allowed.

**Test:**
```bash
curl -s -X POST http://localhost:8082/api/feeds \
  -H "Content-Type: application/json" \
  -d '{"name":"DNS Bypass","url":"wss://definitely-not-real-xyz123.invalid/stream","adapterType":"GENERIC","symbols":[]}'
```

**Expected Result (PASS):**
- [ ] HTTP 400
- [ ] Error: "hostname could not be resolved"

**Failure Result (FAIL — regression detected):**
- HTTP 201 (feed created) → The DNS resolution check has been removed. Check `FeedController.validateFeedUrl()`.

---

### TEST REG-04: ReconnectionValidator Must React to Disconnect Events

**What was broken:** The `ReconnectionValidator` was always PASS because no code was calling its `onDisconnect()` / `onReconnect()` methods. It was "wired up" in code but never actually called.

**Test:**
1. Reset validators
2. Stop and start the simulator (this triggers a disconnect + reconnect)
3. Wait 5 seconds
4. Check the RECONNECTION validator

```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['results'].get('RECONNECTION', {})
print('Status:', r.get('status'))
print('Message:', r.get('message'))
"
```

**Expected Result (PASS):**
- [ ] The message is NOT "No connection events yet" (events were recorded)
- [ ] `disconnectCount` or `reconnectCount` is greater than 0

**Failure Result (FAIL — regression detected):**
- Always shows "No connection events yet" → The `FeedConnection` is not calling `reconnectionValidator.onDisconnect()`.

---

### TEST REG-05: Session Status Must Be FAILED When DB Flush Fails

**What was broken:** If a database error occurred during tick recording, the ticks were silently discarded but the session was marked COMPLETED, giving a false sense of success.

**Testing this requires a DB failure, which is difficult to trigger manually. Instead, verify the logic is in place:**

```bash
grep -n "flushFailed\|FAILED\|status.*FAIL" \
  backend/src/main/java/com/marketdata/validator/session/SessionRecorder.java
```

**Expected Result (PASS):**
- [ ] Lines referencing `flushFailed` exist in the file
- [ ] A line exists that sets session status to `FAILED` when `flushFailed` is true

**Alternative — verify via unit test report:**
```bash
cd backend && ./mvnw -Dtest=SessionRecorderFlushFailureTest test 2>&1 | tail -5
```

- [ ] All tests in `SessionRecorderFlushFailureTest` pass

---

### TEST REG-07: AccuracyValidator Must Detect All Three Violation Types After Method Extraction

**What was changed:** `AccuracyValidator.onTick()` was refactored — the inline price, bid/ask, and large-move checks were extracted into three private methods (`validatePrice`, `validateBidAsk`, `validateNoLargeMove`). This regression test confirms all three violation counters still increment correctly.

**Test:**

1. Reset validators:
```bash
curl -s -X POST http://localhost:8082/api/validation/reset
```

2. Start the simulator in SCENARIO mode with `NEGATIVE_PRICE` to trigger invalid price:
```bash
curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
  -H "Content-Type: application/json" \
  -d '{"mode":"SCENARIO","targetScenario":"NEGATIVE_PRICE","failureRate":0.5,"ticksPerSecond":50}'
```

3. Wait 10 seconds, then check ACCURACY details:
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
a = d['results'].get('ACCURACY', {})
details = a.get('details', {})
print('Status:            ', a.get('status'))
print('invalidPriceCount: ', details.get('invalidPriceCount'))
print('bidAskViolations:  ', details.get('bidAskViolations'))
print('largeMoveCount:    ', details.get('largeMoveCount'))
"
```

4. Reset and repeat with `PRICE_SPIKE` scenario to trigger large-move and bid/ask violations.

**Expected Result (PASS):**
- [ ] After `NEGATIVE_PRICE` scenario: `invalidPriceCount` is greater than 0
- [ ] After `PRICE_SPIKE` scenario: `largeMoveCount` is greater than 0
- [ ] ACCURACY card shows WARN or FAIL (not PASS) during either scenario

**Failure Result (FAIL — regression detected):**
- All violation counters remain 0 → One or more `validate*` methods is not being called. Check `AccuracyValidator.onTick()` short-circuit chain.

---

### TEST REG-08: SubscriptionValidator Must Track Subscribe Latency After Refactor

**What was changed:** `SubscriptionValidator.getResult()` (complexity 21) was split into four private methods: `countActiveSymbols`, `countTimedOutSubscriptions`, `deriveStatus`, `buildMessage`. The `computeIfAbsent` pattern now records subscribe latency on first tick. This test confirms subscribe latency is still captured.

**Test:**

1. Reset validators and stop the simulator.
2. Subscribe to a symbol (subscribe event starts the timer):
```bash
curl -s -X POST "http://localhost:8082/api/feeds/$LVWR_ID/subscribe" \
  -H "Content-Type: application/json" \
  -d '{"symbols":["BTCUSDT"]}'
```
3. Start the simulator:
```bash
curl -s -X POST "http://localhost:8082/api/feeds/$LVWR_ID/start"
```
4. Wait 5 seconds, then check SUBSCRIPTION details:
```bash
curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
s = d['results'].get('SUBSCRIPTION', {})
details = s.get('details', {})
print('Status:          ', s.get('status'))
print('subscribedCount: ', details.get('subscribedCount'))
print('activeCount:     ', details.get('activeCount'))
print('subscribeEvents: ', details.get('subscribeEvents'))
"
```

**Expected Result (PASS):**
- [ ] `subscribeEvents` is greater than 0
- [ ] `activeCount` equals `subscribedCount`
- [ ] SUBSCRIPTION status is PASS

**Failure Result (FAIL — regression detected):**
- `activeCount` is 0 after ticks are flowing → `countActiveSymbols` is not counting correctly. Check `SubscriptionValidator`.
- `subscribeEvents` is 0 → `onSubscribe()` is not being called.

---

### TEST REG-09: LVWRChaosSimulator Must Emit All Failure Types After emitTick Refactor

**What was changed:** The `LVWRChaosSimulator` tick-emission logic was extracted into `emitTick(InstrumentState, FailureType)` using a switch expression. An always-true null check (`if (tick != null)`) was removed from the non-failure path, and the unused `instrId` parameter was removed from `buildTick`. This test verifies all failure modes still fire.

**Test:**

For each scenario listed below, reset validators and run for 15 seconds, then check `failureCounts`:

```bash
for SCENARIO in DISCONNECT RECONNECT_STORM PRICE_SPIKE SEQUENCE_GAP STALE_TIMESTAMP; do
  echo "=== Testing scenario: $SCENARIO ==="
  curl -s -X POST http://localhost:8082/api/validation/reset > /dev/null
  curl -s -X PUT "http://localhost:8082/api/simulator/config?connectionId=$LVWR_ID" \
    -H "Content-Type: application/json" \
    -d "{\"mode\":\"SCENARIO\",\"targetScenario\":\"$SCENARIO\",\"failureRate\":0.8,\"ticksPerSecond\":50}" > /dev/null
  sleep 10
  STATUS=$(curl -s "http://localhost:8082/api/simulator/status?connectionId=$LVWR_ID" | python3 -c "
import sys, json
d = json.load(sys.stdin)
counts = d.get('failureCountsByType', {})
print('failureCounts:', counts.get('$SCENARIO', 0))
print('ticksSent:', d.get('ticksSent', 0))
")
  echo "$STATUS"
  echo ""
done
```

**Expected Result (PASS):**
- [ ] For each scenario: `failureCounts[SCENARIO]` is greater than 0 after 10 seconds
- [ ] `ticksSent` is greater than 0 for every scenario
- [ ] No 500 error or NullPointerException in server logs

**Failure Result (FAIL — regression detected):**
- `failureCounts` is 0 for a scenario → The switch expression in `emitTick` is missing a case or has a fall-through error.
- Server logs show `NullPointerException` in `LVWRChaosSimulator.emitTick` → The null check removal introduced a regression.

---

### TEST REG-10: SessionReplayer Pause/Resume/Stop Must Work After Method Extraction

**What was changed:** `SessionReplayer.replayLoop()` (complexity 21) was split into `checkAndWaitIfPaused()`, `processTickWithTiming()`, and `sleepForGap()`. The loop now uses a single `break` instead of multiple `continue`/`break` paths. This test confirms that pause, resume, and stop still work correctly.

**Prerequisites:** A completed session with at least 200 ticks (see Session Recording Tests for how to create one).

**Test:**

1. Start replay at slow speed:
```bash
REPLAY_SESSION_ID=1  # replace with a real session ID
curl -s -X POST "http://localhost:8082/api/sessions/$REPLAY_SESSION_ID/replay?speed=0.5" &
```

2. Wait 3 seconds, then pause:
```bash
curl -s -X POST http://localhost:8082/api/sessions/replay/pause | python3 -m json.tool
```

3. Wait 5 seconds. Verify that `ticksReplayed` is frozen (not increasing):
```bash
sleep 5
curl -s http://localhost:8082/api/sessions/replay/status | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('state:', d.get('state'))
print('ticksReplayed:', d.get('ticksReplayed'))
"
```

4. Resume:
```bash
curl -s -X POST http://localhost:8082/api/sessions/replay/resume | python3 -m json.tool
```

5. Verify `ticksReplayed` is increasing again (check twice, 2 seconds apart):
```bash
T1=$(curl -s http://localhost:8082/api/sessions/replay/status | python3 -c "import sys,json; print(json.load(sys.stdin).get('ticksReplayed'))")
sleep 2
T2=$(curl -s http://localhost:8082/api/sessions/replay/status | python3 -c "import sys,json; print(json.load(sys.stdin).get('ticksReplayed'))")
echo "ticksReplayed before: $T1, after: $T2"
```

6. Stop replay:
```bash
curl -s -X POST http://localhost:8082/api/sessions/replay/stop
```

**Expected Result (PASS):**
- [ ] After pause: state is `PAUSED` and `ticksReplayed` does not change over 5 seconds
- [ ] After resume: state returns to `REPLAYING` and `ticksReplayed` increases (T2 > T1)
- [ ] After stop: state is `IDLE`

**Failure Result (FAIL — regression detected):**
- Replay does not pause (ticks keep flowing) → `checkAndWaitIfPaused()` `pauseLock.wait()` is not being reached.
- After resume, replay stays frozen → `pauseLock.notifyAll()` is not being called.

---

### TEST REG-06: COMPLETENESS Stale Hysteresis — No False Recovery

**What was broken:** A symbol that became stale would immediately recover after receiving just one tick, causing oscillation (FAIL → PASS → FAIL → PASS every few seconds).

**Test:**
1. Let the simulator run, making a symbol go stale (stop and wait 12 seconds)
2. Restart simulator
3. Check COMPLETENESS every 5 seconds for 45 seconds

```bash
for i in $(seq 1 9); do
  sleep 5
  STATUS=$(curl -s http://localhost:8082/api/validation/summary | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['results'].get('COMPLETENESS', {})
print(r.get('status'), 'stale:', r.get('details', {}).get('staleSymbolCount', 0))
")
  echo "$(date '+%H:%M:%S') → $STATUS"
done
```

**Expected Result (PASS):**
- [ ] After the stale event, the status does NOT immediately flip back to PASS on the first tick
- [ ] COMPLETENESS remains FAIL for approximately 30 seconds after recovery ticks start arriving
- [ ] Only after ~30 continuous seconds of good ticks does the stale symbol clear

**Failure Result (FAIL — regression detected):**
- Status oscillates rapidly (FAIL/PASS/FAIL every few seconds) → The `staleRecoveryWindowMs` hysteresis is not working.

---

## 14. API Quick Reference

> A complete table of every API endpoint for quick lookup during testing.

### Feed Management — `/api/feeds`

| Method | URL | Purpose | Request Body | Response Codes |
|--------|-----|---------|--------------|----------------|
| GET | `/api/feeds` | List all configured feeds | None | 200 |
| POST | `/api/feeds` | Add a new feed connection | `{name, url, adapterType, symbols[]}` | 201, 400 |
| PUT | `/api/feeds/{id}` | Update feed config | `{name?, url?, symbols?, adapterType?}` | 200, 400, 404 |
| DELETE | `/api/feeds/{id}` | Stop and remove feed | None | 204, 404 |
| POST | `/api/feeds/{id}/start` | Connect a feed | `ScenarioConfig` (optional, LVWR_T only) | 200, 400, 404 |
| POST | `/api/feeds/{id}/stop` | Disconnect a feed | None | 200, 400, 404 |
| POST | `/api/feeds/{id}/subscribe` | Add symbols to feed | `{symbols: ["BTCUSDT"]}` | 200, 400, 404 |
| POST | `/api/feeds/{id}/unsubscribe` | Remove symbols from feed | `{symbols: ["BTCUSDT"]}` | 200, 400, 404 |

### Validation — `/api/validation`

| Method | URL | Purpose | Request Body / Params | Response Codes |
|--------|-----|---------|----------------------|----------------|
| GET | `/api/validation/summary` | All validators' current results | `?feedId=<uuid>` (optional) | 200 |
| GET | `/api/validation/history` | Snapshot of all results as list | None | 200 |
| PUT | `/api/validation/config` | Update validator thresholds | `{area?: "LATENCY", config: {thresholdMs: 200}}` | 200, 400 |
| POST | `/api/validation/reset` | Reset all validator state | None | 200 |
| GET | `/api/validation/feeds` | List feeds for scope dropdown | None | 200 |

**Config key names per validator:**

| Validator | Config Keys |
|-----------|-------------|
| LATENCY | `thresholdMs` (default: 500), `bufferSize` (default: 10000) |
| ACCURACY | `largeMovePercent` (default: 0.10), `passThreshold` (default: 99.99), `warnThreshold` (default: 99.0), `reconnectGapMs` (default: 60000) |
| COMPLETENESS | `heartbeatThresholdMs` (default: 10000), `staleRecoveryWindowMs` (default: 30000) |
| THROUGHPUT | `zeroThresholdSecs` (default: 5), `dropPercent` (default: 0.50) |
| ORDERING | `passThreshold` (default: 99.99), `warnThreshold` (default: 99.0) |
| STATEFUL | `passThreshold` (default: 99.99), `warnThreshold` (default: 99.9), `staleThresholdMs` (default: 30000) |
| RECONNECTION | `reconnectThresholdMs` (default: 5000) |
| SUBSCRIPTION | `subscribeTimeoutMs` (default: 5000), `unsubscribeGraceMs` (default: 3000) |

### Sessions — `/api/sessions`

| Method | URL | Purpose | Request Body / Params | Response Codes |
|--------|-----|---------|----------------------|----------------|
| GET | `/api/sessions` | List all sessions | None | 200 |
| POST | `/api/sessions/start` | Start recording session | `{name, feedId}` | 201, 400 |
| POST | `/api/sessions/{id}/stop` | Stop active recording | None | 200, 400 |
| DELETE | `/api/sessions/{id}` | Delete session and ticks | None | 204, 400, 404 |
| GET | `/api/sessions/{id}/ticks` | Get all ticks in session | None | 200, 404 |
| GET | `/api/sessions/{id}/export` | Export as CSV or JSON | `?format=csv\|json` (default: json) | 200, 404 |
| POST | `/api/sessions/{id}/replay` | Replay ticks through validators | `?speed=1.0` (range: 0–1000] | 200, 400, 404, 500 |

### Simulator — `/api/simulator`

| Method | URL | Purpose | Request Body / Params | Response Codes |
|--------|-----|---------|----------------------|----------------|
| GET | `/api/simulator/scenarios` | List all 12 failure types | None | 200 |
| GET | `/api/simulator/status` | Get simulator live stats | `?connectionId=<uuid>` | 200, 404 |
| PUT | `/api/simulator/config` | Update simulator mode/settings | `?connectionId=<uuid>` + body below | 200, 404 |

**PUT /api/simulator/config body:**
```json
{
  "mode": "CLEAN | NOISY | CHAOS | SCENARIO",
  "targetScenario": "SEQUENCE_GAP | PRICE_SPIKE | ...",
  "failureRate": 0.1,
  "ticksPerSecond": 50,
  "disconnectDurationMs": 8000,
  "reconnectPauseDurationMs": 500
}
```

### Alerts — `/api/alerts`

| Method | URL | Purpose | Response Codes |
|--------|-----|---------|----------------|
| GET | `/api/alerts` | List all alerts | 200 |
| GET | `/api/alerts/unacknowledged` | List unacknowledged alerts | 200 |
| GET | `/api/alerts/count` | Count of unacknowledged alerts | 200 |
| POST | `/api/alerts/{id}/acknowledge` | Acknowledge one alert | 200, 404 |
| POST | `/api/alerts/acknowledge-all` | Acknowledge all alerts | 200 |
| DELETE | `/api/alerts/{id}` | Delete one alert | 204, 404 |
| DELETE | `/api/alerts` | Delete all alerts | 204 |

### SSE Streams — `/api/stream`

| Method | URL | Event Name | Data Contains | Notes |
|--------|-----|-----------|--------------|-------|
| GET | `/api/stream/ticks` | `tick` | `symbol, price, latency, sequenceNum, feedId, timestamps` | Filter: `?symbol=BTCUSDT`, `?feedId=<uuid>` |
| GET | `/api/stream/validation` | `validation` | Full 8-validator results object | Filter: `?feedId=<uuid>` |
| GET | `/api/stream/latency` | `latency` | `p50, p95, p99, min, max` | Updates every 1 second |
| GET | `/api/stream/throughput` | `throughput` | `messagesPerSecond, peakPerSecond, totalMessages` | Updates every 1 second |
| GET | `/api/stream/alerts` | `alert` | Single alert object | Event-driven, not periodic |
| GET | `/api/stream/stats` | (REST — not SSE) | `totalTicks, activeConnections, overallStatus, activeEmitters` | Regular HTTP GET |

### Compliance — `/api/compliance`

| Method | URL | Purpose | Response Codes |
|--------|-----|---------|----------------|
| GET | `/api/compliance` | MiFID-II compliance metrics | 200 |

**Response fields:**

| Field | Maps To | PASS Condition |
|-------|---------|----------------|
| `art27TimestampAccuracy` | LATENCY p95 < 100ms | p95 < 100ms |
| `art64PreTradeTransparency` | ACCURACY status | ACCURACY = PASS |
| `art65PostTradeReporting` | COMPLETENESS gapEventCount | gapEventCount == 0 |
| `rts22TradeIdUniqueness` | ORDERING violations | No duplicate seqNum violations |
| `auditTrailCompleteness` | COMPLETENESS rate | completenessRate ≥ 99.99% |
| `ohlcReconciliation` | STATEFUL status | STATEFUL = PASS |

---

## 15. UI Manual Testing Guide

> **Audience:** Beginner QA tester who has never opened this application before.
> **Goal:** Verify every visible element of the browser dashboard looks and behaves correctly.
> **Test IDs in this section:** UI-01 through UI-36

---

### 15.1 Getting Started With the UI

#### What the dashboard URL is

Open a browser and go to: **http://localhost:5174**

> **Browser recommendation:** Use Google Chrome or Microsoft Edge. Both have the best DevTools for inspecting SSE streams. Firefox works but its Network tab labels SSE connections differently.

If you see a blank page or a "cannot connect" error: the frontend dev server is not running. Run `cd frontend && npm run dev` in a terminal and wait for the "ready" message before refreshing.

---

#### Full layout description

When the dashboard loads you will see three zones:

```
┌──────────────┬─────────────────────────────────────────────────────────┐
│  LEFT        │  MAIN CONTENT AREA                                      │
│  SIDEBAR     │                                                         │
│              │  (Changes based on which tab is active)                 │
│  8 nav tabs  │                                                         │
│              │                                                         │
└──────────────┴─────────────────────────────────────────────────────────┘
```

**Left sidebar — 8 navigation tabs (top to bottom):**

| Tab label | What it shows |
|-----------|---------------|
| 📡 Live Feed | A live scrolling table of market data ticks as they arrive |
| 🔌 Connections | List of configured data feeds; add/start/stop/delete feeds here |
| 📊 Validation | 8 validator cards showing PASS/WARN/FAIL status in real time |
| 📈 Latency | A live chart of p50/p95/p99 latency over time |
| 💾 Sessions | Record, replay, export, and delete named recording sessions |
| 🔔 Alerts | Alert history; acknowledge and delete alerts here |
| 🧪 Simulator | Control the built-in LVWR chaos simulator (labelled **NEW**) |
| ⚖️ Compliance | MiFID II compliance check results (labelled **NEW**) |

**Main content area:** Fills the rest of the screen. Changes to show the selected tab's content.

**Alert badge:** The 🔔 Alerts tab shows a red number badge when there are unacknowledged alerts (e.g. "🔔 Alerts 3"). This number counts down as you acknowledge alerts.

---

#### How a healthy dashboard looks

In a healthy state with the simulator running in CLEAN mode:
- All 8 validator cards on the 📊 Validation tab show a green **✅ PASS** badge
- The "Overall: PASS" banner at the top of the Validation tab is green
- The 📡 Live Feed tab shows new rows appearing every second or faster
- The 🔌 Connections tab shows at least one feed with a 🟢 **CONNECTED** label
- No red badge on the 🔔 Alerts tab
- The browser console (F12) shows no red errors

---

#### How to open browser DevTools

Press **F12** (Windows/Linux) or **Cmd+Option+I** (Mac) to open DevTools.

Why it matters for QA:
- The **Console** tab reveals JavaScript errors that are invisible in the normal UI
- The **Network** tab lets you see whether SSE streams are active and delivering events
- Together they tell you whether failures are in the UI code, the API, or the data

---

#### How to open the Network tab and find SSE connections

1. Press F12 to open DevTools
2. Click the **Network** tab at the top of the DevTools panel
3. In the filter bar, type **stream** — this narrows the list to SSE endpoints
4. You should see requests like `/api/stream/ticks`, `/api/stream/validation`, `/api/stream/latency`, `/api/stream/throughput`, `/api/stream/alerts` — all with status **pending** (this means the connection is open and live)
5. Click any one of those requests, then click the **EventStream** sub-tab (in Chrome) or **Messages** sub-tab
6. You will see a scrolling list of events arriving in real time

**PASS:** Multiple `pending` SSE connections visible; events appearing in the EventStream tab.
**FAIL:** No `pending` connections, or the EventStream tab is empty after 5 seconds.

---

#### How to open the Console tab and spot errors

1. Press F12 → click **Console**
2. Red messages = errors. These are always a FAIL.
3. Yellow messages = warnings. Some are acceptable (React development warnings about keys); others are not.
4. A healthy console after 2 minutes of running should have no red messages.

---

### 15.2 Live Feed Tab 📡

**Test ID: UI-01 through UI-05**

#### Step-by-step walkthrough

1. Click **📡 Live Feed** in the left sidebar. The main area changes to show the live tick table.

2. **What you see in the table:**
   - Each row is one market data tick
   - Columns (left to right): **Timestamp**, **Symbol** (e.g. BTCUSDT), **Price**, **Volume**, **Seq#** (sequence number), **Latency (ms)**, **Feed**
   - Rows appear at the top and older rows scroll down. New rows are added in real time.

3. **How to verify ticks are flowing:**
   - Watch the table for 5 seconds. New rows must appear. If the table is frozen, ticks are not flowing.
   - The timestamp column should show times that are seconds (or milliseconds) in the past — not hours ago.

4. **How to use the feed selector dropdown:**
   - Above the table there is a **"Filter by feed"** dropdown (labelled with a dropdown arrow)
   - Select a specific feed from the dropdown (e.g. the LVWR simulator feed)
   - The table should update to show only ticks from that feed
   - Select "All feeds" to return to unfiltered view

5. **How to use the symbol filter input:**
   - Next to the feed selector there is a text input labelled **"Filter by symbol…"**
   - Type a symbol such as `BTCUSDT` — the table immediately filters to show only rows matching that symbol
   - Typing is uppercase-forced automatically (you can type lowercase and it converts)
   - Clear the input to see all symbols again

6. **Testing with simulator running vs stopped:**
   - With simulator **running** (CLEAN mode): new rows appear continuously, latency values are positive numbers
   - With simulator **stopped**: the table freezes; no new rows appear; the last row's timestamp does not change

---

**UI-01** — Tick table loads with columns  
**PASS:** Table visible, column headers present (Timestamp, Symbol, Price, etc.), at least one row.  
**FAIL:** Table is empty and stays empty after 10 seconds, or column headers are missing.

**UI-02** — Ticks update in real time  
**PASS:** New rows appear in the table at least once every 3 seconds while simulator is running.  
**FAIL:** Table is frozen. No new rows for more than 10 seconds while simulator is running.

**UI-03** — Symbol filter works  
**PASS:** Typing `BTCUSDT` in the filter input leaves only rows with symbol "BTCUSDT". Clearing the input restores all rows.  
**FAIL:** Filter has no effect, or typing causes an error, or "undefined" appears in a row.

**UI-04** — Feed filter works  
**PASS:** Selecting a feed from the dropdown shows only that feed's ticks. Switching back to "All feeds" restores all rows.  
**FAIL:** Dropdown is empty, or selecting a feed shows no rows even though that feed is connected.

**UI-05** — No undefined/blank values in tick rows  
**PASS:** Every visible cell has a real value (price is a number, symbol is a string, timestamp is a date/time).  
**FAIL:** Any cell shows "undefined", "null", "NaN", or is visually blank when rows are present.

---

### 15.3 Connections Tab 🔌

**Test ID: UI-06 through UI-11**

#### Step-by-step walkthrough

1. Click **🔌 Connections** in the left sidebar.

2. **What the feed list shows:**
   - Each row represents a configured feed connection
   - Left side: 🟢 or 🔴 status icon, feed name, adapter type badge (e.g. `LVWR`, `BINANCE`)
   - Right side: connection status label (`CONNECTED` in green / `DISCONNECTED`), and buttons: **▶ Start**, **⏹ Stop**, **🗑 Delete**
   - If no feeds are configured, a message reads: "No feeds configured. Click '+ Add Feed' to get started."

3. **How to add a new feed via the UI form:**
   - Click the **"+ Add Feed"** button in the top-right of the panel
   - A dialog box appears titled **"Add Feed"** with the following fields:
     - **Name** (text input) — a label for this feed, e.g. "My Simulator"
     - **URL** (text input, shown only for non-simulator feeds) — the WebSocket URL, e.g. `wss://stream.binance.com:9443/ws/btcusdt@trade`
     - **Adapter type radio buttons** — choose the type: BINANCE, FINNHUB, GENERIC, or LVWR (simulator)
   - If you select **LVWR**, the URL field is hidden and replaced with simulator mode/scenario controls
   - Fill in Name, select adapter, then click **"Add"**
   - The dialog closes and the new feed appears in the list

4. **How to connect a feed:**
   - Find the feed row in the list
   - If it shows 🔴 DISCONNECTED, click the **▶ Start** button
   - The status icon changes to 🟢 and the label changes to **CONNECTED**
   - The **▶ Start** button is replaced by **⏹ Stop**

5. **How to disconnect a feed:**
   - Click the **⏹ Stop** button on a connected feed
   - The status changes to 🔴 **DISCONNECTED**
   - The **⏹ Stop** button is replaced by **▶ Start**

6. **How to delete a feed:**
   - Click **🗑 Delete** on any feed row
   - A browser confirmation dialog appears: "Delete connection '[name]'?"
   - Click OK to confirm, or Cancel to abort
   - On confirmation, the feed row disappears from the list

---

**UI-06** — Feed list loads  
**PASS:** Connection list visible, showing at least one row (the simulator feed if configured).  
**FAIL:** Page shows spinner indefinitely, or "Error fetching feeds" message.

**UI-07** — Add feed dialog opens and closes  
**PASS:** "+ Add Feed" button click opens a dialog. "Cancel" closes it without adding anything.  
**FAIL:** Dialog does not open, or closing it causes the page to freeze.

**UI-08** — Connect a feed  
**PASS:** After clicking ▶ Start, status icon changes from 🔴 to 🟢 and label becomes CONNECTED within 5 seconds.  
**FAIL:** Status stays 🔴 after clicking Start, or an error message appears.

**UI-09** — Disconnect a feed  
**PASS:** After clicking ⏹ Stop, status changes to 🔴 DISCONNECTED. ⏹ button replaced by ▶ Start.  
**FAIL:** Status remains CONNECTED after Stop.

**UI-10** — Delete a feed  
**PASS:** Confirmation dialog appears. After clicking OK, feed row is removed from the list.  
**FAIL:** No confirmation dialog (accidental deletion possible), or feed row remains after deletion.

**UI-11** — Tick count visible on connected feed  
**PASS:** While a feed is CONNECTED and the simulator is running, a tick counter or some stats are visible and changing.  
**FAIL:** No indication that data is being received on a connected feed.

---

### 15.4 Validation Tab 📊

**Test ID: UI-12 through UI-17**

#### Step-by-step walkthrough

1. Click **📊 Validation** in the left sidebar.

2. **What the 8 validator cards look like:**
   - The panel shows a banner at the top and a grid of 8 cards below it
   - Card order (left to right, top to bottom): **Accuracy**, **Latency**, **Completeness**, **Reconnection**, **Throughput**, **Ordering**, **Subscription**, **Stateful**
   - Each card has a coloured background: green (PASS), yellow/amber (WARN), or red (FAIL)

3. **How to read a validator card:**

   ```
   ┌──────────────────────────────┐
   │ ✅  Accuracy          PASS   │  ← status icon + area name + status text
   │                              │
   │ All prices valid             │  ← detail message
   │ Value: 0.0%  Threshold: 10%  │  ← metric and threshold (when available)
   └──────────────────────────────┘
   ```

   - **Status icon:** ✅ = PASS, ⚠️ = WARN, ❌ = FAIL
   - **Status text:** PASS / WARN / FAIL in the top-right of the card header
   - **Detail message:** one sentence describing the current state
   - **Value / Threshold:** only shown when the validator tracks a measurable metric (e.g. Latency shows p95 in ms, Completeness shows a completeness rate percentage)
   - **Clicking a card** expands it to show detailed sub-results for that validator area

4. **The "Overall: PASS/WARN/FAIL" banner:**
   - At the very top of the Validation panel, above all cards
   - Shows the worst status across all 8 validators: if even one is FAIL, Overall = FAIL
   - Green background = PASS, amber background = WARN, red background = FAIL
   - Next to it is a small coloured dot: green (SSE connected) or red (SSE disconnected)

5. **The feed scope dropdown:**
   - Above the cards there is a **feed selector dropdown** (may be labelled "All feeds" by default)
   - Selecting a specific feed filters all 8 cards to show metrics only for that feed
   - This is important when multiple feeds are connected — the LVWR simulator and a live exchange may behave very differently

6. **PASS appearance (CLEAN mode):**
   - All 8 cards have a green background
   - Overall banner shows ✅ Overall: PASS
   - Detail messages say things like "All prices valid", "Sequence in order", "No gaps detected"
   - Latency card shows a p95 value well below the configured threshold (e.g. "Value: 12ms  Threshold: 500ms")

7. **WARN appearance:**
   - One or more cards have an amber/yellow background with ⚠️ icon
   - Typical triggers: latency slightly above threshold, minor sequence gap detected, throughput slightly below minimum
   - Overall banner turns amber if any card is WARN

8. **FAIL appearance:**
   - One or more cards have a red background with ❌ icon
   - Typical triggers: a CHAOS scenario injecting price spikes, sequence gaps, stale timestamps
   - Overall banner turns red

9. **How to trigger a FAIL visually:**
   - Go to 🧪 Simulator tab
   - Click the **CHAOS** mode button
   - Click **Apply Config** (or **▶ Start** if not already running)
   - Return to 📊 Validation tab
   - Within 10–30 seconds you should see cards change colour from green to amber/red

---

**UI-12** — 8 validator cards all visible  
**PASS:** Exactly 8 cards visible: Accuracy, Latency, Completeness, Reconnection, Throughput, Ordering, Subscription, Stateful. Each card has a name, status icon, and status text.  
**FAIL:** Fewer than 8 cards, any card missing its name, or all cards show "—" or blank status after 10 seconds.

**UI-13** — Overall status banner visible and correct in CLEAN mode  
**PASS:** "✅ Overall: PASS" banner is green. All 8 cards are green.  
**FAIL:** Banner shows FAIL or WARN while simulator is in CLEAN mode and has been running for more than 30 seconds.

**UI-14** — Cards change colour in CHAOS mode  
**PASS:** After switching simulator to CHAOS mode and waiting 30 seconds, at least 3 cards change to amber (⚠️) or red (❌), and the Overall banner reflects the worst status.  
**FAIL:** All cards stay green regardless of simulator mode — validation is not reacting to data quality changes.

**UI-15** — Card click expands to show details  
**PASS:** Clicking any card expands it to show additional sub-result detail. Clicking again collapses it.  
**FAIL:** Clicking a card has no effect, or the expansion causes a JavaScript error in the console.

**UI-16** — Feed scope dropdown filters cards  
**PASS:** Selecting a feed from the dropdown updates all 8 cards to show metrics for only that feed. The SSE dot does not go red.  
**FAIL:** Dropdown is empty, selecting a feed causes a blank panel, or the SSE dot turns red.

**UI-17** — Value and Threshold shown on metric-bearing cards  
**PASS:** Latency card shows "Value: Xms  Threshold: Yms". Completeness card shows a rate or gap count.  
**FAIL:** All cards show only a status badge with no metric values (could indicate backend is not sending metric data).

---

### 15.5 Latency Tab 📈

**Test ID: UI-18 through UI-20**

#### Step-by-step walkthrough

1. Click **📈 Latency** in the left sidebar.

2. **What is shown:**
   - A live chart showing latency over time
   - The x-axis is time (moving right as time passes)
   - The y-axis is latency in milliseconds
   - Three lines or data series: **p50** (median), **p95** (95th percentile), **p99** (99th percentile)
   - A horizontal threshold line marks the configured maximum acceptable latency

3. **How to read the chart:**
   - If the p95 line stays below the threshold line: latency is acceptable
   - If the p95 line rises above the threshold line: latency validation will show FAIL on the Validation tab
   - The most important line to watch is **p95** — this is what the Latency validator checks

4. **How to make latency spike:**
   - Go to 🧪 Simulator → select SCENARIO mode → choose **STALE_TIMESTAMP** from the scenario dropdown → set failure rate to 1.0 (100%) → click Apply Config
   - Return to 📈 Latency tab — you should see the p95 line rise significantly (possibly going off the chart)

---

**UI-18** — Latency chart renders  
**PASS:** Chart is visible with axes and at least one data line drawn.  
**FAIL:** Blank area where the chart should be, or a "Cannot read properties of undefined" error in console.

**UI-19** — Chart updates in real time  
**PASS:** The chart advances along the x-axis as time passes. New data points appear.  
**FAIL:** Chart is static and never updates while the simulator is running.

**UI-20** — Latency spike is visible in chart  
**PASS:** After running STALE_TIMESTAMP scenario at 100% failure rate for 15 seconds, the chart shows a clear upward spike in the p95 line.  
**FAIL:** Chart shows no change regardless of which scenario is running.

---

### 15.6 Sessions Tab 💾

**Test ID: UI-21 through UI-27**

#### Step-by-step walkthrough

1. Click **💾 Sessions** in the left sidebar.

2. **What the session list shows:**
   - A list of saved recording sessions
   - Each session row shows: session name, **status badge** (RECORDING / COMPLETED / FAILED), tick count, duration, start date/time
   - Status badge colours: **RECORDING** = blue/pulsing indicator, **COMPLETED** = green, **FAILED** = red

3. **How to start a new recording:**
   - Click the **"+ New Recording"** button (top area of the panel)
   - A dialog box appears with two fields:
     - **Session name** (text input) — type a name, e.g. "UI Walkthrough Test"
     - **Feed** dropdown — select which connected feed to record from
   - Click **Start Recording**
   - The dialog closes, a new session row appears at the top of the list with a **RECORDING** status badge
   - The tick count on that row increments as ticks arrive

4. **How to verify ticks are being recorded:**
   - Watch the tick count on the RECORDING session row — it should increase every few seconds
   - **CRITICAL:** if the tick count stays at 0 after 10 seconds, recording is broken (this was a previously fixed bug — a regression here is a blocker)

5. **How to stop a recording:**
   - Find the active RECORDING session row (there is also a "Stop Recording" button in the active recording area at the top of the panel)
   - Click **"■ Stop Recording"**
   - The status badge changes from RECORDING to **COMPLETED** (green)
   - The tick count shows a final non-zero number

6. **How to export a session:**
   - On a COMPLETED session row, find the export buttons: **CSV** and **JSON** (or a single export button with format options)
   - Click **CSV** — your browser downloads a file named `session-{id}.csv`
   - Open the downloaded file in a spreadsheet or text editor
   - **PASS:** First row is a header (`symbol,price,...`), subsequent rows are tick data with real values

7. **How to replay a session:**
   - On a COMPLETED session row, click the **▶ Replay** button
   - A replay results panel appears below the session list showing: `ticksReplayed`, `sessionId`, and a list of validation results
   - The replay runs at the default speed (1×) and completes synchronously — the panel appears once replay is done
   - Note: replay goes through the validators, so the Validation tab will update with the replay results

8. **How to delete a session:**
   - On any session row, click the **🗑 Delete** button (or trash icon)
   - A browser confirmation dialog appears
   - Click OK — the session row disappears

---

**UI-21** — Session list loads  
**PASS:** Sessions panel visible, showing existing sessions (or an empty state message if none exist).  
**FAIL:** Error message, blank panel with spinner stuck, or console errors.

**UI-22** — New recording starts  
**PASS:** After filling in name + feed and clicking Start, a new row appears with RECORDING status. No errors.  
**FAIL:** Dialog fails to submit, error toast appears, or no new row appears.

**UI-23** — Tick count increases during recording  
**PASS:** The tick count number on the RECORDING session row visibly increases every few seconds.  
**FAIL:** Tick count stays at 0 for more than 15 seconds while the feed is active. **(Regression blocker)**

**UI-24** — Stop recording shows COMPLETED  
**PASS:** After clicking Stop, status badge changes to COMPLETED (green) within 2 seconds. Tick count shows a final non-zero value.  
**FAIL:** Status stays as RECORDING, or changes to FAILED.

**UI-25** — CSV export downloads correctly  
**PASS:** Clicking CSV export triggers a file download. The file name contains the session ID. Opening the file shows a header row and data rows.  
**FAIL:** No file downloads, file downloads but is empty, or file header row is missing.

**UI-26** — Replay shows results  
**PASS:** Clicking Replay on a completed session shows a results panel with `ticksReplayed > 0` and a list of validation results.  
**FAIL:** Replay button has no effect, or panel shows `ticksReplayed: 0` for a session that had ticks.

**UI-27** — Delete session removes the row  
**PASS:** After confirming deletion, the session row is gone from the list immediately.  
**FAIL:** Row remains after deletion, or deletion causes a 500 error.

---

### 15.7 Alerts Tab 🔔

**Test ID: UI-28 through UI-32**

#### Step-by-step walkthrough

1. Click **🔔 Alerts** in the left sidebar.

2. **What the alert list shows:**
   - Each alert appears as a card with: **severity badge**, **area** (e.g. ACCURACY), **message**, **timestamp**
   - Cards are sorted: unacknowledged first, then by severity (CRITICAL before WARN before INFO), then by time descending
   - Acknowledged alerts appear below unacknowledged ones, visually greyed out

3. **Severity badge colours:**
   - 🔴 **CRITICAL** — red badge, most severe
   - 🟡 **WARN** — yellow/amber badge
   - 🟢 **INFO** — green badge, informational only

4. **The red number badge on the tab:**
   - The tab label shows "🔔 Alerts N" where N = the count of unacknowledged alerts
   - This number is visible on the Alerts tab in the sidebar even when you are on a different tab
   - N decreases by 1 each time you acknowledge one alert; drops to 0 (and badge disappears) when all are acknowledged

5. **How to acknowledge one alert:**
   - Find an unacknowledged alert card (it has full colour — not greyed out)
   - Click the **"✓ Acknowledge"** button on that card
   - The card becomes greyed out (visually dimmed or shows a checkmark)
   - The badge count on the Alerts tab decreases by 1

6. **How to acknowledge all alerts at once:**
   - At the top of the Alerts panel, click **"Acknowledge All"** button
   - All alert cards immediately become greyed out
   - The badge count on the Alerts tab drops to 0 and the badge disappears

7. **How to filter alerts:**
   - There is a toggle button near the top of the panel: **"Show: All"** or **"Show: Unacknowledged"**
   - Clicking it switches between showing all alerts and showing only unacknowledged ones

8. **How to delete an alert:**
   - Click the **🗑** (trash icon) button on any alert card
   - The card disappears immediately. There is no confirmation dialog for individual alert deletion.

9. **How to generate alerts for testing:**
   - Go to 🧪 Simulator tab → switch to CHAOS mode → click Apply Config
   - Wait 30 seconds
   - Return to 🔔 Alerts tab — new WARN and CRITICAL alerts should have appeared

---

**UI-28** — Alerts appear during CHAOS mode  
**PASS:** After 30 seconds of CHAOS mode, the Alerts tab shows at least 2 new alert cards with WARN or CRITICAL severity.  
**FAIL:** No alerts appear after 60 seconds of CHAOS mode — alert generation is broken.

**UI-29** — Tab badge count is accurate  
**PASS:** The red number on the Alerts tab matches the count of unacknowledged alert cards in the list.  
**FAIL:** Badge shows a stale number (e.g. shows 5 but there are 0 unacknowledged), or badge never appears.

**UI-30** — Acknowledge single alert updates badge count  
**PASS:** Clicking Acknowledge on one alert: that card greys out, badge count decreases by exactly 1.  
**FAIL:** Badge count does not change, or card does not visually change.

**UI-31** — Acknowledge all clears badge  
**PASS:** Clicking "Acknowledge All" greys out all cards, badge count drops to 0, red badge disappears from the tab.  
**FAIL:** Some cards remain unacknowledged, or badge count stays non-zero.

**UI-32** — Delete alert removes card  
**PASS:** Clicking 🗑 on an alert card removes it from the list immediately.  
**FAIL:** Card remains, or a JavaScript error appears in console.

---

### 15.8 Simulator Tab 🧪

**Test ID: UI-33 through UI-36** *(plus sub-steps for scenarios)*

#### Step-by-step walkthrough

1. Click **🧪 Simulator** in the left sidebar (labelled **NEW**).

2. **What the simulator controls show:**

   ```
   ┌────────────────────────────────────────────┐
   │  Mode:  [CLEAN] [NOISY] [CHAOS] [SCENARIO] │  ← mode selector buttons
   │                                            │
   │  Failure rate:  ●──────────  18%           │  ← slider (0–50%)
   │  Ticks / sec:   ●──────────  50            │  ← slider (1–500)
   │                                            │
   │  [Apply Config]  [■ Stop]  or  [▶ Start]   │  ← action buttons
   │                                            │
   │  Live Status:                              │
   │    Mode: CLEAN   Ticks sent: 1,234         │
   │    Failures injected: 0   Ticks/sec: 50    │
   └────────────────────────────────────────────┘
   ```

   - **Mode buttons:** CLEAN, NOISY, CHAOS, SCENARIO — clicking one selects that mode (highlighted)
   - **Failure rate slider:** 0–50% of ticks will be injected with failures (only active in NOISY/CHAOS/SCENARIO modes)
   - **Ticks per second slider:** 1–500 ticks per second
   - **SCENARIO mode:** when selected, a **scenario dropdown** appears below the mode row listing all 12 available failure scenarios
   - **Apply Config button:** updates a running simulator with new settings — appears as **"✓ Applied!"** briefly after clicking
   - **▶ Start button:** starts the simulator (shown when simulator is stopped)
   - **■ Stop button:** stops the simulator (shown when simulator is running)
   - **Live Status section:** shows current mode, ticks sent, total failures injected, and current ticks/sec — updates in real time via polling

3. **How to start the simulator from the UI:**
   - Select a mode (e.g. CLEAN)
   - Optionally adjust the sliders
   - Click **▶ Start**
   - The button changes to **■ Stop** and the Live Status section appears showing ticks sent counting up

4. **How to switch modes without stopping:**
   - Click a different mode button (e.g. CHAOS)
   - The selected mode button highlights. An **"⚠ Not applied yet"** warning appears below the buttons.
   - Click **Apply Config**
   - The warning disappears, and the button briefly shows **"✓ Applied!"**
   - The Live Status Mode field updates to the new mode
   - Navigate to 📊 Validation — watch cards change within 10 seconds

5. **How to test SCENARIO mode from UI:**
   - Click **SCENARIO** mode button — a scenario dropdown appears below
   - Select a scenario from the dropdown (e.g. **PRICE_SPIKE**)
   - Optionally add a hint: below the dropdown you will see guidance: "① Select a scenario above  ② Click Apply Config"
   - Click **Apply Config**
   - Navigate to 📊 Validation — the **Accuracy** card should turn WARN or FAIL within 15 seconds

6. **How to stop the simulator:**
   - Click **■ Stop**
   - The button changes back to **▶ Start**
   - The Live Status section shows the simulator is no longer running
   - Ticks stop flowing — the 📡 Live Feed table freezes

7. **Simulator stats shown in Live Status:**
   - **Mode:** current mode (CLEAN / NOISY / CHAOS / SCENARIO)
   - **Ticks sent:** cumulative count of all ticks sent since last start (comma-formatted for readability)
   - **Failures injected:** total count of failure ticks injected (broken down by type if expanded)
   - **Ticks / sec:** current configured rate

---

**UI-33** — Simulator starts and Live Status appears  
**PASS:** Clicking ▶ Start causes the Live Status section to appear. Ticks sent count increments visibly.  
**FAIL:** Clicking Start has no effect, or Live Status section stays blank.

**UI-34** — Mode change applies and reflects in validators  
**PASS:** Switching from CLEAN → CHAOS and clicking Apply Config: the Live Status Mode field shows "CHAOS", and within 30 seconds the 📊 Validation tab shows at least one non-PASS card.  
**FAIL:** Mode change has no effect on validation cards even after 60 seconds.

**UI-35** — SCENARIO mode shows scenario dropdown  
**PASS:** Clicking SCENARIO mode button reveals a dropdown listing at least 12 scenario names (PRICE_SPIKE, NEGATIVE_PRICE, SEQUENCE_GAP, etc.).  
**FAIL:** No dropdown appears, or dropdown is empty.

**UI-36** — Simulator stop halts tick flow  
**PASS:** After clicking ■ Stop, the 📡 Live Feed table stops receiving new rows within 3 seconds. Ticks sent count on Live Status stops incrementing.  
**FAIL:** Ticks continue to arrive after Stop is clicked.

---

### 15.9 Compliance Tab ⚖️

#### Step-by-step walkthrough

1. Click **⚖️ Compliance** in the left sidebar (labelled **NEW**).

2. **What MiFID II compliance checks are shown:**

   The panel is titled **"MiFID II Compliance"** and lists 6 regulatory checks:

   | # | Check label | What it measures |
   |---|-------------|-----------------|
   | 1 | ART 27 — Timestamp Accuracy | LATENCY p95 < 100ms |
   | 2 | ART 64 — Pre-Trade Transparency | ACCURACY validator status |
   | 3 | ART 65 — Post-Trade Reporting | COMPLETENESS gap event count |
   | 4 | RTS 22 — Trade ID Uniqueness | ORDERING violations |
   | 5 | Audit Trail Completeness | COMPLETENESS rate ≥ 99.99% |
   | 6 | OHLC Reconciliation | STATEFUL validator status |

3. **How to read each check:**
   - Each row shows the check label on the left and a status badge on the right: **PASS** (green), **WARN** (amber), or **FAIL** (red)
   - A dash `—` means the system has not received enough data yet to evaluate the check

4. **What causes each check to fail:**

   | Check | Root cause of FAIL |
   |-------|-------------------|
   | ART 27 Timestamp Accuracy | p95 latency > 100ms — use STALE_TIMESTAMP scenario to trigger |
   | ART 64 Pre-Trade Transparency | ACCURACY validator is FAIL — use PRICE_SPIKE scenario |
   | ART 65 Post-Trade Reporting | Sequence gaps detected — use SEQUENCE_GAP scenario |
   | RTS 22 Trade ID Uniqueness | Out-of-order sequence numbers — use DUPLICATE_SEQUENCE scenario |
   | Audit Trail Completeness | Completeness rate drops below 99.99% — use DROPPED_TICKS scenario |
   | OHLC Reconciliation | STATEFUL validator FAIL — use PRICE_SPIKE (corrupts OHLC) |

5. **PASS criteria:** All 6 rows show green PASS in CLEAN mode after running for 30+ seconds.

6. **FAIL criteria:** One or more rows show red FAIL. The row label tells you which MiFID II article is failing, and the table above maps it to the triggering validator.

---

### 15.10 Browser Console Checks

#### Step-by-step walkthrough

1. Press **F12** → click the **Console** tab.

2. **What to look for:**

   | Console output | What it means | Action |
   |---------------|---------------|--------|
   | 🔴 Red error message | A JavaScript error occurred | Always FAIL — investigate |
   | "Failed to fetch" | Backend is unreachable | Check backend is running on port 8082 |
   | "Cannot read properties of undefined" | A UI bug — data is null when UI tries to read it | Always FAIL |
   | "Each child in a list should have a unique 'key'" | React rendering issue with list items | FAIL in production; acceptable in dev |
   | 🟡 Yellow warning | Potential issue | Investigate; some are acceptable |
   | `[vite] connected` or `[vite] hmr` messages | Vite hot-module reload — dev only | Acceptable, ignore |

3. **Acceptable console output in a healthy app:**
   - `[vite] connected.` — Vite dev server connected (dev mode only)
   - `[vite] server restarted.` — Vite restarted (dev mode only)
   - React DevTools suggestion banner (only appears once on first load)

4. **Unacceptable console output (always FAIL):**
   - Any message in **red**
   - "Failed to fetch" (means API is not reachable)
   - "Cannot read properties of undefined (reading 'X')"
   - "Uncaught TypeError" / "Uncaught ReferenceError"
   - "SyntaxError" in a fetched response (usually means the API returned HTML instead of JSON)

5. **How to do a console health check:**
   - Open F12 Console
   - Click the 🚫 (clear) button to clear existing messages
   - Run through all 8 tabs — click each tab, wait 5 seconds, move to the next
   - After visiting all tabs, check the console
   - **PASS:** Zero red messages after visiting all tabs
   - **FAIL:** Any red message appeared

---

### 15.11 SSE Connection Visual Check in Network Tab

#### Step-by-step walkthrough

1. Press **F12** → click the **Network** tab.

2. In the filter bar at the top of the Network tab, type **`stream`** to filter requests to only those matching SSE endpoints.

3. **What you should see:**
   - 5 requests, one per SSE endpoint:
     - `/api/stream/ticks`
     - `/api/stream/validation`
     - `/api/stream/latency`
     - `/api/stream/throughput`
     - `/api/stream/alerts`
   - Each request should show a status of **pending** (Chrome shows a spinning indicator in the Status column; this is normal and correct for live SSE connections)

4. **How to inspect a live SSE stream:**
   - Click on `/api/stream/ticks` in the Network tab
   - A detail pane opens on the right
   - Click the **EventStream** sub-tab (Chrome) — in Firefox this may be labelled **Messages**
   - You will see a scrolling list of events. Each row shows:
     - **Event name** (e.g. `tick`)
     - **Data** — the JSON payload for that event (e.g. `{"symbol":"BTCUSDT","price":"44567.00",...}`)
     - **Time** — when the event arrived in the browser
   - New rows appear continuously while ticks are flowing

5. **PASS criteria:**
   - All 5 SSE endpoints are visible in the Network tab with `pending` status
   - The EventStream tab shows events arriving at least once every 2 seconds for the `/api/stream/ticks` endpoint

6. **FAIL criteria:**
   - One or more SSE endpoints are missing from the Network tab (check if the frontend is connecting at all)
   - All SSE connections show a 4xx or 5xx status (connection refused)
   - EventStream tab is empty and stays empty for more than 10 seconds while simulator is running

---

### 15.12 End-to-End Visual Walkthrough (Full Scenario)

Use this section as a single QA script that exercises the entire UI from start to finish. Follow every step in order. Record PASS or FAIL at each numbered step.

**Prerequisites:** Backend running on port 8082. Frontend running on port 5174. Browser DevTools open on the Console tab (F12).

---

**Step 1** — Open the dashboard  
Open http://localhost:5174 in Chrome.  
**PASS:** Dashboard loads within 3 seconds. Left sidebar shows 8 tabs.  
**FAIL:** Blank screen, 404, or "cannot connect" error.

**Step 2** — Verify connections  
Click **🔌 Connections**. Verify at least one feed shows 🟢 CONNECTED.  
**PASS:** At least one row shows CONNECTED with a green icon.  
**FAIL:** All rows show DISCONNECTED, or the list is empty.

**Step 3** — Verify all cards PASS in CLEAN mode  
Click **📊 Validation**. Confirm the simulator is in CLEAN mode (check 🧪 Simulator tab first if unsure — see Step 4b). Wait 20 seconds.  
**PASS:** All 8 validator cards show ✅ PASS. Overall banner is green.  
**FAIL:** Any card shows WARN or FAIL in CLEAN mode after 30 seconds.

**Step 4** — Switch simulator to CHAOS mode  
Click **🧪 Simulator**. Click the **CHAOS** mode button. Set ticks per second to 100 using the slider. Click **Apply Config**. Wait for "✓ Applied!" confirmation.  
**PASS:** Live Status section shows Mode = CHAOS. Ticks sent count is increasing.  
**FAIL:** Mode still shows CLEAN in Live Status, or Apply Config has no effect.

**Step 5** — Watch validation cards degrade  
Click **📊 Validation**. Wait up to 30 seconds.  
**PASS:** At least 3 validator cards change to ⚠️ WARN or ❌ FAIL. Overall banner changes colour.  
**FAIL:** All cards remain green after 60 seconds of CHAOS mode.

**Step 6** — Verify alerts appear  
Click **🔔 Alerts**. The tab badge should show a non-zero number.  
**PASS:** At least 2 new alert cards visible with WARN or CRITICAL severity badges.  
**FAIL:** No alerts after 60 seconds of CHAOS mode.

**Step 7** — Acknowledge all alerts  
Click **"Acknowledge All"** button.  
**PASS:** All alert cards grey out. The red badge count on the 🔔 Alerts tab drops to 0.  
**FAIL:** Some cards remain unacknowledged, or the badge count does not update.

**Step 8** — Start a recording session  
Click **💾 Sessions**. Click **"+ New Recording"**. Enter name: **"UI Walkthrough Test"**. Select the active simulator feed. Click **Start Recording**.  
**PASS:** A new session row appears with RECORDING status badge.  
**FAIL:** Error dialog, or no new row appears.

**Step 9** — Verify tick count increases  
Wait 15 seconds while watching the RECORDING session row.  
**PASS:** The tick count on the row increases from 0 to a non-zero number within 15 seconds.  
**FAIL:** Tick count stays at 0. **(Critical regression — this was a previously fixed bug.)**

**Step 10** — Stop the recording  
Click **"■ Stop Recording"** on the active session.  
**PASS:** Status badge changes to COMPLETED (green). Final tick count is shown and is > 0.  
**FAIL:** Status changes to FAILED, or tick count is 0.

**Step 11** — Export as CSV  
On the COMPLETED session row, click **CSV** export.  
**PASS:** A file downloads (e.g. `session-1.csv`). Opening it shows a header row and data rows.  
**FAIL:** No file downloads, or file is empty.

**Step 12** — Switch simulator back to CLEAN mode  
Click **🧪 Simulator**. Click the **CLEAN** mode button. Click **Apply Config**.  
**PASS:** Live Status shows Mode = CLEAN.  
**FAIL:** Mode update fails.

**Step 13** — Verify validators recover to PASS  
Click **📊 Validation**. Wait up to 30 seconds.  
**PASS:** All 8 cards return to ✅ PASS and the Overall banner turns green.  
**FAIL:** One or more cards remain FAIL even after 60 seconds of CLEAN mode.

**Step 14** — Browser console health check  
Click the **Console** tab in F12 DevTools.  
**PASS:** Zero red error messages throughout the entire walkthrough.  
**FAIL:** Any red error was logged at any point during Steps 1–13.

**Step 15** — Final screenshot  
Take a screenshot of the full dashboard in CLEAN mode with all cards PASS.  
**PASS:** Screenshot shows green Overall banner, all 8 green PASS cards, and no console errors.  
**FAIL:** Unable to reach this step cleanly.

---

**Full walkthrough PASS criteria checklist:**

- [ ] All 8 validator cards visible and correctly labelled
- [ ] Status badges changed colour when switching simulator to CHAOS mode
- [ ] Alert tab badge count increased during CHAOS mode
- [ ] Acknowledge All cleared the badge count to 0
- [ ] Session tick count > 0 after recording (no regression)
- [ ] Status changed to COMPLETED after stopping recording
- [ ] CSV export downloaded and contained data
- [ ] All validator cards returned to PASS after switching back to CLEAN mode
- [ ] Zero red errors in browser console throughout the walkthrough

---

## Quick Test Execution Checklist

Use this checklist to track progress through a full test run:

### Smoke Tests
- [ ] S-01: Dashboard loads
- [ ] S-02: Feed list accessible
- [ ] S-03: Validation cards visible
- [ ] S-04: SSE connected indicator
- [ ] S-05: Backend API responds

### Feed Management
- [ ] F-01 through F-14

### Simulator
- [ ] SIM-01: Start simulator
- [ ] SIM-02: CLEAN baseline
- [ ] SIM-03: NOISY mode
- [ ] SIM-04: CHAOS mode
- [ ] SIM-05-A through SIM-05-L: All 12 scenarios
- [ ] SIM-06: Reset to CLEAN
- [ ] SIM-07: Scenario list
- [ ] SIM-08: Stop simulator

### Validators (one set per validator)
- [ ] V-ACC-01 through V-ACC-04
- [ ] V-LAT-01 through V-LAT-03
- [ ] V-COM-01 through V-COM-04
- [ ] V-ORD-01 through V-ORD-02
- [ ] V-THR-01 through V-THR-02
- [ ] V-REC-01 through V-REC-02
- [ ] V-SUB-01 through V-SUB-02
- [ ] V-STA-01 through V-STA-02

### Sessions
- [ ] SR-01 through SR-10

### Replay
- [ ] REP-01 through REP-08

### Alerts
- [ ] AL-01 through AL-07

### SSE Streams
- [ ] SSE-01 through SSE-08

### Configuration
- [ ] VC-01 through VC-06

### Error Handling
- [ ] ERR-01 through ERR-10

### Advanced / Stress
- [ ] ADV-01 through ADV-05

### Regression
- [ ] REG-01 through REG-06
- [ ] REG-07: AccuracyValidator detects all 3 violation types after method extraction
- [ ] REG-08: SubscriptionValidator tracks subscribe latency after computeIfAbsent refactor
- [ ] REG-09: LVWRChaosSimulator emits all failure types after emitTick switch refactor
- [ ] REG-10: SessionReplayer pause/resume/stop work after replayLoop method extraction

### UI Manual Testing (Section 15)

#### 15.2 Live Feed Tab
- [ ] UI-01: Tick table loads with columns
- [ ] UI-02: Ticks update in real time
- [ ] UI-03: Symbol filter works
- [ ] UI-04: Feed filter works
- [ ] UI-05: No undefined/blank values in tick rows

#### 15.3 Connections Tab
- [ ] UI-06: Feed list loads
- [ ] UI-07: Add feed dialog opens and closes
- [ ] UI-08: Connect a feed (Start button → CONNECTED)
- [ ] UI-09: Disconnect a feed (Stop button → DISCONNECTED)
- [ ] UI-10: Delete a feed (confirmation dialog + row removed)
- [ ] UI-11: Tick count visible on connected feed

#### 15.4 Validation Tab
- [ ] UI-12: 8 validator cards all visible and labelled correctly
- [ ] UI-13: Overall status banner correct in CLEAN mode (green PASS)
- [ ] UI-14: Cards change colour in CHAOS mode (at least 3 non-PASS)
- [ ] UI-15: Card click expands to show details
- [ ] UI-16: Feed scope dropdown filters cards
- [ ] UI-17: Value and Threshold shown on metric-bearing cards

#### 15.5 Latency Tab
- [ ] UI-18: Latency chart renders with axes and data line
- [ ] UI-19: Chart updates in real time
- [ ] UI-20: Latency spike visible after STALE_TIMESTAMP scenario

#### 15.6 Sessions Tab
- [ ] UI-21: Session list loads
- [ ] UI-22: New recording starts (dialog + new row)
- [ ] UI-23: Tick count increases during recording (regression check)
- [ ] UI-24: Stop recording shows COMPLETED status
- [ ] UI-25: CSV export downloads correctly
- [ ] UI-26: Replay shows results (ticksReplayed > 0)
- [ ] UI-27: Delete session removes the row

#### 15.7 Alerts Tab
- [ ] UI-28: Alerts appear during CHAOS mode
- [ ] UI-29: Tab badge count is accurate
- [ ] UI-30: Acknowledge single alert updates badge count
- [ ] UI-31: Acknowledge all clears badge to 0
- [ ] UI-32: Delete alert removes card

#### 15.8 Simulator Tab
- [ ] UI-33: Simulator starts and Live Status appears
- [ ] UI-34: Mode change applies and reflects in validators
- [ ] UI-35: SCENARIO mode shows scenario dropdown (12+ scenarios)
- [ ] UI-36: Simulator stop halts tick flow

#### 15.12 End-to-End Visual Walkthrough
- [ ] E2E Step 1: Dashboard loads
- [ ] E2E Step 2: Feed CONNECTED
- [ ] E2E Step 3: All 8 cards PASS in CLEAN mode
- [ ] E2E Step 4: CHAOS mode applied
- [ ] E2E Step 5: ≥3 cards degrade to WARN/FAIL
- [ ] E2E Step 6: Alerts appear
- [ ] E2E Step 7: Acknowledge All clears badge to 0
- [ ] E2E Step 8: New recording starts
- [ ] E2E Step 9: Tick count > 0 during recording
- [ ] E2E Step 10: Recording stops with COMPLETED status
- [ ] E2E Step 11: CSV export downloads
- [ ] E2E Step 12: Simulator returns to CLEAN mode
- [ ] E2E Step 13: All cards recover to PASS
- [ ] E2E Step 14: Zero red errors in browser console
- [ ] E2E Step 15: Final screenshot taken

---

*End of QA Manual Testing Guide*
