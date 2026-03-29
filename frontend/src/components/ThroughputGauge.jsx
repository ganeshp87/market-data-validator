import React from 'react';
import useSSE from '../hooks/useSSE';

/**
 * ThroughputGauge — Live throughput display with rolling history sparkline.
 *
 * Blueprint Phase 4/Section 9.5:
 *   Shows current msg/sec, rolling average, max seen, and drop indicator.
 *   Updates via /api/stream/throughput SSE endpoint every 1 second.
 *
 * Also polls /api/stream/validation for the THROUGHPUT validator result
 * to display the pass/warn/fail status.
 */

const MAX_HISTORY = 60; // 60 seconds of history for the sparkline

export default function ThroughputGauge() {
  const { data: history, latest, connected } = useSSE(
    '/api/stream/throughput',
    'throughput',
    { maxItems: MAX_HISTORY }
  );

  const { latest: validation } = useSSE(
    '/api/stream/validation',
    'validation',
    { maxItems: 1 }
  );

  const rate = latest ? Number(latest.messagesPerSecond) : 0;
  const peak = latest ? Number(latest.peakPerSecond) : 0;
  const total = latest ? Number(latest.totalMessages) : 0;

  // Extract THROUGHPUT validator status from validation stream
  const throughputResult = validation?.results?.THROUGHPUT;
  const status = throughputResult?.status || 'UNKNOWN';
  const dropDetected = throughputResult?.details?.dropDetected || false;

  // Build sparkline data from history
  const sparkData = history.map((d) => Number(d.messagesPerSecond));
  const sparkMax = sparkData.length > 0 ? Math.max(...sparkData, 1) : 1;

  return (
    <div className="throughput-gauge" data-testid="throughput-gauge">
      <h2>Throughput</h2>

      <div className="tg-status-row">
        <span className={`status-dot ${connected ? 'green' : 'red'}`} />
        <span>{connected ? 'Live' : 'Disconnected'}</span>
        <span className={`tg-badge tg-badge-${status.toLowerCase()}`}>
          {status}
        </span>
      </div>

      {/* Main rate display */}
      <div className="tg-rate" data-testid="tg-rate">
        <span className="tg-rate-value">{rate.toLocaleString()}</span>
        <span className="tg-rate-unit">msg/s</span>
      </div>

      {/* Stats grid */}
      <div className="tg-stats">
        <div className="tg-stat">
          <span className="tg-stat-label">Peak</span>
          <span className="tg-stat-value">{peak.toLocaleString()}</span>
        </div>
        <div className="tg-stat">
          <span className="tg-stat-label">Total</span>
          <span className="tg-stat-value">{total.toLocaleString()}</span>
        </div>
        <div className="tg-stat">
          <span className="tg-stat-label">History</span>
          <span className="tg-stat-value">{sparkData.length}s</span>
        </div>
      </div>

      {/* Drop alert */}
      {dropDetected && (
        <div className="tg-alert" data-testid="tg-drop-alert">
          ⚠️ Throughput drop detected
        </div>
      )}

      {/* Sparkline — simple bar chart */}
      <div className="tg-sparkline" data-testid="tg-sparkline" aria-label="Throughput history">
        {sparkData.map((val, i) => (
          <div
            key={i}
            className="tg-spark-bar"
            style={{ height: `${(val / sparkMax) * 100}%` }}
            title={`${val} msg/s`}
          />
        ))}
      </div>
    </div>
  );
}
