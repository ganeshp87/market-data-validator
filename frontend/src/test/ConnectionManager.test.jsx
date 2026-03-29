import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ConnectionManager from '../components/ConnectionManager';

// --- Mock fetch ---

const mockFetch = vi.fn();

beforeEach(() => {
  vi.stubGlobal('fetch', mockFetch);
  vi.stubGlobal('confirm', vi.fn(() => true));
  mockFetch.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

// --- Sample data ---

function makeConnection(overrides = {}) {
  return {
    id: 'conn-1',
    name: 'Binance BTC',
    url: 'wss://stream.binance.com:9443/ws',
    adapterType: 'BINANCE',
    symbols: ['BTCUSDT', 'ETHUSDT'],
    status: 'DISCONNECTED',
    connectedAt: null,
    lastTickAt: null,
    tickCount: 0,
    ...overrides,
  };
}

function mockFetchConnections(connections) {
  mockFetch.mockResolvedValueOnce({
    ok: true,
    json: async () => connections,
  });
}

function mockFetchAction(result = {}) {
  mockFetch.mockResolvedValueOnce({
    ok: true,
    json: async () => result,
  });
}

// --- Tests ---

describe('ConnectionManager', () => {
  it('shows loading state initially', () => {
    // Don't resolve the fetch yet
    mockFetch.mockReturnValue(new Promise(() => {}));
    render(<ConnectionManager />);

    expect(screen.getByText('Loading feeds…')).toBeInTheDocument();
  });

  it('renders connection list after loading', async () => {
    mockFetchConnections([makeConnection()]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('Binance BTC')).toBeInTheDocument();
    });
  });

  it('shows empty state when no connections', async () => {
    mockFetchConnections([]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText(/No feeds configured/)).toBeInTheDocument();
    });
  });

  it('displays connection status icon', async () => {
    mockFetchConnections([makeConnection({ status: 'CONNECTED' })]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('🟢')).toBeInTheDocument();
      expect(screen.getByText('CONNECTED')).toBeInTheDocument();
    });
  });

  it('displays symbols for a connection', async () => {
    mockFetchConnections([makeConnection()]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('BTCUSDT, ETHUSDT')).toBeInTheDocument();
    });
  });

  it('displays adapter type badge', async () => {
    mockFetchConnections([makeConnection()]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('BINANCE')).toBeInTheDocument();
    });
  });

  it('shows Start button for disconnected connections', async () => {
    mockFetchConnections([makeConnection({ status: 'DISCONNECTED' })]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('▶ Start')).toBeInTheDocument();
    });
  });

  it('shows Stop button for connected connections', async () => {
    mockFetchConnections([makeConnection({ status: 'CONNECTED' })]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('⏹ Stop')).toBeInTheDocument();
    });
  });

  it('calls start API when Start button clicked', async () => {
    mockFetchConnections([makeConnection({ status: 'DISCONNECTED' })]);

    render(<ConnectionManager />);

    await waitFor(() => screen.getByText('▶ Start'));

    // Mock the start POST + subsequent refresh
    mockFetchAction(makeConnection({ status: 'CONNECTED' }));
    mockFetchConnections([makeConnection({ status: 'CONNECTED' })]);

    fireEvent.click(screen.getByText('▶ Start'));

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalledWith('/api/feeds/conn-1/start', { method: 'POST' });
    });
  });

  it('calls stop API when Stop button clicked', async () => {
    mockFetchConnections([makeConnection({ status: 'CONNECTED' })]);

    render(<ConnectionManager />);

    await waitFor(() => screen.getByText('⏹ Stop'));

    mockFetchAction(makeConnection({ status: 'DISCONNECTED' }));
    mockFetchConnections([makeConnection({ status: 'DISCONNECTED' })]);

    fireEvent.click(screen.getByText('⏹ Stop'));

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalledWith('/api/feeds/conn-1/stop', { method: 'POST' });
    });
  });

  it('calls delete API with confirmation', async () => {
    mockFetchConnections([makeConnection()]);

    render(<ConnectionManager />);

    await waitFor(() => screen.getByText('🗑 Delete'));

    mockFetch.mockResolvedValueOnce({ ok: true, status: 204 });
    mockFetchConnections([]);

    fireEvent.click(screen.getByText('🗑 Delete'));

    await waitFor(() => {
      expect(window.confirm).toHaveBeenCalledWith('Delete connection "Binance BTC"?');
      expect(mockFetch).toHaveBeenCalledWith('/api/feeds/conn-1', { method: 'DELETE' });
    });
  });

  it('does not delete when confirmation cancelled', async () => {
    window.confirm.mockReturnValue(false);
    mockFetchConnections([makeConnection()]);

    render(<ConnectionManager />);

    await waitFor(() => screen.getByText('🗑 Delete'));

    fireEvent.click(screen.getByText('🗑 Delete'));

    // fetch should only have been called once (initial load), not for delete
    await waitFor(() => {
      const deleteCalls = mockFetch.mock.calls.filter(
        (call) => call[1]?.method === 'DELETE'
      );
      expect(deleteCalls).toHaveLength(0);
    });
  });

  it('shows tick count when ticks received', async () => {
    mockFetchConnections([makeConnection({ tickCount: 12340 })]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('12,340 ticks')).toBeInTheDocument();
    });
  });

  // --- Add Feed Dialog ---

  it('opens Add Feed dialog on button click', async () => {
    mockFetchConnections([]);

    render(<ConnectionManager />);

    await waitFor(() => screen.getByText('+ Add Feed'));

    fireEvent.click(screen.getByText('+ Add Feed'));

    expect(screen.getByText('Add Feed')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Binance BTC')).toBeInTheDocument();
  });

  it('submits new connection via POST /api/feeds', async () => {
    mockFetchConnections([]);

    render(<ConnectionManager />);

    await waitFor(() => screen.getByText('+ Add Feed'));

    fireEvent.click(screen.getByText('+ Add Feed'));

    fireEvent.change(screen.getByPlaceholderText('Binance BTC'), {
      target: { value: 'Test Feed' },
    });
    fireEvent.change(
      screen.getByPlaceholderText('wss://stream.binance.com:9443/ws'),
      { target: { value: 'wss://example.com/ws' } }
    );
    fireEvent.change(screen.getByPlaceholderText('BTCUSDT, ETHUSDT'), {
      target: { value: 'BTCUSDT' },
    });

    // Mock POST response + refresh
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => makeConnection({ name: 'Test Feed' }),
    });
    mockFetchConnections([makeConnection({ name: 'Test Feed' })]);

    fireEvent.click(screen.getByText('Connect'));

    await waitFor(() => {
      const postCalls = mockFetch.mock.calls.filter(
        (call) => call[0] === '/api/feeds' && call[1]?.method === 'POST'
      );
      expect(postCalls).toHaveLength(1);
      const body = JSON.parse(postCalls[0][1].body);
      expect(body.name).toBe('Test Feed');
      expect(body.url).toBe('wss://example.com/ws');
      expect(body.symbols).toEqual(['BTCUSDT']);
    });
  });

  it('closes dialog on Cancel', async () => {
    mockFetchConnections([]);

    render(<ConnectionManager />);

    await waitFor(() => screen.getByText('+ Add Feed'));

    fireEvent.click(screen.getByText('+ Add Feed'));
    expect(screen.getByText('Add Feed')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Cancel'));

    await waitFor(() => {
      expect(screen.queryByText('Add Feed')).not.toBeInTheDocument();
    });
  });

  it('shows error when fetch fails', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
    });

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText(/Failed to fetch feeds/)).toBeInTheDocument();
    });
  });

  it('renders multiple connections', async () => {
    mockFetchConnections([
      makeConnection({ id: 'c1', name: 'Binance', status: 'CONNECTED' }),
      makeConnection({ id: 'c2', name: 'Finnhub', status: 'DISCONNECTED', adapterType: 'FINNHUB' }),
    ]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('Binance')).toBeInTheDocument();
      expect(screen.getByText('Finnhub')).toBeInTheDocument();
    });
  });

  // --- Edge cases ---

  it('renders connection with empty symbols list', async () => {
    mockFetchConnections([makeConnection({ symbols: [] })]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('Binance BTC')).toBeInTheDocument();
    });
  });

  it('shows error when start API call fails', async () => {
    mockFetchConnections([makeConnection({ id: 'c1', name: 'Test', status: 'DISCONNECTED' })]);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('Test')).toBeInTheDocument();
    });

    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => ({ error: 'Start failed: 500' }),
    });

    const startBtn = screen.getByRole('button', { name: /start/i });
    fireEvent.click(startBtn);

    await waitFor(() => {
      expect(screen.getByText(/Start failed/i)).toBeInTheDocument();
    });
  });

  it('handles many connections (10+)', async () => {
    const connections = [];
    for (let i = 0; i < 10; i++) {
      connections.push(makeConnection({
        id: `conn-${i}`,
        name: `Feed ${i}`,
        symbols: ['BTCUSDT'],
      }));
    }
    mockFetchConnections(connections);

    render(<ConnectionManager />);

    await waitFor(() => {
      expect(screen.getByText('Feed 0')).toBeInTheDocument();
      expect(screen.getByText('Feed 9')).toBeInTheDocument();
    });
  });
});
