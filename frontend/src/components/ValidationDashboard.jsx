import React, { useState } from 'react';
import useSSE from '../hooks/useSSE';

/**
 * ValidationDashboard — 8-area pass/warn/fail cards with live SSE updates.
 *
 * Blueprint Section 6.2:
 *   - 8 cards (ACCURACY, LATENCY, COMPLETENESS, RECONNECTION,
 *     THROUGHPUT, ORDERING, SUBSCRIPTION, STATEFUL)
 *   - Each card: status icon, area name, metric, threshold, message
 *   - Click card → expands to show detailed results
 *   - Overall status banner at top
 *
 * Uses: useSSE('/api/stream/validation', 'validation') for live updates
 */

const ALL_AREAS = [
  'ACCURACY', 'LATENCY', 'COMPLETENESS', 'RECONNECTION',
  'THROUGHPUT', 'ORDERING', 'SUBSCRIPTION', 'STATEFUL',
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
  const { latest, connected, error } = useSSE(
    '/api/stream/validation',
    'validation',
    { maxItems: 1 }
  );

  const results = latest?.results || {};
  const overallStatus = latest?.overallStatus || 'PASS';
  const ticksProcessed = latest?.ticksProcessed || 0;

  return (
    <div className="validation-dashboard">
      <div className="vd-header">
        <h2>Validation Dashboard</h2>
        <div className="vd-summary">
          <span className={`vd-overall ${STATUS_CLASSES[overallStatus] || ''}`}>
            {STATUS_ICONS[overallStatus] || '⚪'} Overall: {overallStatus}
          </span>
          <span className="vd-ticks">{ticksProcessed.toLocaleString()} ticks processed</span>
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
      return `${Number(value)} gaps`;
    case 'RECONNECTION':
    case 'SUBSCRIPTION':
      return String(value);
    default:
      return String(value);
  }
}
