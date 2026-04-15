import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import CompliancePanel from '../components/CompliancePanel';

const mockFetch = vi.fn();

beforeEach(() => {
  vi.stubGlobal('fetch', mockFetch);
  mockFetch.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

const PASS_REPORT = {
  timestamp: '2026-01-01T00:00:00Z',
  art27TimestampAccuracy: 'PASS',
  art64PreTradeTransparency: 'PASS',
  art65PostTradeReporting: 'PASS',
  rts22TradeIdUniqueness: 'PASS',
  auditTrailCompleteness: 'PASS',
  ohlcReconciliation: 'PASS',
};

const MIXED_REPORT = {
  ...PASS_REPORT,
  art65PostTradeReporting: 'FAIL',
  auditTrailCompleteness: 'WARN',
};

describe('CompliancePanel', () => {
  it('renders the panel heading', () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => PASS_REPORT });
    render(<CompliancePanel />);
    expect(screen.getByText(/Compliance/)).toBeInTheDocument();
  });

  it('shows all 6 MiFID field labels', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => PASS_REPORT });
    render(<CompliancePanel />);
    // Use exact label strings so getByText matches only the label div (not its ancestors)
    await waitFor(() => {
      expect(screen.getByText('ART 27 — Timestamp Accuracy')).toBeInTheDocument();
      expect(screen.getByText('ART 64 — Pre-Trade Transparency')).toBeInTheDocument();
      expect(screen.getByText('ART 65 — Post-Trade Reporting')).toBeInTheDocument();
      expect(screen.getByText('RTS 22 — Trade ID Uniqueness')).toBeInTheDocument();
      expect(screen.getByText('Audit Trail Completeness')).toBeInTheDocument();
      expect(screen.getByText('OHLC Reconciliation')).toBeInTheDocument();
    });
  });

  it('shows PASS status values', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => PASS_REPORT });
    render(<CompliancePanel />);
    await waitFor(() => {
      const passes = screen.getAllByText('PASS');
      expect(passes.length).toBe(6);
    });
  });

  it('shows FAIL and WARN alongside PASS', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => MIXED_REPORT });
    render(<CompliancePanel />);
    await waitFor(() => {
      expect(screen.getByText('FAIL')).toBeInTheDocument();
      expect(screen.getByText('WARN')).toBeInTheDocument();
    });
  });

  it('shows last-updated timestamp after fetch', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => PASS_REPORT });
    render(<CompliancePanel />);
    await waitFor(() =>
      expect(screen.getByText(/Updated:/)).toBeInTheDocument()
    );
  });

  it('shows error message on fetch failure', async () => {
    mockFetch.mockRejectedValue(new Error('Network error'));
    render(<CompliancePanel />);
    await waitFor(() =>
      expect(screen.getByText(/Error fetching compliance/)).toBeInTheDocument()
    );
  });

  it('fetches /api/compliance on mount', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => PASS_REPORT });
    render(<CompliancePanel />);
    await waitFor(() =>
      expect(mockFetch).toHaveBeenCalledWith('/api/compliance')
    );
  });

  it('shows loading placeholder before data arrives', () => {
    mockFetch.mockReturnValue(new Promise(() => {})); // never resolves
    render(<CompliancePanel />);
    // Cards should show — indicator but not status values yet
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });
});
