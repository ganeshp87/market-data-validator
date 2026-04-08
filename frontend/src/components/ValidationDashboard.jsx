import React, { useState, useEffect } from 'react';
import useSSE from '../hooks/useSSE';

/**
 * ValidationDashboard — 9-area pass/warn/fail cards with live SSE updates.
 *
 * Blueprint Section 6.2:
 *   - 9 cards (ACCURACY, LATENCY, COMPLETENESS, RECONNECTION,
 *     THROUGHPUT, ORDERING, SUBSCRIPTION, STATEFUL, SOURCE)
 *   - Each card: status icon, area name, metric, threshold, message
 *   - Click card → expands to show detailed results
 *   - Overall status banner at top
 *
 * Uses: useSSE('/api/stream/validation', 'validation') for live updates
 * Fix 8: Feed scope selector dropdown filters metrics per feed.
 */

const ALL_AREAS = [
  'ACCURACY', 'LATENCY', 'COMPLETENESS', 'RECONNECTION',
  'THROUGHPUT', 'ORDERING', 'SUBSCRIPTION', 'STATEFUL', 'SOURCE',
];

const STATUS_ICONS = {
  PASS: '✅',
  WARN: '⚠️',
  FAIL: '❌',
};

const STATUS_CLASSES = {
  PASS: 'status-pass',
  WARN: 'status-warn',
  FAIL: 'status-fail',
};

export default function ValidationDashboard() {
  const [feeds, setFeeds] = useState([]);
  const [selectedFeed, setSelectedFeed] = useState('');

  useEffect(() => {
    const poll = async () => {
      try {
        const res = await fetch('/api/validation/feeds');
        if (res.ok) setFeeds(await res.json());
      } catch { /* ignore */ }
    };
    poll();
    const interval = setInterval(poll, 5000);
    return () => clearInterval(interval);
  }, []);

  const sseUrl = selectedFeed
    ? `/api/stream/validation?feedId=${encodeURIComponent(selectedFeed)}`
    : '/api/stream/validation';

  const { latest, connected, error } = useSSE(
    sseUrl,
    'validation',
    { maxItems: 1 }
  );

  const results = latest?.results || {};
  const overallStatus = latest?.overallStatus || 'PASS';
  const ticksProcessed = latest?.ticksProcessed || 0;
  const rejectedCount = latest?.rejectedCount || 0;
  const duplicateCount = latest?.duplicateCount || 0;

  const scopeLabel = selectedFeed
    ? `Feed: ${feeds.find(f => f.id === selectedFeed)?.name || selectedFeed}`
    : 'Global (all feeds)';

  return (
    <div className="validation-dashboard">
      <div className="vd-header">
        <h2>Validation Dashboard</h2>
        <div className="vd-summary">
          <select
            className="sim-scenario-select"
            style={{ maxWidth: '220px', fontSize: '0.85em' }}
            value={selectedFeed}
            onChange={e => setSelectedFeed(e.target.value)}
          >
            <option value="">Global (all feeds)</option>
            {feeds.map(f => (
              <option key={f.id} value={f.id}>{f.name}</option>
            ))}
          </select>
          <span style={{ fontSize: '0.8em', opacity: 0.7, marginLeft: '6px' }}>Scope: {scopeLabel}</span>
          <span className={`vd-overall ${STATUS_CLASSES[overallStatus] || ''}`}>
            {STATUS_ICONS[overallStatus] || '⚪'} Overall: {overallStatus}
          </span>
          <span className="vd-ticks">{ticksProcessed.toLocaleString()} ticks processed</span>
          {rejectedCount > 0 && (
            <span className="vd-rejected">{rejectedCount.toLocaleString()} rejected</span>
          )}
          {duplicateCount > 0 && (
            <span className="vd-duplicates">{duplicateCount.toLocaleString()} duplicates</span>
          )}
          <span className={`status-dot ${connected ? 'green' : 'red'}`} />
        </div>
      </div>

      {error && <div className="cm-error">{error.message}</div>}

      <div className="vd-grid">
        {ALL_AREAS.map((area) => (
          <ValidationCard key={area} area={area} result={results[area]} />
        ))}
      </div>
    </div>
  );
}

// --- Validation Card ---

function ValidationCard({ area, result }) {
  const [expanded, setExpanded] = useState(false);

  const status = result?.status || 'PASS';
  const message = result?.message || 'Waiting for data…';
  const metric = result?.metric;
  const threshold = result?.threshold;
  const details = result?.details || {};

  return (
    <div
      className={`vd-card ${STATUS_CLASSES[status] || ''}`}
      onClick={() => setExpanded((e) => !e)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => e.key === 'Enter' && setExpanded((x) => !x)}
    >
      <div className="vd-card-header">
        <span className="vd-card-icon">{STATUS_ICONS[status] || '⚪'}</span>
        <span className="vd-card-area">{formatAreaName(area)}</span>
        <span className="vd-card-status">{status}</span>
      </div>

      <div className="vd-card-body">
        <span className="vd-card-message">{message}</span>
        {metric !== undefined && metric !== null && (
          <div className="vd-card-metrics">
            <span className="vd-metric">Value: {formatMetric(area, metric)}</span>
            {threshold !== undefined && threshold !== null && (
              <span className="vd-threshold">Threshold: {formatMetric(area, threshold)}</span>
            )}
          </div>
        )}
        {area === 'COMPLETENESS' && (details.gapEventCount != null || details.missingSequenceCount != null) && (
          <div className="vd-completeness-detail">
            <span>Gap events: {Number(details.gapEventCount ?? 0).toLocaleString()}</span>
            {' | '}
            <span>Missing seqNums: {Number(details.missingSequenceCount ?? 0).toLocaleString()}</span>
          </div>
        )}
      </div>

      {expanded && Object.keys(details).length > 0 && (
        <div className="vd-card-details">
          {Object.entries(details).map(([key, val]) => (
            <div key={key} className="vd-detail-row">
              <span className="vd-detail-key">{key}</span>
              <span className="vd-detail-val">{String(val)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// --- Formatters ---

function formatAreaName(area) {
  const names = {
    ACCURACY: 'Accuracy',
    LATENCY: 'Latency',
    COMPLETENESS: 'Completeness',
    RECONNECTION: 'Reconnection',
    THROUGHPUT: 'Throughput',
    ORDERING: 'Ordering',
    SUBSCRIPTION: 'Subscription',
    STATEFUL: 'Stateful',
    SOURCE: 'Source',
  };
  return names[area] || area;
}

function formatMetric(area, value) {
  if (value === undefined || value === null) return '—';
  switch (area) {
    case 'ACCURACY':
    case 'ORDERING':
    case 'STATEFUL':
      return `${Number(value).toFixed(2)}%`;
    case 'LATENCY':
      return `${Number(value).toFixed(0)}ms`;
    case 'THROUGHPUT':
      return `${Number(value).toLocaleString()} msg/s`;
    case 'COMPLETENESS':
      return `${Number(value).toFixed(2)}%`;
    case 'RECONNECTION':
    case 'SUBSCRIPTION':
      return String(value);
    case 'SOURCE':
      return `${Number(value).toFixed(2)}%`;
    default:
      return String(value);
  }
}
