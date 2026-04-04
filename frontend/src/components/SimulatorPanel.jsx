import React, { useState, useEffect, useRef } from 'react';

const MODES = ['CLEAN', 'NOISY', 'CHAOS', 'SCENARIO'];

const FAILURE_CHIPS = [
  'PRICE_SPIKE', 'SEQUENCE_GAP', 'DUPLICATE_TICK', 'STALE_TIMESTAMP',
  'NEGATIVE_PRICE', 'MALFORMED_PAYLOAD', 'DISCONNECT', 'CUMVOL_BACKWARD',
  'THROTTLE_BURST', 'RECONNECT_STORM', 'OUT_OF_ORDER', 'SYMBOL_MISMATCH',
];

const CHIP_SHORT = {
  PRICE_SPIKE: 'PRICE_SPIKE', SEQUENCE_GAP: 'SEQ_GAP',
  DUPLICATE_TICK: 'DUPLICATE', STALE_TIMESTAMP: 'STALE_TS',
  NEGATIVE_PRICE: 'NEG_PRICE', MALFORMED_PAYLOAD: 'MALFORMED',
  DISCONNECT: 'DISCONNECT', CUMVOL_BACKWARD: 'CUMVOL_BWD',
  THROTTLE_BURST: 'THROTTLE', RECONNECT_STORM: 'RECONNECT',
  OUT_OF_ORDER: 'OUT_ORDER', SYMBOL_MISMATCH: 'SYM_MISMTCH',
};

