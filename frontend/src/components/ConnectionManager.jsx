import React, { useState, useEffect, useCallback } from 'react';

/**
 * ConnectionManager — Add, edit, remove, start/stop WebSocket feed connections.
 *
 * Blueprint Section 6.2:
 *   - Lists all connections with status indicator
 *   - Add Feed dialog (name, URL, type, symbols)
 *   - Start/Stop buttons per connection
 *   - Delete with confirmation
 *
 * API: FeedController at /api/feeds
 */

const ADAPTER_TYPES = ['BINANCE', 'FINNHUB', 'GENERIC', 'LVWR_T'];

const STATUS_ICONS = {
  CONNECTED: '🟢',
  DISCONNECTED: '🔴',
  RECONNECTING: '🟡',
  ERROR: '🔴',
};

export default function ConnectionManager() {
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showAddDialog, setShowAddDialog] = useState(false);

  // Fetch connections on mount and periodically
  const fetchConnections = useCallback(async () => {
    try {
      const res = await fetch('/api/feeds');
      if (!res.ok) throw new Error(`Failed to fetch feeds: ${res.status}`);
      const data = await res.json();
      setConnections(data);
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchConnections();
    const interval = setInterval(fetchConnections, 3000);
    return () => clearInterval(interval);
  }, [fetchConnections]);

  const handleStart = async (id) => {
    try {
      const res = await fetch(`/api/feeds/${id}/start`, { method: 'POST' });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `Start failed: ${res.status}`);
      }
      await fetchConnections();
    } catch (e) {
      setError(e.message);
    }
  };

  const handleStop = async (id) => {
    try {
      const res = await fetch(`/api/feeds/${id}/stop`, { method: 'POST' });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `Stop failed: ${res.status}`);
      }
      await fetchConnections();
    } catch (e) {
      setError(e.message);
    }
  };

  const handleDelete = async (id, name) => {
    if (!window.confirm(`Delete connection "${name}"?`)) return;
    try {
      const res = await fetch(`/api/feeds/${id}`, { method: 'DELETE' });
      if (!res.ok && res.status !== 204) {
        throw new Error(`Delete failed: ${res.status}`);
      }
      await fetchConnections();
    } catch (e) {
      setError(e.message);
    }
  };

  const handleAddComplete = () => {
    setShowAddDialog(false);
    fetchConnections();
  };

  if (loading) {
    return <div className="connection-manager"><p>Loading feeds…</p></div>;
  }

  return (
    <div className="connection-manager">
      <div className="cm-header">
        <h2>Connections</h2>
        <button className="toolbar-btn" onClick={() => setShowAddDialog(true)}>
          + Add Feed
        </button>
      </div>

      {error && <div className="cm-error">{error}</div>}

      {showAddDialog && (
        <AddFeedDialog
          onClose={() => setShowAddDialog(false)}
          onComplete={handleAddComplete}
        />
      )}

      {connections.length === 0 && (
        <p className="cm-empty">No feeds configured. Click "+ Add Feed" to get started.</p>
      )}

      <div className="cm-list">
        {connections.map((conn) => (
          <ConnectionCard
            key={conn.id}
            connection={conn}
            onStart={handleStart}
            onStop={handleStop}
            onDelete={handleDelete}
          />
        ))}
      </div>
    </div>
  );
}

// --- Connection Card ---

function ConnectionCard({ connection, onStart, onStop, onDelete }) {
  const { id, name, url, adapterType, symbols, status, tickCount } = connection;
  const isActive = status === 'CONNECTED' || status === 'RECONNECTING';

  return (
    <div className={`cm-card ${status.toLowerCase()}`}>
      <div className="cm-card-header">
        <span className="cm-status-icon">{STATUS_ICONS[status] || '⚪'}</span>
        <span className="cm-name">{name}</span>
        <span className="cm-type">{adapterType}</span>
        <span className="cm-status-text">{status}</span>
      </div>

      <div className="cm-card-body">
        <span className="cm-url" title={url}>
          {url.length > 40 ? url.substring(0, 40) + '…' : url}
        </span>
        <span className="cm-symbols">{(symbols || []).join(', ')}</span>
        {tickCount > 0 && (
          <span className="cm-ticks">{tickCount.toLocaleString()} ticks</span>
        )}
      </div>

      <div className="cm-card-actions">
        {isActive ? (
          <button className="toolbar-btn" onClick={() => onStop(id)}>
            ⏹ Stop
          </button>
        ) : (
          <button className="toolbar-btn" onClick={() => onStart(id)}>
            ▶ Start
          </button>
        )}
        <button
          className="toolbar-btn danger"
          onClick={() => onDelete(id, name)}
        >
          🗑 Delete
        </button>
      </div>
    </div>
  );
}

// --- Add Feed Dialog ---

function AddFeedDialog({ onClose, onComplete }) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [adapterType, setAdapterType] = useState('BINANCE');
  const [symbols, setSymbols] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);

    const symbolList = symbols
      .split(',')
      .map((s) => s.trim().toUpperCase())
      .filter(Boolean);

    try {
      const res = await fetch('/api/feeds', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name.trim(),
          url: url.trim(),
          adapterType,
          symbols: symbolList,
        }),
      });

      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `Add failed: ${res.status}`);
      }

      onComplete();
    } catch (e) {
      setError(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="cm-dialog-overlay" onClick={onClose}>
      <form
        className="cm-dialog"
        onClick={(e) => e.stopPropagation()}
        onSubmit={handleSubmit}
      >
        <h3>Add Feed</h3>

        {error && <div className="cm-error">{error}</div>}

        <label>
          Name
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Binance BTC"
            required
          />
        </label>

        <label>
          URL
          <input
            type="text"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="wss://stream.binance.com:9443/ws"
            required
          />
        </label>

        <label>
          Type
          <select value={adapterType} onChange={(e) => setAdapterType(e.target.value)}>
            {ADAPTER_TYPES.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </label>

        <label>
          Symbols (comma-separated)
          <input
            type="text"
            value={symbols}
            onChange={(e) => setSymbols(e.target.value)}
            placeholder="BTCUSDT, ETHUSDT"
            required
          />
        </label>

        <div className="cm-dialog-actions">
          <button type="button" className="toolbar-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="toolbar-btn primary" disabled={submitting}>
            {submitting ? 'Connecting…' : 'Connect'}
          </button>
        </div>
      </form>
    </div>
  );
}
