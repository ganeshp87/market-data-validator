import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import LiveTickFeed from '../components/LiveTickFeed';

// --- Mock useSSE hook ---

const mockUseSSE = vi.fn();

vi.mock('../hooks/useSSE', () => ({
  default: (...args) => mockUseSSE(...args),
}));

// Default return value
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

beforeEach(() => {
  mockUseSSE.mockReturnValue(defaultSSE());
});

afterEach(() => {
  vi.restoreAllMocks();
});

// --- Sample ticks ---

function makeTick(overrides = {}) {
  return {
    symbol: 'BTCUSDT',
    price: '45123.45',
    bid: '45120.00',
    ask: '45125.00',
    volume: '0.123',
    sequenceNum: 9847,
    exchangeTimestamp: '2026-03-23T14:30:01.000Z',
    receivedTimestamp: '2026-03-23T14:30:01.012Z',
    feedId: 'conn-1',
    latency: 12,
    ...overrides,
  };
}

// --- Tests ---

describe('LiveTickFeed', () => {
  it('renders the table headers', () => {
    render(<LiveTickFeed />);

    expect(screen.getByText('Time')).toBeInTheDocument();
    expect(screen.getByText('Symbol')).toBeInTheDocument();
    expect(screen.getByText('Price')).toBeInTheDocument();
    expect(screen.getByText('Volume')).toBeInTheDocument();
    expect(screen.getByText('Seq')).toBeInTheDocument();
    expect(screen.getByText('Latency')).toBeInTheDocument();
  });

  it('shows disconnected status when not connected', () => {
    render(<LiveTickFeed />);

    expect(screen.getByText('Disconnected')).toBeInTheDocument();
  });

  it('shows Live status when connected', () => {
    mockUseSSE.mockReturnValue(defaultSSE({ connected: true }));
    render(<LiveTickFeed />);

    expect(screen.getByText('Live')).toBeInTheDocument();
  });

  it('renders tick data in the table', () => {
    const tick = makeTick();
    mockUseSSE.mockReturnValue(defaultSSE({ data: [tick], connected: true }));

    render(<LiveTickFeed />);

    expect(screen.getByText('BTCUSDT')).toBeInTheDocument();
    expect(screen.getByText('0.123')).toBeInTheDocument();
    expect(screen.getByText('9847')).toBeInTheDocument();
    expect(screen.getByText('12ms')).toBeInTheDocument();
  });

  it('renders multiple ticks', () => {
    const ticks = [
      makeTick({ symbol: 'BTCUSDT', sequenceNum: 1 }),
      makeTick({ symbol: 'ETHUSDT', price: '3456.78', sequenceNum: 2 }),
    ];
    mockUseSSE.mockReturnValue(defaultSSE({ data: ticks, connected: true }));

    render(<LiveTickFeed />);

    expect(screen.getByText('BTCUSDT')).toBeInTheDocument();
    expect(screen.getByText('ETHUSDT')).toBeInTheDocument();
  });

  it('shows tick count', () => {
    const ticks = [makeTick(), makeTick({ sequenceNum: 2 })];
    mockUseSSE.mockReturnValue(defaultSSE({ data: ticks }));

    render(<LiveTickFeed />);

    expect(screen.getByText('2 ticks')).toBeInTheDocument();
  });

  it('displays error message when present', () => {
    mockUseSSE.mockReturnValue(
      defaultSSE({ error: new Error('SSE disconnected. Reconnecting in 2s…') })
    );

    render(<LiveTickFeed />);

    expect(screen.getByText(/Reconnecting in 2s/)).toBeInTheDocument();
  });

  it('toggles pause/resume button', () => {
    render(<LiveTickFeed />);

    const btn = screen.getByText('⏸ Pause');
    fireEvent.click(btn);
    expect(screen.getByText('▶ Resume')).toBeInTheDocument();

    fireEvent.click(screen.getByText('▶ Resume'));
    expect(screen.getByText('⏸ Pause')).toBeInTheDocument();
  });

  it('calls clear() when Clear button is clicked', () => {
    const clearFn = vi.fn();
    mockUseSSE.mockReturnValue(defaultSSE({ clear: clearFn }));

    render(<LiveTickFeed />);

    fireEvent.click(screen.getByText('🗑 Clear'));
    expect(clearFn).toHaveBeenCalledOnce();
  });

  it('passes symbol filter to useSSE URL', () => {
    render(<LiveTickFeed />);

    const input = screen.getByPlaceholderText('Filter by symbol…');
    fireEvent.change(input, { target: { value: 'ethusdt' } });

    // useSSE should be called with the filtered URL (uppercased)
    expect(mockUseSSE).toHaveBeenCalledWith(
      '/api/stream/ticks?symbol=ETHUSDT',
      'tick',
      expect.objectContaining({ maxItems: 500 })
    );
  });

  it('passes maxItems=500 to useSSE', () => {
    render(<LiveTickFeed />);

    expect(mockUseSSE).toHaveBeenCalledWith(
      '/api/stream/ticks',
      'tick',
      expect.objectContaining({ maxItems: 500 })
    );
  });

  it('color-codes latency: good (<=50ms)', () => {
    const tick = makeTick({ latency: 12 });
    mockUseSSE.mockReturnValue(defaultSSE({ data: [tick] }));

    const { container } = render(<LiveTickFeed />);

    const latencyCell = container.querySelector('.latency-good');
    expect(latencyCell).not.toBeNull();
    expect(latencyCell.textContent).toBe('12ms');
  });

  it('color-codes latency: warn (51-200ms)', () => {
    const tick = makeTick({ latency: 150 });
    mockUseSSE.mockReturnValue(defaultSSE({ data: [tick] }));

    const { container } = render(<LiveTickFeed />);

    const latencyCell = container.querySelector('.latency-warn');
    expect(latencyCell).not.toBeNull();
  });

  it('color-codes latency: bad (>200ms)', () => {
    const tick = makeTick({ latency: 500 });
    mockUseSSE.mockReturnValue(defaultSSE({ data: [tick] }));

    const { container } = render(<LiveTickFeed />);

    const latencyCell = container.querySelector('.latency-bad');
    expect(latencyCell).not.toBeNull();
  });

  it('disables Export button when no data', () => {
    render(<LiveTickFeed />);

    const exportBtn = screen.getByText('📥 Export');
    expect(exportBtn).toBeDisabled();
  });

  it('enables Export button when data exists', () => {
    mockUseSSE.mockReturnValue(defaultSSE({ data: [makeTick()] }));

    render(<LiveTickFeed />);

    const exportBtn = screen.getByText('📥 Export');
    expect(exportBtn).not.toBeDisabled();
  });

  // --- Edge cases ---

  it('renders tick with very long symbol name', () => {
    const longSymbol = 'SUPERLONGCRYPTOTOKENUSDT';
    mockUseSSE.mockReturnValue(
      defaultSSE({ data: [makeTick({ symbol: longSymbol })] })
    );

    render(<LiveTickFeed />);

    expect(screen.getByText(longSymbol)).toBeInTheDocument();
  });

  it('renders tick with zero price', () => {
    mockUseSSE.mockReturnValue(
      defaultSSE({ data: [makeTick({ price: '0' })] })
    );

    const { container } = render(<LiveTickFeed />);

    const priceCell = container.querySelector('.col-price');
    expect(priceCell.textContent).toMatch(/^0\.00/);
  });

  it('handles many ticks without crashing', () => {
    const ticks = [];
    for (let i = 0; i < 100; i++) {
      ticks.push(makeTick({ sequenceNum: i, price: `${45000 + i}.00` }));
    }
    mockUseSSE.mockReturnValue(defaultSSE({ data: ticks }));

    const { container } = render(<LiveTickFeed />);

    const rows = container.querySelectorAll('tbody tr');
    expect(rows.length).toBe(100);
  });
});