export default function SimulatorPanel({ connectionId }) {
  const [mode, setMode] = useState('CLEAN');
  const [failureRate, setFailureRate] = useState(18);
  const [ticksPerSec, setTicksPerSec] = useState(50);
  const [scenarios, setScenarios] = useState([]);
  const [selectedScenario, setSelectedScenario] = useState('');
  const [status, setStatus] = useState(null);
  const [applyFeedback, setApplyFeedback] = useState(false);
  const [dirty, setDirty] = useState(false);
  const pollRef = useRef(null);
  const syncedRef = useRef(false);

  // Fetch available scenarios on mount
  useEffect(() => {
    fetch('/api/simulator/scenarios')
      .then(r => r.ok ? r.json() : [])
      .then(setScenarios)
      .catch(() => {});
  }, []);

  // Poll simulator status when connectionId is available
  useEffect(() => {
    if (!connectionId) {
      setStatus(null);
      syncedRef.current = false;
      return;
    }
    const poll = async () => {
      try {
        const res = await fetch(`/api/simulator/status?connectionId=${encodeURIComponent(connectionId)}`);
        if (res.ok) {
          const data = await res.json();
          setStatus(data);
          // Sync local controls from first successful poll so UI matches the running config
          if (!syncedRef.current && data.running) {
            syncedRef.current = true;
            if (data.mode) setMode(data.mode);
            if (data.targetScenario) setSelectedScenario(data.targetScenario);
            if (data.failureRate != null) setFailureRate(Math.round(data.failureRate * 100));
            if (data.ticksPerSecond != null) setTicksPerSec(data.ticksPerSecond);
            setDirty(false);
          }
        } else {
          setStatus(null);
        }
      } catch { setStatus(null); }
    };
    poll();
    pollRef.current = setInterval(poll, 2000);
    return () => clearInterval(pollRef.current);
  }, [connectionId]);

  const handleApply = async () => {
    if (!connectionId) return;
    try {
      const res = await fetch(`/api/simulator/config?connectionId=${encodeURIComponent(connectionId)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mode,
          targetScenario: (mode === 'SCENARIO' && selectedScenario) ? selectedScenario : null,
          failureRate: failureRate / 100,
          ticksPerSecond: ticksPerSec,
        }),
      });
      if (!res.ok) return;
      setDirty(false);
      setApplyFeedback(true);
      setTimeout(() => setApplyFeedback(false), 1500);
      // Immediately re-poll status to refresh Live Status display
      try {
        const sr = await fetch(`/api/simulator/status?connectionId=${encodeURIComponent(connectionId)}`);
        if (sr.ok) setStatus(await sr.json());
      } catch { /* ignore */ }
    } catch { /* ignore */ }
  };

  const handleStart = async () => {
    if (!connectionId) return;
    try {
      await fetch(`/api/feeds/${connectionId}/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mode,
          targetScenario: (mode === 'SCENARIO' && selectedScenario) ? selectedScenario : null,
          failureRate: failureRate / 100,
          ticksPerSecond: ticksPerSec,
        }),
      });
      setDirty(false);
    } catch { /* ignore */ }
  };

  const handleStop = async () => {
    if (!connectionId) return;
    try {
      await fetch(`/api/feeds/${connectionId}/stop`, { method: 'POST' });
    } catch { /* ignore */ }
  };

  const handleResetValidators = async () => {
    try {
      await fetch('/api/validation/reset', { method: 'POST' });
    } catch { /* ignore */ }
  };

  const modeClass = (m) => {
    if (m !== mode) return 'mode-btn';
    if (m === 'CLEAN') return 'mode-btn selected-clean';
    return 'mode-btn selected';
  };

  const isRunning = !!status?.running;
  const failures = status?.failuresInjected || {};

  // Pending: user changed controls since last Apply / Start
  const isPending = isRunning && dirty;

  return (
    <>
      <div className="panel-section">
        <div className="panel-label">
          Simulator control <span className="new-badge">NEW</span>
          <button className="btn" style={{ marginLeft: '8px', padding: '2px 8px' }} onClick={handleResetValidators}>
            Reset Validators
          </button>
        </div>

        {!connectionId && (
          <div className="sim-hint">Select or create an LVWR_T connection to enable controls.</div>
        )}

        <div className="sim-mode-row">
          {MODES.map((m) => (
            <div key={m} className={modeClass(m)} onClick={() => { setMode(m); if (isRunning) setDirty(true); }}>
              {m}{m === mode && m === 'CHAOS' ? ' ●' : ''}
            </div>
          ))}
        </div>

        {mode === 'SCENARIO' && scenarios.length > 0 && (
          <>
            <select
              className="sim-scenario-select"
              value={selectedScenario}
              onChange={e => { setSelectedScenario(e.target.value); if (isRunning) setDirty(true); }}
            >
              <option value="">— select scenario —</option>
              {scenarios.map(s => (
                <option key={s.name} value={s.name}>{s.name}: {s.description}</option>
              ))}
            </select>
            {selectedScenario && (
              <div className="sim-scenario-hint">
                ① Adjust <b>Failure rate</b> (how often it fires) &amp; <b>Ticks/sec</b> 
                ② Click <b>{isRunning ? 'Apply Config' : '▶ Start'}</b> 
                ③ Watch the <b>{selectedScenario}</b> chip light up below
              </div>
            )}
          </>
        )}

        <div className="slider-row">
          <span className="slider-label">Failure rate</span>
          <input
            type="range" min="0" max="50" value={failureRate}
            style={{ flex: 1 }}
            onChange={e => { setFailureRate(Number(e.target.value)); if (isRunning) setDirty(true); }}
          />
          <span className="slider-val">{failureRate}%</span>
        </div>
        <div className="slider-row">
          <span className="slider-label">Ticks / sec</span>
          <input
            type="range" min="1" max="500" value={ticksPerSec}
            style={{ flex: 1 }}
            onChange={e => { setTicksPerSec(Number(e.target.value)); if (isRunning) setDirty(true); }}
          />
          <span className="slider-val">{ticksPerSec}</span>
        </div>

        <div style={{ display: 'flex', gap: '6px', marginTop: '6px' }}>
          <button
            className={`btn ${applyFeedback ? 'btn-apply-ok' : isPending ? 'btn-pending' : 'btn-accent'}`}
            style={{ flex: 1 }}
            disabled={!connectionId || (mode === 'SCENARIO' && !selectedScenario)}
            onClick={handleApply}
          >
            {applyFeedback ? '✓ Applied!' : 'Apply Config'}
          </button>
          {isRunning ? (
            <button className="btn btn-red" style={{ flex: 1 }} onClick={handleStop}>■ Stop</button>
          ) : (
            <button className="btn btn-green" style={{ flex: 1 }} disabled={!connectionId} onClick={handleStart}>▶ Start</button>
          )}
        </div>
        {isPending && (
          <div className="sim-pending-hint">⚠ Not applied yet — click Apply Config to update the running simulator</div>
        )}
      </div>

      {connectionId && status && (
        <div className="panel-section">
          <div className="panel-label">Live Status <span className="new-badge">NEW</span></div>
          <div className="sim-stat-row">
            <span>Mode</span><span>{status.mode}</span>
          </div>
          <div className="sim-stat-row">
            <span>Ticks sent</span><span>{status.ticksSent?.toLocaleString()}</span>
          </div>
          <div className="sim-stat-row">
            <span>Failures injected</span>
            <span>{Object.values(status.failuresInjected || {}).reduce((s, v) => s + Number(v), 0).toLocaleString()}</span>
          </div>
          <div className="sim-stat-row">
            <span>Ticks / sec</span><span>{status.ticksPerSecond}</span>
          </div>
        </div>
      )}

      <div className="panel-section">
        <div className="panel-label">Failures injected <span className="new-badge">NEW</span></div>
        <div className="failure-grid">
          {FAILURE_CHIPS.map((f) => {
            const count = (typeof failures === 'object' && failures !== null) ? (failures[f] || 0) : 0;
            return (
              <div key={f} className={`failure-chip ${count > 0 ? 'firing' : ''}`}>
                <span>{CHIP_SHORT[f]}</span>
                <span className="chip-count">{count}</span>
              </div>
            );
          })}
        </div>
      </div>
    </>
  );
}
