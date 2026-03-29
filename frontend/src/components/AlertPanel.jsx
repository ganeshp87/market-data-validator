import React, { useState, useEffect, useCallback, useRef } from 'react';
import useSSE from '../hooks/useSSE';

const SEVERITY_ICONS = { CRITICAL: '🔴', WARN: '🟡', INFO: '🟢' };
const SEVERITY_ORDER = { CRITICAL: 0, WARN: 1, INFO: 2 };
const POLL_INTERVAL = 5000;

function formatTime(isoStr) {
  if (!isoStr) return '';
  return new Date(isoStr).toLocaleTimeString();
}

export default function AlertPanel() {
  const [alerts, setAlerts] = useState([]);
  const [error, setError] = useState(null);
  const [filter, setFilter] = useState('all'); // all, unacknowledged
  const pollRef = useRef(null);

  // SSE for live alerts
  const { latest: liveAlert } = useSSE('/api/stream/alerts', 'alert', { maxItems: 1 });

  // Fetch alerts from REST API
  const fetchAlerts = useCallback(async () => {
    try {
      const res = await fetch('/api/alerts');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setAlerts(data);
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    fetchAlerts();
    pollRef.current = setInterval(fetchAlerts, POLL_INTERVAL);
    return () => clearInterval(pollRef.current);
  }, [fetchAlerts]);

  // When a new live alert arrives, prepend it
  useEffect(() => {
    if (liveAlert && liveAlert.id) {
      setAlerts((prev) => {
        if (prev.some((a) => a.id === liveAlert.id)) return prev;
        return [liveAlert, ...prev];
      });
    }
  }, [liveAlert]);

  // ── Actions ─────────────────────────────────────────

  const acknowledgeAlert = async (id) => {
    try {
      await fetch(`/api/alerts/${id}/acknowledge`, { method: 'POST' });
      setAlerts((prev) =>
        prev.map((a) => (a.id === id ? { ...a, acknowledged: true } : a))
      );
    } catch (err) {
      setError(err.message);
    }
  };

  const acknowledgeAll = async () => {
    try {
      await fetch('/api/alerts/acknowledge-all', { method: 'POST' });
      setAlerts((prev) => prev.map((a) => ({ ...a, acknowledged: true })));
    } catch (err) {
      setError(err.message);
    }
  };

  const deleteAlert = async (id) => {
    try {
      await fetch(`/api/alerts/${id}`, { method: 'DELETE' });
      setAlerts((prev) => prev.filter((a) => a.id !== id));
    } catch (err) {
      setError(err.message);
    }
  };

  const clearAll = async () => {
    try {
      await fetch('/api/alerts', { method: 'DELETE' });
      setAlerts([]);
    } catch (err) {
      setError(err.message);
    }
  };

  // ── Derived state ───────────────────────────────────

  const unacknowledgedCount = alerts.filter((a) => !a.acknowledged).length;
  const displayed = filter === 'unacknowledged'
    ? alerts.filter((a) => !a.acknowledged)
    : alerts;

  // Sort: unacknowledged first, then by severity, then by time DESC
  const sorted = [...displayed].sort((a, b) => {
    if (a.acknowledged !== b.acknowledged) return a.acknowledged ? 1 : -1;
    const sevA = SEVERITY_ORDER[a.severity] ?? 3;
    const sevB = SEVERITY_ORDER[b.severity] ?? 3;
    if (sevA !== sevB) return sevA - sevB;
    return 0; // Already DESC from server
  });

  return (
    <div className="ap-container">
      <div className="ap-header">
        <h2>🔔 Alerts</h2>
        <div className="ap-header-actions">
          {unacknowledgedCount > 0 && (
            <span className="ap-badge">{unacknowledgedCount}</span>
          )}
          <button
            className="toolbar-btn"
            onClick={() => setFilter(filter === 'all' ? 'unacknowledged' : 'all')}
          >
            {filter === 'all' ? 'Show Active' : 'Show All'}
          </button>
          <button
            className="toolbar-btn"
            onClick={acknowledgeAll}
            disabled={unacknowledgedCount === 0}
          >
            ✓ Ack All
          </button>
          <button
            className="toolbar-btn danger"
            onClick={clearAll}
            disabled={alerts.length === 0}
          >
            Clear All
          </button>
        </div>
      </div>

      {error && <div className="ap-error">{error}</div>}

      {sorted.length === 0 ? (
        <p className="ap-empty">No alerts.</p>
      ) : (
        <div className="ap-alert-list">
          {sorted.map((alert) => (
            <AlertCard
              key={alert.id}
              alert={alert}
              onAcknowledge={acknowledgeAlert}
              onDelete={deleteAlert}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function AlertCard({ alert, onAcknowledge, onDelete }) {
  const icon = SEVERITY_ICONS[alert.severity] || '⚪';
  const sevClass = alert.severity ? alert.severity.toLowerCase() : '';

  return (
    <div className={`ap-card ${sevClass} ${alert.acknowledged ? 'acknowledged' : ''}`}>
      <div className="ap-card-header">
        <span className="ap-icon">{icon}</span>
        <span className={`ap-severity ${sevClass}`}>{alert.severity}</span>
        <span className="ap-area">{alert.area}</span>
        <span className="ap-time">{formatTime(alert.createdAt)}</span>
      </div>
      <div className="ap-card-body">
        <p className="ap-message">{alert.message}</p>
      </div>
      <div className="ap-card-actions">
        {!alert.acknowledged && (
          <button className="toolbar-btn primary" onClick={() => onAcknowledge(alert.id)}>
            ✓ Acknowledge
          </button>
        )}
        <button className="toolbar-btn danger" onClick={() => onDelete(alert.id)}>
          🗑
        </button>
      </div>
    </div>
  );
}
