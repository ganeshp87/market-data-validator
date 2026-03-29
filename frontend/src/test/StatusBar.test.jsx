import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import StatusBar from '../components/StatusBar';

// --- Mock useSSE hook ---

const mockUseSSE = vi.fn();

vi.mock('../hooks/useSSE', () => ({
  default: (...args) => mockUseSSE(...args),
}));

function defaultSSE(overrides = {}) {
  return {
    data: [],
    latest: null,
    error: null,
    connected: false,
    clear: vi.fn(),
    ...overrides,
  };
}

// Track which URL each call gets
let callIndex = 0;
const sseStates = {};

function setupSSE(states = {}) {
  sseStates.tick = states.tick || defaultSSE();
  sseStates.throughput = states.throughput || defaultSSE();
  sseStates.latency = states.latency || defaultSSE();

  callIndex = 0;
  mockUseSSE.mockImplementation((url, eventName) => {
    if (eventName === 'tick') return sseStates.tick;
    if (eventName === 'throughput') return sseStates.throughput;
    if (eventName === 'latency') return sseStates.latency;
    return defaultSSE();
  });
}

beforeEach(() => {
  setupSSE();
});

afterEach(() => {
  vi.restoreAllMocks();
});

// --- Tests ---

describe('StatusBar', () => {
  it('renders status bar element', () => {
    const { container } = render(<StatusBar />);
    expect(container.querySelector('.status-bar')).not.toBeNull();
  });

  it('shows Disconnected when no streams connected', () => {
    render(<StatusBar />);
    expect(screen.getByText('Disconnected')).toBeInTheDocument();
  });

  it('shows Connected when all streams connected', () => {
    setupSSE({
      tick: defaultSSE({ connected: true }),
      throughput: defaultSSE({ connected: true }),
      latency: defaultSSE({ connected: true }),
    });

    render(<StatusBar />);
    expect(screen.getByText('Connected')).toBeInTheDocument();
  });

  it('shows Partial when some streams connected', () => {
    setupSSE({
      tick: defaultSSE({ connected: true }),
      throughput: defaultSSE({ connected: false }),
      latency: defaultSSE({ connected: true }),
    });

    render(<StatusBar />);
    expect(screen.getByText('Partial')).toBeInTheDocument();
  });

  it('shows "No ticks" when no tick data', () => {
    render(<StatusBar />);
    expect(screen.getByText('No ticks')).toBeInTheDocument();
  });

  it('displays latest symbol and price', () => {
    setupSSE({
      tick: defaultSSE({
        connected: true,
        latest: { symbol: 'BTCUSDT', price: '45123.45' },
      }),
    });

    render(<StatusBar />);
    expect(screen.getByText('BTCUSDT')).toBeInTheDocument();
    expect(screen.getByText('$45,123.45')).toBeInTheDocument();
  });

  it('shows throughput as msg/s', () => {
    setupSSE({
      throughput: defaultSSE({
        connected: true,
        latest: { messagesPerSecond: 12340, peakPerSecond: 15000, totalMessages: 500000 },
      }),
    });

    render(<StatusBar />);
    expect(screen.getByText('12,340 msg/s')).toBeInTheDocument();
  });

  it('shows peak throughput when available', () => {
    setupSSE({
      throughput: defaultSSE({
        connected: true,
        latest: { messagesPerSecond: 12340, peakPerSecond: 15000, totalMessages: 500000 },
      }),
    });

    render(<StatusBar />);
    expect(screen.getByText('peak: 15,000')).toBeInTheDocument();
  });

  it('shows p95 latency', () => {
    setupSSE({
      latency: defaultSSE({
        connected: true,
        latest: { p50: 28, p95: 42, p99: 87, min: 5, max: 200, count: 10000 },
      }),
    });

    render(<StatusBar />);
    expect(screen.getByText('p95: 42ms')).toBeInTheDocument();
  });

  it('shows placeholder for missing throughput data', () => {
    render(<StatusBar />);
    expect(screen.getByText('— msg/s')).toBeInTheDocument();
  });

  it('shows placeholder for missing latency data', () => {
    render(<StatusBar />);
    expect(screen.getByText('p95: —')).toBeInTheDocument();
  });

  it('uses correct SSE URLs and event names', () => {
    render(<StatusBar />);

    expect(mockUseSSE).toHaveBeenCalledWith(
      '/api/stream/ticks', 'tick', expect.objectContaining({ maxItems: 1 })
    );
    expect(mockUseSSE).toHaveBeenCalledWith(
      '/api/stream/throughput', 'throughput', expect.objectContaining({ maxItems: 1 })
    );
    expect(mockUseSSE).toHaveBeenCalledWith(
      '/api/stream/latency', 'latency', expect.objectContaining({ maxItems: 1 })
    );
  });

  it('has role="status" for accessibility', () => {
    const { container } = render(<StatusBar />);
    expect(container.querySelector('[role="status"]')).not.toBeNull();
  });

  it('shows green dot when all connected', () => {
    setupSSE({
      tick: defaultSSE({ connected: true }),
      throughput: defaultSSE({ connected: true }),
      latency: defaultSSE({ connected: true }),
    });

    const { container } = render(<StatusBar />);
    expect(container.querySelector('.status-dot.green')).not.toBeNull();
  });

  it('shows red dot when all disconnected', () => {
    const { container } = render(<StatusBar />);
    expect(container.querySelector('.status-dot.red')).not.toBeNull();
  });

  it('shows yellow dot when partially connected', () => {
    setupSSE({
      tick: defaultSSE({ connected: true }),
      throughput: defaultSSE({ connected: false }),
      latency: defaultSSE({ connected: false }),
    });

    const { container } = render(<StatusBar />);
    expect(container.querySelector('.status-dot.yellow')).not.toBeNull();
  });
});
