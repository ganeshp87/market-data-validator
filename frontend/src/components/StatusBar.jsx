import React from 'react';
import useSSE from '../hooks/useSSE';

/**
 * StatusBar — Connection and throughput status strip at the bottom of the dashboard.
 *
 * Blueprint Section 6.1 wireframe:
 *   Status Bar: BTCUSDT: $45,123.45 | 12,340 msg/s | p95: 42ms
 *
 * Subscribes to 3 SSE streams:
 *   - /api/stream/ticks       → latest symbol + price
 *   - /api/stream/throughput  → messages/sec + peak
 *   - /api/stream/latency     → p95 latency
 */

export default function StatusBar() {
  const { latest: latestTick, connected: tickConnected } = useSSE(
    '/api/stream/ticks',
    'tick',
    { maxItems: 1 }
  );

  const { latest: throughput, connected: throughputConnected } = useSSE(
    '/api/stream/throughput',
    'throughput',
    { maxItems: 1 }
  );

  const { latest: latency, connected: latencyConnected } = useSSE(
    '/api/stream/latency',
    'latency',
    { maxItems: 1 }
  );

  const allConnected = tickConnected && throughputConnected && latencyConnected;
  const anyConnected = tickConnected || throughputConnected || latencyConnected;

  return (
    <footer className="status-bar" role="status">
      <div className="sb-section">
        <span className={`status-dot ${allConnected ? 'green' : anyConnected ? 'yellow' : 'red'}`} />
        <span className="sb-label">
          {allConnected ? 'Connected' : anyConnected ? 'Partial' : 'Disconnected'}
        </span>
      </div>

      <span className="sb-divider">|</span>

      <div className="sb-section">
        {latestTick ? (
          <>
            <span className="sb-symbol">{latestTick.symbol}</span>
            <span className="sb-price">${formatPrice(latestTick.price)}</span>
          </>
        ) : (
          <span className="sb-muted">No ticks</span>
        )}
      </div>

      <span className="sb-divider">|</span>

      <div className="sb-section">
        <span className="sb-value">
          {throughput ? `${Number(throughput.messagesPerSecond).toLocaleString()} msg/s` : '— msg/s'}
        </span>
        {throughput && throughput.peakPerSecond > 0 && (
          <span className="sb-muted">
            peak: {Number(throughput.peakPerSecond).toLocaleString()}
          </span>
        )}
      </div>

      <span className="sb-divider">|</span>

      <div className="sb-section">
        <span className="sb-value">
          {latency ? `p95: ${latency.p95}ms` : 'p95: —'}
        </span>
      </div>
    </footer>
  );
}

function formatPrice(priceStr) {
  if (!priceStr) return '—';
  const num = Number(priceStr);
  if (Number.isNaN(num)) return priceStr;
  return num.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}
