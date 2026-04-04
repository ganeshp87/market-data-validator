import React, { useState, useEffect } from 'react';
import useSSE from '../hooks/useSSE';

const AREAS = ['ACCURACY', 'ORDERING', 'LATENCY', 'COMPLETENESS', 'THROUGHPUT', 'STATEFUL', 'RECONNECTION', 'SUBSCRIPTION'];
const AREA_NAMES = {
  ACCURACY: 'Accuracy', ORDERING: 'Ordering', LATENCY: 'Latency',
  COMPLETENESS: 'Completeness', THROUGHPUT: 'Throughput', STATEFUL: 'Stateful',
  RECONNECTION: 'Reconnection', SUBSCRIPTION: 'Subscription',
};

export default function ValidatorSummary() {
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

  const { latest } = useSSE(sseUrl, 'validation', { maxItems: 1 });
  const results = latest?.results || {};

  const scopeLabel = selectedFeed
    ? `Feed: ${feeds.find(f => f.id === selectedFeed)?.name || selectedFeed}`
    : 'Global (all feeds)';

  return (
    <div className="panel-section">
      <div className="panel-label" style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
        <span>8 Validators — live state</span>
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
        <span style={{ fontSize: '0.8em', opacity: 0.7 }}>Scope: {scopeLabel}</span>
      </div>
      <div className="validator-grid">
        {AREAS.map((area) => {
          const r = results[area];
          const status = r?.status || 'PASS';
          const cls = status === 'FAIL' ? 'fail' : status === 'WARN' ? 'warn' : 'pass';
          return (
            <div key={area} className={`v-card ${cls}`}>
              <div className="v-name">{AREA_NAMES[area]}</div>
              <div className="v-status">{r?.message || 'waiting'}</div>
              <div className="v-metric">{formatMetric(area, r)}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function formatMetric(area, result) {
  if (!result) return '—';
  const m = result.metric;
  if (m === undefined || m === null) return '—';
  switch (area) {
    case 'LATENCY': return `${Math.round(Number(m)).toLocaleString()}ms`;
    case 'THROUGHPUT': return `${Math.round(Number(m)).toLocaleString()}/s`;
    case 'COMPLETENESS': {
      const gapEvents = result.details?.gapEventCount;
      const missingSequenceCount = result.details?.missingSequenceCount ?? result.details?.gapCount;
      const rate = `${Number(m).toFixed(2)}%`;
      if (gapEvents != null || missingSequenceCount != null) {
        const formattedGapEvents = gapEvents == null ? '0' : Math.round(gapEvents).toLocaleString();
        const formattedMissing = missingSequenceCount == null ? '0' : Math.round(missingSequenceCount).toLocaleString();
        return `${rate} | Gap events: ${formattedGapEvents} | Missing seqNums: ${formattedMissing}`;
      }
      return rate;
    }
    default: return `${Number(m).toFixed(2)}`;
  }
}
