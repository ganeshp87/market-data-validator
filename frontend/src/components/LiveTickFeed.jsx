import React, { useState, useRef, useEffect, useCallback } from 'react';
import useSSE from '../hooks/useSSE';

/**
 * LiveTickFeed — Auto-scrolling table of incoming market data ticks.
 *
 * Blueprint Section 6.2:
 *   - Columns: Time, Symbol, Price, Volume, Seq, Latency
 *   - Auto-scrolls, keeps last 500 rows
 *   - Pause button stops auto-scroll
 *   - Filter by symbol
 *   - Export visible data
 *
 * Uses: useSSE('/api/stream/ticks', 'tick') for live data
 */

const MAX_ROWS = 500;

export default function LiveTickFeed() {
  const [paused, setPaused] = useState(false);
  const [symbolFilter, setSymbolFilter] = useState('');
  const tableEndRef = useRef(null);

  // Build URL with optional symbol filter
  const url = symbolFilter
    ? `/api/stream/ticks?symbol=${encodeURIComponent(symbolFilter)}`
    : '/api/stream/ticks';

  const { data, connected, error, clear } = useSSE(url, 'tick', {
    maxItems: MAX_ROWS,
  });

  // Auto-scroll to bottom when new data arrives (unless paused)
  useEffect(() => {
    if (!paused && tableEndRef.current && tableEndRef.current.scrollIntoView) {
      tableEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [data.length, paused]);

  const handleExport = useCallback(() => {
    const header = 'Time,Symbol,Price,Volume,Seq,Latency(ms)\n';
    const rows = data
      .map(
        (t) =>
          `${formatTime(t.exchangeTimestamp)},${t.symbol},${t.price},${t.volume},${t.sequenceNum},${t.latency}`
      )
      .join('\n');

    const blob = new Blob([header + rows], { type: 'text/csv' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `ticks-${Date.now()}.csv`;
    a.click();
    URL.revokeObjectURL(a.href);
  }, [data]);

  return (
    <div className="live-tick-feed">
      <div className="feed-toolbar">
        <div className="feed-status">
          <span className={`status-dot ${connected ? 'green' : 'red'}`} />
          <span>{connected ? 'Live' : 'Disconnected'}</span>
          {error && <span className="feed-error">{error.message}</span>}
          <span className="tick-count">{data.length} ticks</span>
        </div>

        <div className="feed-controls">
          <input
            type="text"
            className="symbol-filter"
            placeholder="Filter by symbol…"
            value={symbolFilter}
            onChange={(e) => setSymbolFilter(e.target.value.toUpperCase())}
            aria-label="Filter by symbol"
          />
          <button
            className={`toolbar-btn ${paused ? 'active' : ''}`}
            onClick={() => setPaused((p) => !p)}
          >
            {paused ? '▶ Resume' : '⏸ Pause'}
          </button>
          <button className="toolbar-btn" onClick={clear}>
            🗑 Clear
          </button>
          <button
            className="toolbar-btn"
            onClick={handleExport}
            disabled={data.length === 0}
          >
            📥 Export
          </button>
        </div>
      </div>

      <div className="tick-table-wrapper">
        <table className="tick-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Symbol</th>
              <th>Price</th>
              <th>Volume</th>
              <th>Seq</th>
              <th>Latency</th>
            </tr>
          </thead>
          <tbody>
            {data.map((tick, i) => (
              <tr key={`${tick.sequenceNum}-${i}`} className="tick-row">
                <td className="col-time">{formatTime(tick.exchangeTimestamp)}</td>
                <td className="col-symbol">{tick.symbol}</td>
                <td className="col-price">{formatPrice(tick.price)}</td>
                <td className="col-volume">{tick.volume}</td>
                <td className="col-seq">{tick.sequenceNum}</td>
                <td className={`col-latency ${latencyClass(tick.latency)}`}>
                  {tick.latency}ms
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div ref={tableEndRef} />
      </div>
    </div>
  );
}

// --- Formatters ---

function formatTime(isoString) {
  if (!isoString) return '—';
  try {
    const d = new Date(isoString);
    return d.toLocaleTimeString(undefined, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      fractionalSecondDigits: 3,
    });
  } catch {
    return isoString;
  }
}

function formatPrice(priceStr) {
  if (!priceStr) return '—';
  const num = Number(priceStr);
  if (Number.isNaN(num)) return priceStr;
  return num.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 8,
  });
}

function latencyClass(ms) {
  if (ms <= 50) return 'latency-good';
  if (ms <= 200) return 'latency-warn';
  return 'latency-bad';
}
