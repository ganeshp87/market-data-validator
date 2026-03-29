import React, { useState, useEffect, useCallback, useRef } from 'react';

const POLL_INTERVAL = 3000;
const SPEEDS = [1, 2, 5, 10];

function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
}

function formatDuration(startIso, endIso) {
  if (!startIso) return '—';
  const start = new Date(startIso);
  const end = endIso ? new Date(endIso) : new Date();
  const diffMs = end - start;
  const seconds = Math.floor(diffMs / 1000) % 60;
  const minutes = Math.floor(diffMs / 60000) % 60;
  const hours = Math.floor(diffMs / 3600000);
  if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

function formatDate(isoStr) {
  if (!isoStr) return '';
  return new Date(isoStr).toLocaleString();
}

export default function SessionManager() {
  const [sessions, setSessions] = useState([]);
  const [recording, setRecording] = useState(null); // active recording session
  const [error, setError] = useState(null);
  const [showStartDialog, setShowStartDialog] = useState(false);
  const [replay, setReplay] = useState(null); // { sessionId, state, ticksReplayed, totalTicks, speed, results }
  const [loading, setLoading] = useState(false);
  const [compare, setCompare] = useState(null); // { sessionIdA, sessionIdB, loading, result, error }
  const pollRef = useRef(null);

  // ── Fetch sessions ──────────────────────────────────
  const fetchSessions = useCallback(async () => {
    try {
      const res = await fetch('/api/sessions');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setSessions(data);

      // Track active recording
      const active = data.find((s) => s.status === 'RECORDING');
      setRecording(active || null);
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    fetchSessions();
    pollRef.current = setInterval(fetchSessions, POLL_INTERVAL);
    return () => clearInterval(pollRef.current);
  }, [fetchSessions]);

  // ── Start recording ─────────────────────────────────
  const startRecording = async (name, feedId) => {
    setError(null);
    setLoading(true);
    try {
      const res = await fetch('/api/sessions/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, feedId }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }
      setShowStartDialog(false);
      await fetchSessions();
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  // ── Stop recording ──────────────────────────────────
  const stopRecording = async () => {
    if (!recording) return;
    setError(null);
    try {
      const res = await fetch(`/api/sessions/${recording.id}/stop`, { method: 'POST' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      await fetchSessions();
    } catch (err) {
      setError(err.message);
    }
  };

  // ── Delete session ──────────────────────────────────
  const deleteSession = async (id) => {
    setError(null);
    try {
      const res = await fetch(`/api/sessions/${id}`, { method: 'DELETE' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setSessions((prev) => prev.filter((s) => s.id !== id));
    } catch (err) {
      setError(err.message);
    }
  };

  // ── Replay session ─────────────────────────────────
  const replaySession = async (id) => {
    setError(null);
    setReplay({ sessionId: id, state: 'REPLAYING', ticksReplayed: 0, totalTicks: 0, speed: 1, results: null });
    try {
      const res = await fetch(`/api/sessions/${id}/replay`, { method: 'POST' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setReplay({
        sessionId: id,
        state: 'COMPLETED',
        ticksReplayed: data.ticksReplayed,
        totalTicks: data.ticksReplayed,
        speed: 1,
        results: data.results,
      });
    } catch (err) {
      setError(err.message);
      setReplay(null);
    }
  };

  // ── Export session ──────────────────────────────────
  const exportSession = async (id, format) => {
    try {
      const res = await fetch(`/api/sessions/${id}/export?format=${format}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `session-${id}.${format}`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err.message);
    }
  };

  // ── Compare sessions ─────────────────────────────
  const compareSessions = async (idA, idB) => {
    setError(null);
    setCompare({ sessionIdA: idA, sessionIdB: idB, loading: true, result: null, error: null });
    try {
      const res = await fetch('/api/compare', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionIdA: idA, sessionIdB: idB }),
      });
      if (!res.ok) {
        const data = await res.json();
        throw new Error(data.error || `HTTP ${res.status}`);
      }
      const data = await res.json();
      setCompare({ sessionIdA: idA, sessionIdB: idB, loading: false, result: data, error: null });
    } catch (err) {
      setCompare((prev) => ({ ...prev, loading: false, error: err.message }));
    }
  };

  const completedSessions = sessions.filter((s) => s.status !== 'RECORDING');

  return (
    <div className="sm-container">
      <h2>💾 Sessions</h2>

      {error && <div className="sm-error">{error}</div>}

      {/* ── Recording control ──────────────────────── */}
      <section className="sm-section">
        <h3>Recording</h3>
        {recording ? (
          <div className="sm-recording-active">
            <div className="sm-recording-dot" />
            <div className="sm-recording-info">
              <strong>{recording.name}</strong>
              <span className="sm-meta">
                {recording.tickCount.toLocaleString()} ticks • {formatDuration(recording.startedAt, null)} • {formatBytes(recording.byteSize)}
              </span>
            </div>
            <button className="toolbar-btn danger" onClick={stopRecording}>
              ⏹ Stop
            </button>
          </div>
        ) : (
          <button
            className="toolbar-btn primary"
            onClick={() => setShowStartDialog(true)}
          >
            🔴 Start Recording
          </button>
        )}
      </section>

      {/* ── Start dialog ───────────────────────────── */}
      {showStartDialog && (
        <StartRecordingDialog
          onStart={startRecording}
          onCancel={() => setShowStartDialog(false)}
          loading={loading}
        />
      )}

      {/* ── Replay panel ───────────────────────────── */}
      {replay && (
        <ReplayPanel replay={replay} onClose={() => setReplay(null)} />
      )}

      {/* ── Compare panel ──────────────────────────── */}
      {completedSessions.length >= 2 && (
        <ComparePanel
          sessions={completedSessions}
          compare={compare}
          onCompare={compareSessions}
          onClose={() => setCompare(null)}
        />
      )}

      {/* ── Saved sessions list ────────────────────── */}
      <section className="sm-section">
        <h3>Saved Sessions ({completedSessions.length})</h3>
        {completedSessions.length === 0 ? (
          <p className="sm-empty">No recorded sessions yet.</p>
        ) : (
          <div className="sm-session-list">
            {completedSessions.map((session) => (
              <SessionCard
                key={session.id}
                session={session}
                onReplay={replaySession}
                onExport={exportSession}
                onDelete={deleteSession}
                replayActive={replay !== null}
              />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

// ── Start Recording Dialog ─────────────────────────────
function StartRecordingDialog({ onStart, onCancel, loading }) {
  const [name, setName] = useState('');
  const [feedId, setFeedId] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    if (name.trim() && feedId.trim()) {
      onStart(name.trim(), feedId.trim());
    }
  };

  return (
    <div className="cm-dialog-overlay" onClick={onCancel}>
      <div className="cm-dialog" onClick={(e) => e.stopPropagation()}>
        <h3>Start Recording</h3>
        <form onSubmit={handleSubmit}>
          <label>
            Session Name
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="btc-morning-session"
              autoFocus
            />
          </label>
          <label>
            Feed ID
            <input
              type="text"
              value={feedId}
              onChange={(e) => setFeedId(e.target.value)}
              placeholder="feed-1"
            />
          </label>
          <div className="cm-dialog-actions">
            <button type="button" className="toolbar-btn" onClick={onCancel}>
              Cancel
            </button>
            <button
              type="submit"
              className="toolbar-btn primary"
              disabled={!name.trim() || !feedId.trim() || loading}
            >
              {loading ? 'Starting…' : '🔴 Start'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Session Card ───────────────────────────────────────
function SessionCard({ session, onReplay, onExport, onDelete, replayActive }) {
  const [confirmDelete, setConfirmDelete] = useState(false);

  const statusClass =
    session.status === 'COMPLETED' ? 'completed' :
    session.status === 'FAILED' ? 'failed' : '';

  return (
    <div className={`sm-card ${statusClass}`}>
      <div className="sm-card-header">
        <span className="sm-card-icon">📁</span>
        <div className="sm-card-title">
          <strong>{session.name}</strong>
          <span className="sm-meta">Feed: {session.feedId}</span>
        </div>
        <span className={`sm-status-badge ${session.status.toLowerCase()}`}>
          {session.status}
        </span>
      </div>

      <div className="sm-card-body">
        <div className="sm-stat">
          <span className="sm-stat-label">Ticks</span>
          <span className="sm-stat-value">{session.tickCount.toLocaleString()}</span>
        </div>
        <div className="sm-stat">
          <span className="sm-stat-label">Duration</span>
          <span className="sm-stat-value">{formatDuration(session.startedAt, session.endedAt)}</span>
        </div>
        <div className="sm-stat">
          <span className="sm-stat-label">Size</span>
          <span className="sm-stat-value">{formatBytes(session.byteSize)}</span>
        </div>
        <div className="sm-stat">
          <span className="sm-stat-label">Date</span>
          <span className="sm-stat-value">{formatDate(session.startedAt)}</span>
        </div>
      </div>

      <div className="sm-card-actions">
        <button
          className="toolbar-btn primary"
          onClick={() => onReplay(session.id)}
          disabled={session.status !== 'COMPLETED' || replayActive}
          title="Replay through validators"
        >
          ▶ Replay
        </button>
        <button
          className="toolbar-btn"
          onClick={() => onExport(session.id, 'json')}
          title="Export as JSON"
        >
          📥 JSON
        </button>
        <button
          className="toolbar-btn"
          onClick={() => onExport(session.id, 'csv')}
          title="Export as CSV"
        >
          📥 CSV
        </button>
        {confirmDelete ? (
          <>
            <button className="toolbar-btn danger" onClick={() => { onDelete(session.id); setConfirmDelete(false); }}>
              Confirm
            </button>
            <button className="toolbar-btn" onClick={() => setConfirmDelete(false)}>
              Cancel
            </button>
          </>
        ) : (
          <button className="toolbar-btn danger" onClick={() => setConfirmDelete(true)} title="Delete session">
            🗑
          </button>
        )}
      </div>
    </div>
  );
}

// ── Replay Panel ───────────────────────────────────────
function ReplayPanel({ replay, onClose }) {
  const progress = replay.totalTicks > 0
    ? Math.round((replay.ticksReplayed / replay.totalTicks) * 100)
    : 0;

  return (
    <section className="sm-replay-panel">
      <div className="sm-replay-header">
        <h3>Replay — Session #{replay.sessionId}</h3>
        <button className="toolbar-btn" onClick={onClose}>✕</button>
      </div>

      <div className="sm-replay-progress">
        <div className="sm-progress-bar">
          <div
            className="sm-progress-fill"
            style={{ width: `${progress}%` }}
          />
        </div>
        <span className="sm-meta">
          {replay.ticksReplayed.toLocaleString()} / {replay.totalTicks.toLocaleString()} ticks
          ({progress}%)
        </span>
      </div>

      <div className="sm-replay-status">
        <span className={`sm-status-badge ${replay.state.toLowerCase()}`}>
          {replay.state}
        </span>
      </div>

      {replay.results && (
        <div className="sm-replay-results">
          <h4>Validation Results</h4>
          <div className="sm-results-grid">
            {replay.results.map((r) => (
              <div key={r.area} className={`sm-result-card ${r.status.toLowerCase()}`}>
                <span className="sm-result-area">{r.area}</span>
                <span className={`sm-status-badge ${r.status.toLowerCase()}`}>{r.status}</span>
                <span className="sm-meta">{r.message}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  );
}

// ── Compare Panel ──────────────────────────────────────
function ComparePanel({ sessions, compare, onCompare, onClose }) {
  const [idA, setIdA] = useState('');
  const [idB, setIdB] = useState('');

  const handleCompare = () => {
    if (idA && idB && idA !== idB) {
      onCompare(Number(idA), Number(idB));
    }
  };

  return (
    <section className="sm-section">
      <h3>🔍 Compare Sessions</h3>
      <div className="cmp-controls">
        <label>
          Session A
          <select value={idA} onChange={(e) => setIdA(e.target.value)}>
            <option value="">Select…</option>
            {sessions.map((s) => (
              <option key={s.id} value={s.id}>{s.name} (#{s.id})</option>
            ))}
          </select>
        </label>
        <span className="cmp-vs">vs</span>
        <label>
          Session B
          <select value={idB} onChange={(e) => setIdB(e.target.value)}>
            <option value="">Select…</option>
            {sessions.map((s) => (
              <option key={s.id} value={s.id}>{s.name} (#{s.id})</option>
            ))}
          </select>
        </label>
        <button
          className="toolbar-btn primary"
          onClick={handleCompare}
          disabled={!idA || !idB || idA === idB || (compare && compare.loading)}
        >
          {compare && compare.loading ? 'Comparing…' : '🔍 Compare'}
        </button>
        {compare && (
          <button className="toolbar-btn" onClick={onClose}>✕</button>
        )}
      </div>

      {compare && compare.error && (
        <div className="sm-error">{compare.error}</div>
      )}

      {compare && compare.result && (
        <CompareResults result={compare.result} />
      )}
    </section>
  );
}

// ── Compare Results ────────────────────────────────────
function CompareResults({ result }) {
  const { sessionA, sessionB, priceDifferences, volumeDifferences,
          sequenceGaps, latencyPatterns, missingSymbols } = result;

  return (
    <div className="cmp-results">
      {/* Summary */}
      <div className="cmp-summary">
        <div className="cmp-session-label">
          <strong>A:</strong> {sessionA.name} ({sessionA.tickCount} ticks)
        </div>
        <div className="cmp-session-label">
          <strong>B:</strong> {sessionB.name} ({sessionB.tickCount} ticks)
        </div>
      </div>

      {/* Price Differences */}
      {priceDifferences.length > 0 && (
        <div className="cmp-section">
          <h4>💰 Price Differences</h4>
          <table className="cmp-table">
            <thead>
              <tr><th>Symbol</th><th>Avg A</th><th>Avg B</th><th>Diff %</th></tr>
            </thead>
            <tbody>
              {priceDifferences.map((d) => (
                <tr key={d.symbol}>
                  <td>{d.symbol}</td>
                  <td>{Number(d.avgPriceA).toFixed(2)}</td>
                  <td>{Number(d.avgPriceB).toFixed(2)}</td>
                  <td className={Math.abs(d.diffPercent) > 1 ? 'cmp-warn' : ''}>
                    {d.diffPercent > 0 ? '+' : ''}{d.diffPercent}%
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Volume Differences */}
      {volumeDifferences.length > 0 && (
        <div className="cmp-section">
          <h4>📊 Volume Differences</h4>
          <table className="cmp-table">
            <thead>
              <tr><th>Symbol</th><th>Avg A</th><th>Avg B</th><th>Diff %</th></tr>
            </thead>
            <tbody>
              {volumeDifferences.map((d) => (
                <tr key={d.symbol}>
                  <td>{d.symbol}</td>
                  <td>{Number(d.avgVolumeA).toFixed(4)}</td>
                  <td>{Number(d.avgVolumeB).toFixed(4)}</td>
                  <td className={Math.abs(d.diffPercent) > 10 ? 'cmp-warn' : ''}>
                    {d.diffPercent > 0 ? '+' : ''}{d.diffPercent}%
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Sequence Gaps */}
      <div className="cmp-section">
        <h4>🔢 Sequence Gaps</h4>
        <div className="cmp-stats-row">
          <span>Gaps in A: <strong>{sequenceGaps.totalGapsA}</strong></span>
          <span>Gaps in B: <strong>{sequenceGaps.totalGapsB}</strong></span>
          <span className={sequenceGaps.newGapsInB > 0 ? 'cmp-warn' : ''}>
            New gaps in B: <strong>{sequenceGaps.newGapsInB}</strong>
          </span>
        </div>
      </div>

      {/* Latency */}
      <div className="cmp-section">
        <h4>⏱ Latency (ms)</h4>
        <table className="cmp-table">
          <thead>
            <tr><th></th><th>p50</th><th>p95</th><th>Max</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>Session A</td>
              <td>{latencyPatterns.sessionA.p50}</td>
              <td>{latencyPatterns.sessionA.p95}</td>
              <td>{latencyPatterns.sessionA.max}</td>
            </tr>
            <tr>
              <td>Session B</td>
              <td>{latencyPatterns.sessionB.p50}</td>
              <td className={latencyPatterns.sessionB.p95 > latencyPatterns.sessionA.p95 * 1.2 ? 'cmp-warn' : ''}>
                {latencyPatterns.sessionB.p95}
              </td>
              <td>{latencyPatterns.sessionB.max}</td>
            </tr>
          </tbody>
        </table>
      </div>

      {/* Missing Symbols */}
      {(missingSymbols.onlyInA.length > 0 || missingSymbols.onlyInB.length > 0) && (
        <div className="cmp-section">
          <h4>⚠️ Missing Symbols</h4>
          {missingSymbols.onlyInA.length > 0 && (
            <p className="cmp-warn">Only in A: {missingSymbols.onlyInA.join(', ')}</p>
          )}
          {missingSymbols.onlyInB.length > 0 && (
            <p>Only in B: {missingSymbols.onlyInB.join(', ')}</p>
          )}
        </div>
      )}
    </div>
  );
}
