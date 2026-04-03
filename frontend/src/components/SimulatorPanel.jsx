import { useState, useEffect, useCallback } from 'react'

const MODES = ['CLEAN', 'NOISY', 'CHAOS', 'SCENARIO']

const defaultConfig = {
  mode: 'CLEAN',
  targetScenario: null,
  failureRate: 0.10,
  numTrades: 10000,
  ticksPerSecond: 50,
  includeHeartbeats: true,
}

export default function SimulatorPanel({ connectionId }) {
  const [scenarios, setScenarios] = useState([])
  const [config, setConfig] = useState(defaultConfig)
  const [status, setStatus] = useState(null)
  const [updating, setUpdating] = useState(false)
  const [error, setError] = useState(null)

  // Load available failure scenarios on mount
  useEffect(() => {
    fetch('/api/simulator/scenarios')
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then(setScenarios)
      .catch(() => setScenarios([]))
  }, [])

  // Poll simulator status every 2 seconds when a connectionId is provided
  useEffect(() => {
    if (!connectionId) return
    const id = setInterval(() => {
      fetch(`/api/simulator/status?connectionId=${connectionId}`)
        .then(r => r.ok ? r.json() : null)
        .then(setStatus)
        .catch(() => {})
    }, 2000)
    return () => clearInterval(id)
  }, [connectionId])

  const applyConfig = useCallback(async () => {
    if (!connectionId) return
    setUpdating(true)
    setError(null)
    try {
      const res = await fetch(`/api/simulator/config?connectionId=${connectionId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
    } catch (e) {
      setError(e.message)
    } finally {
      setUpdating(false)
    }
  }, [connectionId, config])

  const resetValidators = useCallback(async () => {
    try {
      await fetch('/api/validation/reset', { method: 'POST' })
    } catch (e) {
      setError(e.message)
    }
  }, [])

  return (
    <div className="simulator-panel">
      <h2>🧪 Simulator — LVWR_T</h2>

      {/* Mode selector */}
      <div className="sim-row">
        <label>Mode</label>
        <div className="mode-buttons">
          {MODES.map(m => (
            <button
              key={m}
              className={`mode-btn ${config.mode === m ? 'active' : ''}`}
              onClick={() => setConfig(c => ({ ...c, mode: m, targetScenario: null }))}
            >
              {m}
            </button>
          ))}
        </div>
      </div>

      {/* Scenario selector — only in SCENARIO mode */}
      {config.mode === 'SCENARIO' && (
        <div className="sim-row">
          <label>Scenario</label>
          <select
            value={config.targetScenario ?? ''}
            onChange={e => setConfig(c => ({ ...c, targetScenario: e.target.value || null }))}
          >
            <option value="">— choose —</option>
            {scenarios.map(s => (
              <option key={s.name} value={s.name} title={s.description}>
                {s.name}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Numeric controls */}
      <div className="sim-row">
        <label>Failure Rate</label>
        <input
          type="number"
          min="0" max="1" step="0.01"
          value={config.failureRate}
          onChange={e => setConfig(c => ({ ...c, failureRate: parseFloat(e.target.value) }))}
        />
      </div>
      <div className="sim-row">
        <label>Ticks / Second</label>
        <input
          type="number"
          min="1" max="1000"
          value={config.ticksPerSecond}
          onChange={e => setConfig(c => ({ ...c, ticksPerSecond: parseInt(e.target.value, 10) }))}
        />
      </div>
      <div className="sim-row">
        <label>Num Trades</label>
        <input
          type="number"
          min="0"
          value={config.numTrades}
          onChange={e => setConfig(c => ({ ...c, numTrades: parseInt(e.target.value, 10) }))}
        />
        <span className="hint">(0 = unlimited)</span>
      </div>
      <div className="sim-row">
        <label>Include Heartbeats</label>
        <input
          type="checkbox"
          checked={config.includeHeartbeats}
          onChange={e => setConfig(c => ({ ...c, includeHeartbeats: e.target.checked }))}
        />
      </div>

      {/* Actions */}
      <div className="sim-actions">
        <button
          onClick={applyConfig}
          disabled={!connectionId || updating}
          className="btn-primary"
        >
          {updating ? 'Applying…' : 'Apply Config'}
        </button>
        <button onClick={resetValidators} className="btn-secondary">
          Reset Validators
        </button>
      </div>

      {error && <p className="sim-error">Error: {error}</p>}

      {/* Live status */}
      {status && (
        <div className="sim-status">
          <h3>Live Status</h3>
          <dl>
            <dt>Running</dt><dd>{status.running ? '✅ Yes' : '❌ No'}</dd>
            <dt>Mode</dt><dd>{status.mode}</dd>
            <dt>Ticks Sent</dt><dd>{status.ticksSent?.toLocaleString()}</dd>
            <dt>Failures Injected</dt><dd>{status.failuresInjected?.toLocaleString()}</dd>
            <dt>Ticks / Sec</dt><dd>{status.ticksPerSecond}</dd>
            <dt>Failure Rate</dt><dd>{(status.failureRate * 100).toFixed(1)}%</dd>
          </dl>
        </div>
      )}

      {!connectionId && (
        <p className="sim-hint">Select or create an LVWR_T connection to enable the simulator.</p>
      )}
    </div>
  )
}
