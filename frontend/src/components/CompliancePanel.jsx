import React, { useState, useEffect, useRef } from 'react';

const RULES = [
  { key: 'art27TimestampAccuracy',       label: 'ART 27 — Timestamp Accuracy' },
  { key: 'art64PreTradeTransparency',    label: 'ART 64 — Pre-Trade Transparency' },
  { key: 'art65PostTradeReporting',      label: 'ART 65 — Post-Trade Reporting' },
  { key: 'rts22TradeIdUniqueness',       label: 'RTS 22 — Trade ID Uniqueness' },
  { key: 'auditTrailCompleteness',       label: 'Audit Trail Completeness' },
  { key: 'ohlcReconciliation',           label: 'OHLC Reconciliation' },
];

export default function CompliancePanel() {
  const [data, setData] = useState(null);
  const [updatedAt, setUpdatedAt] = useState(null);
  const [error, setError] = useState(null);
  const pollRef = useRef(null);

  useEffect(() => {
    const fetchCompliance = async () => {
      try {
        const res = await fetch('/api/compliance');
        if (res.ok) {
          setData(await res.json());
          setUpdatedAt(new Date().toLocaleTimeString());
          setError(null);
        }
      } catch (e) {
        setError('Error fetching compliance data');
      }
    };
    fetchCompliance();
    pollRef.current = setInterval(fetchCompliance, 10000);
    return () => clearInterval(pollRef.current);
  }, []);

  return (
    <div className="panel-section">
      <div className="panel-label">MiFID II Compliance <span className="new-badge">NEW</span></div>
      {error && <div className="compliance-error">{error}</div>}
      {updatedAt && <div className="compliance-updated">Updated: {updatedAt}</div>}
      {RULES.map((rule) => {
        const result = data ? data[rule.key] : undefined;
        const pass = result === 'PASS';
        const warn = result === 'WARN';
        const loaded = result !== undefined;
        return (
          <div key={rule.key} className="compliance-row">
            <span>{rule.label}</span>
            <span className={pass ? 'compliance-pass' : warn ? 'compliance-warn' : loaded ? 'compliance-fail' : ''}>
              {loaded ? (pass ? 'PASS' : warn ? 'WARN' : 'FAIL') : '—'}
            </span>
          </div>
        );
      })}
    </div>
  );
}
