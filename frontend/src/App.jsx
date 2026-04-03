import React, { useState, useEffect, useCallback, useRef } from 'react';
import LiveTickFeed from './components/LiveTickFeed';
import ConnectionManager from './components/ConnectionManager';
import ValidationDashboard from './components/ValidationDashboard';
import StatusBar from './components/StatusBar';
import LatencyChart from './components/LatencyChart';
import ThroughputGauge from './components/ThroughputGauge';
import SessionManager from './components/SessionManager';
import AlertPanel from './components/AlertPanel';
import SimulatorPanel from './components/SimulatorPanel';
import CompliancePanel from './components/CompliancePanel';

const TABS = [
  { id: 'feed', label: '📡 Live Feed' },
  { id: 'connections', label: '🔌 Connections' },
  { id: 'validation', label: '📊 Validation' },
  { id: 'latency', label: '📈 Latency' },
  { id: 'sessions', label: '💾 Sessions' },
  { id: 'alerts', label: '🔔 Alerts' },
  { id: 'simulator', label: '🧪 Simulator' },
  { id: 'compliance', label: '⚖️ Compliance' },
];

export default function App() {
  const [activeTab, setActiveTab] = useState('feed');
  const [alertCount, setAlertCount] = useState(0);
  const pollRef = useRef(null);

  const fetchAlertCount = useCallback(async () => {
    try {
      const res = await fetch('/api/alerts/count');
      if (res.ok) setAlertCount(await res.json());
    } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    fetchAlertCount();
    pollRef.current = setInterval(fetchAlertCount, 3000);
    return () => clearInterval(pollRef.current);
  }, [fetchAlertCount]);

  return (
    <div className="app">
      <header className="app-header">
        <h1>Market Data Stream Validator</h1>
      </header>

      <div className="app-body">
        <nav className="sidebar">
          {TABS.map((tab) => (
            <button
              key={tab.id}
              className={`sidebar-btn ${activeTab === tab.id ? 'active' : ''}`}
              onClick={() => setActiveTab(tab.id)}
            >
              {tab.label}
              {tab.id === 'alerts' && alertCount > 0 && (
                <span className="alert-badge">{alertCount}</span>
              )}
            </button>
          ))}
        </nav>

        <main className="content">
          {activeTab === 'feed' && <LiveTickFeed />}
          {activeTab === 'connections' && <ConnectionManager />}
          {activeTab === 'validation' && (
            <>
              <ValidationDashboard />
              <ThroughputGauge />
            </>
          )}
          {activeTab === 'latency' && <LatencyChart />}
          {activeTab === 'sessions' && <SessionManager />}
          {activeTab === 'alerts' && <AlertPanel />}
          {activeTab === 'simulator' && <SimulatorPanel connectionId={null} />}
          {activeTab === 'compliance' && <CompliancePanel />}
        </main>
      </div>

      <StatusBar />
    </div>
  );
}


