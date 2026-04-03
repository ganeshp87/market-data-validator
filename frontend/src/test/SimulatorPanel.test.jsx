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
    mockFetch.mockImplementation((url, opts) => {
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

  it('shows hint when no connectionId', () => {
    mockFetch.mockResolvedValue({ ok: true, json: async () => [] });
    render(<SimulatorPanel connectionId={null} />);
    expect(screen.getByText(/Select or create an LVWR_T/)).toBeInTheDocument();
  });
});
