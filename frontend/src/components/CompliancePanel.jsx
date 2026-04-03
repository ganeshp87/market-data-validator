import { useState, useEffect } from 'react'

const FIELDS = [
  { key: 'art27TimestampAccuracy',    label: 'ART 27 — Timestamp Accuracy',    article: 'MiFID II Art. 27' },
  { key: 'art64PreTradeTransparency', label: 'ART 64 — Pre-Trade Transparency', article: 'MiFID II Art. 64' },
  { key: 'art65PostTradeReporting',   label: 'ART 65 — Post-Trade Reporting',   article: 'MiFID II Art. 65' },
  { key: 'rts22TradeIdUniqueness',    label: 'RTS 22 — Trade ID Uniqueness',    article: 'MiFID II RTS 22' },
  { key: 'auditTrailCompleteness',    label: 'Audit Trail Completeness',         article: 'ESMA MAR' },
  { key: 'ohlcReconciliation',        label: 'OHLC Reconciliation',              article: 'Internal' },
]

function statusClass(value) {
  if (value === 'PASS') return 'compliance-pass'
  if (value === 'WARN') return 'compliance-warn'
  if (value === 'FAIL') return 'compliance-fail'
  return 'compliance-unknown'
}

function statusIcon(value) {
  if (value === 'PASS') return '✅'
  if (value === 'WARN') return '⚠️'
  if (value === 'FAIL') return '❌'
  return '❓'
}

export default function CompliancePanel() {
  const [report, setReport] = useState(null)
  const [lastUpdated, setLastUpdated] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    const fetchReport = () => {
      fetch('/api/compliance')
        .then(r => r.ok ? r.json() : Promise.reject(r.status))
        .then(data => {
          setReport(data)
          setLastUpdated(new Date())
          setError(null)
        })
        .catch(e => setError(String(e)))
    }
    fetchReport()
    const id = setInterval(fetchReport, 5000)
    return () => clearInterval(id)
  }, [])

  return (
    <div className="compliance-panel">
      <div className="compliance-header">
        <h2>⚖️ Compliance — MiFID-II</h2>
        {lastUpdated && (
          <span className="compliance-updated">
            Updated: {lastUpdated.toLocaleTimeString()}
          </span>
        )}
      </div>

      {error && <p className="compliance-error">Error fetching compliance data: {error}</p>}

      <div className="compliance-grid">
        {FIELDS.map(({ key, label, article }) => {
          const value = report ? report[key] ?? 'UNKNOWN' : '—'
          return (
            <div key={key} className={`compliance-card ${report ? statusClass(value) : ''}`}>
              <div className="compliance-icon">{report ? statusIcon(value) : '…'}</div>
              <div className="compliance-label">{label}</div>
              <div className="compliance-article">{article}</div>
              <div className="compliance-value">{value}</div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
