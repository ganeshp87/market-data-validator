import React, { useState, useEffect, useCallback } from 'react';

const ADAPTER_TYPES = ['BINANCE', 'FINNHUB', 'GENERIC', 'LVWR_T'];

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

  const handleDelete = async (conn) => {
    if (!window.confirm(`Delete connection "${conn.name}"?`)) return;
    try {
      const res = await fetch(`/api/feeds/${conn.id}`, { method: 'DELETE' });
      if (!res.ok) throw new Error(`Delete failed: ${res.status}`);
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
        <h2>Feed Connections</h2>
        <button className="toolbar-btn primary" onClick={() => setShowAddDialog(true)}>
          + Add Feed
        </button>
      </div>

      {error && <div className="cm-error">{error}</div>}

      {connections.length === 0 ? (
        <p className="cm-empty">No feeds configured. Click "+ Add Feed" to get started.</p>
      ) : (
        <div className="cm-list">
          {connections.map((conn) => (
            <div key={conn.id} className="cm-card">
              <div className="cm-card-header">
                <span className="cm-status-icon">{conn.status === 'CONNECTED' ? '🟢' : '🔴'}</span>
                <span className="cm-name">{conn.name}</span>
                <span className="cm-adapter-badge">{conn.adapterType}</span>
                <span className={`cm-status-label ${conn.status === 'CONNECTED' ? 'connected' : ''}`}>
                  {conn.status}
                </span>
              </div>
              <div className="cm-card-body">
                <span className="cm-symbols">{(conn.symbols || []).join(', ')}</span>
                <span className="cm-ticks">{conn.tickCount > 0 ? `${conn.tickCount.toLocaleString()} ticks` : ''}</span>
              </div>
              <div className="cm-card-actions">
                {conn.status !== 'CONNECTED' ? (
                  <button className="toolbar-btn" onClick={() => handleStart(conn.id)}>▶ Start</button>
                ) : (
                  <button className="toolbar-btn" onClick={() => handleStop(conn.id)}>⏹ Stop</button>
                )}
                <button className="toolbar-btn danger" onClick={() => handleDelete(conn)}>🗑 Delete</button>
              </div>
            </div>
          ))}
        </div>
      )}

      {showAddDialog && (
        <AddFeedDialog
          onClose={() => setShowAddDialog(false)}
          onComplete={handleAddComplete}
        />
      )}
    </div>
  );
}

function AddFeedDialog({ onClose, onComplete }) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [adapterType, setAdapterType] = useState('BINANCE');
  const [symbols, setSymbols] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const [simMode, setSimMode] = useState('CLEAN');
  const [failureRate, setFailureRate] = useState(10);
  const [ticksPerSecond, setTicksPerSecond] = useState(10);
  const [targetScenario, setTargetScenario] = useState('');
  const [scenarios, setScenarios] = useState([]);

  const isLvwr = adapterType === 'LVWR_T';

  useEffect(() => {
    if (!isLvwr) return;
    fetch('/api/simulator/scenarios')
      .then((res) => res.json())
      .then(setScenarios)
      .catch(() => {});
  }, [isLvwr]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);

    const symbolList = symbols
      .split(',')
      .map((s) => s.trim().toUpperCase())
      .filter(Boolean);

    const body = {
      name: name.trim(),
      url: isLvwr ? 'lvwr://simulator' : url.trim(),
      adapterType,
      symbols: isLvwr ? [] : symbolList,
    };

    try {
      const res = await fetch('/api/feeds', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        const resBody = await res.json().catch(() => ({}));
        throw new Error(resBody.error || `Add failed: ${res.status}`);
      }

      const created = await res.json();

      if (isLvwr) {
        const simConfig = {
          mode: simMode,
          failureRate: failureRate / 100,
          ticksPerSecond,
          targetScenario: simMode === 'SCENARIO' && targetScenario ? targetScenario : null,
        };
        await fetch(`/api/feeds/${created.id}/start`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(simConfig),
        });
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
          <input type="text" value={name} onChange={(e) => setName(e.target.value)}
            placeholder={isLvwr ? 'LVWR Simulator' : 'Binance BTC'} required />
        </label>

        {!isLvwr && (
          <label>
            URL
            <input type="text" value={url} onChange={(e) => setUrl(e.target.value)}
              placeholder="wss://stream.binance.com:9443/ws" required />
          </label>
        )}

        {isLvwr && (
          <div className="cm-sim-info">Synthetic LVWR feed — no external connection</div>
        )}

        <label>
          Type
          <select value={adapterType} onChange={(e) => setAdapterType(e.target.value)}>
            {ADAPTER_TYPES.map((t) => (
              <option key={t} value={t}>{t === 'LVWR_T' ? 'LVWR_T (Simulator)' : t}</option>
            ))}
          </select>
        </label>

        {!isLvwr && (
          <label>
            Symbols (comma-separated)
            <input type="text" value={symbols} onChange={(e) => setSymbols(e.target.value)}
              placeholder="BTCUSDT, ETHUSDT" required />
          </label>
        )}

        {isLvwr && (
          <div className="cm-sim-controls">
            <label>
              Mode
              <div className="cm-radio-group">
                {['CLEAN', 'NOISY', 'CHAOS', 'SCENARIO'].map((m) => (
                  <label key={m} className="cm-radio">
                    <input type="radio" name="simMode" value={m}
                      checked={simMode === m} onChange={() => setSimMode(m)} />
                    {m}
                  </label>
                ))}
              </div>
            </label>

            {simMode === 'NOISY' && (
              <label>
                Failure rate: {failureRate}%
                <input type="range" min="0" max="50" value={failureRate}
                  onChange={(e) => setFailureRate(Number(e.target.value))} />
              </label>
            )}

            {simMode === 'SCENARIO' && (
              <label>
                Target Scenario
                <select value={targetScenario} onChange={(e) => setTargetScenario(e.target.value)}>
                  <option value="">Select scenario…</option>
                  {scenarios.map((s) => (
                    <option key={s.name} value={s.name} title={s.description}>{s.name}</option>
                  ))}
                </select>
              </label>
            )}

            <label>
              Ticks per second: {ticksPerSecond}
              <input type="range" min="1" max="500" value={ticksPerSecond}
                onChange={(e) => setTicksPerSecond(Number(e.target.value))} />
            </label>
          </div>
        )}

        <div className="cm-dialog-actions">
          <button type="button" className="toolbar-btn" onClick={onClose}>Cancel</button>
          <button type="submit" className="toolbar-btn primary" disabled={submitting}>
            {submitting ? 'Connecting…' : isLvwr ? 'Start Simulator' : 'Connect'}
          </button>
        </div>
      </form>
    </div>
  );
}
