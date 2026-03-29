-- Market Data Stream Validator — Database Schema
-- SQLite, all prices stored as TEXT for BigDecimal precision

-- Feed connections
CREATE TABLE IF NOT EXISTS connections (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    adapter_type TEXT NOT NULL,          -- BINANCE, FINNHUB, GENERIC
    symbols TEXT NOT NULL,               -- JSON array: ["BTCUSDT","ETHUSDT"]
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Recording sessions
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

-- Individual ticks (the bulk data)
CREATE TABLE IF NOT EXISTS ticks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER,                  -- NULL if not recording
    feed_id TEXT NOT NULL,
    symbol TEXT NOT NULL,
    price TEXT NOT NULL,                 -- Stored as TEXT for BigDecimal precision
    bid TEXT,
    ask TEXT,
    volume TEXT,
    sequence_num INTEGER,
    exchange_ts TEXT NOT NULL,           -- ISO-8601 instant
    received_ts TEXT NOT NULL,           -- ISO-8601 instant
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_ticks_session ON ticks(session_id);
CREATE INDEX IF NOT EXISTS idx_ticks_symbol ON ticks(symbol, exchange_ts);
CREATE INDEX IF NOT EXISTS idx_ticks_feed ON ticks(feed_id, exchange_ts);

-- Validation results (periodic snapshots)
CREATE TABLE IF NOT EXISTS validations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    area TEXT NOT NULL,                  -- ACCURACY, LATENCY, etc.
    status TEXT NOT NULL,                -- PASS, WARN, FAIL
    message TEXT,
    metric REAL,
    threshold REAL,
    details TEXT,                        -- JSON blob
    created_at TEXT NOT NULL
);

-- Alerts (threshold breaches)
CREATE TABLE IF NOT EXISTS alerts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    area TEXT NOT NULL,
    severity TEXT NOT NULL,              -- INFO, WARN, CRITICAL
    message TEXT NOT NULL,
    acknowledged INTEGER DEFAULT 0,
    created_at TEXT NOT NULL
);
