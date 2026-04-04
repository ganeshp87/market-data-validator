import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import SimulatorPanel from '../components/SimulatorPanel';

const mockFetch = vi.fn();

beforeEach(() => {
  vi.stubGlobal('fetch', mockFetch);
  mockFetch.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const SCENARIOS = [
  { name: 'SEQUENCE_GAP', description: 'Skip seqNums' },
  { name: 'PRICE_SPIKE', description: 'Price +14%' },
  { name: 'NEGATIVE_PRICE', description: 'Negative price' },
];

function mockScenariosAndStatus() {
  mockFetch.mockImplementation((url) => {
    if (url.includes('/api/simulator/scenarios')) {
      return Promise.resolve({ ok: true, json: async () => SCENARIOS });
    }
    if (url.includes('/api/simulator/status')) {
      return Promise.resolve({
        ok: true,
        json: async () => ({
          running: true,
          mode: 'CLEAN',
          ticksSent: 1234,
          failuresInjected: 5,
          ticksPerSecond: 50,
          failureRate: 0.1,
        }),
      });
    }
    return Promise.resolve({ ok: true, json: async () => ({}) });
  });
}

describe('SimulatorPanel', () => {
  it('renders the panel heading', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => [] });
    render(<SimulatorPanel connectionId={null} />);
    expect(screen.getByText(/Simulator/)).toBeInTheDocument();
  });

  it('shows mode buttons', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => [] });
    render(<SimulatorPanel connectionId={null} />);
    expect(screen.getByText('CLEAN')).toBeInTheDocument();
    expect(screen.getByText('NOISY')).toBeInTheDocument();
    expect(screen.getByText('CHAOS')).toBeInTheDocument();
    expect(screen.getByText('SCENARIO')).toBeInTheDocument();
  });

  it('shows scenario selector when SCENARIO mode selected', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => SCENARIOS });
    render(<SimulatorPanel connectionId={null} />);
    fireEvent.click(screen.getByText('SCENARIO'));
    await waitFor(() =>
      expect(screen.getByRole('combobox')).toBeInTheDocument()
    );
  });

  it('hides scenario selector in non-SCENARIO modes', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => [] });
    render(<SimulatorPanel connectionId={null} />);
    fireEvent.click(screen.getByText('CLEAN'));
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });

  it('disables Apply Config when no connectionId', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => [] });
    render(<SimulatorPanel connectionId={null} />);
    const btn = screen.getByText('Apply Config');
    expect(btn).toBeDisabled();
  });

  it('enables Apply Config when connectionId provided', async () => {
    mockScenariosAndStatus();
    render(<SimulatorPanel connectionId="conn-1" />);
    await waitFor(() => {
      const btn = screen.getByText('Apply Config');
      expect(btn).not.toBeDisabled();
    });
  });

  it('calls PUT /api/simulator/config on apply', async () => {
    mockScenariosAndStatus();
    mockFetch.mockImplementation((_url, opts) => {
      if (opts?.method === 'PUT') return Promise.resolve({ ok: true, json: async () => ({}) });
      return Promise.resolve({ ok: true, json: async () => SCENARIOS });
    });
    render(<SimulatorPanel connectionId="conn-1" />);
    await waitFor(() => expect(screen.getByText('Apply Config')).not.toBeDisabled());
    fireEvent.click(screen.getByText('Apply Config'));
    await waitFor(() =>
      expect(mockFetch).toHaveBeenCalledWith(
        expect.stringContaining('/api/simulator/config?connectionId=conn-1'),
        expect.objectContaining({ method: 'PUT' })
      )
    );
  });

  it('shows live status when connectionId provided and status available', async () => {
    mockScenariosAndStatus();
    vi.useFakeTimers();
    render(<SimulatorPanel connectionId="conn-1" />);
    await act(async () => {
      vi.advanceTimersByTime(2500);
    });
    vi.useRealTimers();
    await waitFor(() =>
      expect(screen.getByText(/Live Status/)).toBeInTheDocument()
    );
  });

  it('shows "Not applied yet" when UI mode differs from live running mode', async () => {
    // status returns mode: 'CLEAN' with running: true
    mockScenariosAndStatus();
    render(<SimulatorPanel connectionId="conn-1" />);

    // Wait for status to load — Live Status section confirms status is available
    await waitFor(() => expect(screen.getByText(/Live Status/)).toBeInTheDocument());

    // Initial state: UI mode = CLEAN, live mode = CLEAN → no pending indicator
    expect(screen.queryByText(/Not applied yet/)).not.toBeInTheDocument();

    // Switch UI to NOISY — now UI mode ≠ live mode → pending
    fireEvent.click(screen.getByText('NOISY'));
    expect(screen.getByText(/Not applied yet/)).toBeInTheDocument();

    // Apply Config button should now carry btn-pending class
    const applyBtn = screen.getByText('Apply Config');
    expect(applyBtn.className).toContain('btn-pending');
  });

  it('shows hint when no connectionId', () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => [] });
    render(<SimulatorPanel connectionId={null} />);
    expect(screen.getByText(/Select or create an LVWR_T/)).toBeInTheDocument();
  });

  it('renders all 12 failure type chips', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => [] });
    render(<SimulatorPanel connectionId={null} />);
    // Chip short names as rendered in the DOM
    const expectedChips = [
      'PRICE_SPIKE', 'SEQ_GAP', 'DUPLICATE', 'STALE_TS',
      'NEG_PRICE', 'MALFORMED', 'DISCONNECT', 'CUMVOL_BWD',
      'THROTTLE', 'RECONNECT', 'OUT_ORDER', 'SYM_MISMTCH',
    ];
    for (const label of expectedChips) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
  });

  it('scenario dropdown renders option descriptions when SCENARIO selected', async () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => SCENARIOS });
    render(<SimulatorPanel connectionId={null} />);
    fireEvent.click(screen.getByText('SCENARIO'));
    await waitFor(() => expect(screen.getByRole('combobox')).toBeInTheDocument());
    // Each scenario option must show "NAME: description"
    for (const s of SCENARIOS) {
      expect(screen.getByText(`${s.name}: ${s.description}`)).toBeInTheDocument();
    }
  });

  it('failure chip count reflects status.failuresInjected values', async () => {
    mockFetch.mockImplementation((url) => {
      if (url.includes('/api/simulator/scenarios')) {
        return Promise.resolve({ ok: true, json: async () => [] });
      }
      if (url.includes('/api/simulator/status')) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            running: true,
            mode: 'CLEAN',
            ticksSent: 100,
            failuresInjected: { PRICE_SPIKE: 7, SEQUENCE_GAP: 3 },
            ticksPerSecond: 50,
          }),
        });
      }
      return Promise.resolve({ ok: true, json: async () => ({}) });
    });
    render(<SimulatorPanel connectionId="conn-1" />);
    await waitFor(() => expect(screen.getByText('7')).toBeInTheDocument());
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('failure chip gets firing class when count greater than zero', async () => {
    mockFetch.mockImplementation((url) => {
      if (url.includes('/api/simulator/scenarios')) {
        return Promise.resolve({ ok: true, json: async () => [] });
      }
      if (url.includes('/api/simulator/status')) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            running: true,
            mode: 'CLEAN',
            ticksSent: 50,
            failuresInjected: { PRICE_SPIKE: 4 },
            ticksPerSecond: 50,
          }),
        });
      }
      return Promise.resolve({ ok: true, json: async () => ({}) });
    });
    render(<SimulatorPanel connectionId="conn-1" />);
    // Wait until the PRICE_SPIKE chip transitions to the firing state
    await waitFor(() => {
      const chipLabel = screen.getByText('PRICE_SPIKE');
      expect(chipLabel.closest('.failure-chip').className).toContain('firing');
    });
  });
});
