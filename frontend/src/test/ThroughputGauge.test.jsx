import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import ThroughputGauge from '../components/ThroughputGauge';

// --- Mock useSSE ---

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

const sseStates = {};

function setupSSE(states = {}) {
  sseStates.throughput = states.throughput || defaultSSE();
  sseStates.validation = states.validation || defaultSSE();

  mockUseSSE.mockImplementation((url, eventName) => {
    if (eventName === 'throughput') return sseStates.throughput;
    if (eventName === 'validation') return sseStates.validation;
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

describe('ThroughputGauge', () => {
  it('renders the gauge container', () => {
    render(<ThroughputGauge />);
    expect(screen.getByTestId('throughput-gauge')).toBeInTheDocument();
  });

  it('renders the heading', () => {
    render(<ThroughputGauge />);
    expect(screen.getByText('Throughput')).toBeInTheDocument();
  });

  it('shows Disconnected when not connected', () => {
    render(<ThroughputGauge />);
    expect(screen.getByText('Disconnected')).toBeInTheDocument();
  });

  it('shows Live when connected', () => {
    setupSSE({ throughput: defaultSSE({ connected: true }) });
    render(<ThroughputGauge />);
    expect(screen.getByText('Live')).toBeInTheDocument();
  });

  it('shows 0 msg/s when no data', () => {
    render(<ThroughputGauge />);
    expect(screen.getByText('msg/s')).toBeInTheDocument();
    expect(screen.getByTestId('tg-rate').textContent).toContain('0');
  });

  it('displays current msg/s from latest', () => {
    setupSSE({
      throughput: defaultSSE({
        connected: true,
        latest: { messagesPerSecond: 12340, peakPerSecond: 15000, totalMessages: 500000 },
      }),
    });

    render(<ThroughputGauge />);
    expect(screen.getByTestId('tg-rate').textContent).toContain('12,340');
  });

  it('displays peak value', () => {
    setupSSE({
      throughput: defaultSSE({
        connected: true,
        latest: { messagesPerSecond: 5000, peakPerSecond: 15000, totalMessages: 100000 },
      }),
    });

    render(<ThroughputGauge />);
    expect(screen.getByText('15,000')).toBeInTheDocument();
  });

  it('displays total messages', () => {
    setupSSE({
      throughput: defaultSSE({
        connected: true,
        latest: { messagesPerSecond: 1000, peakPerSecond: 5000, totalMessages: 500000 },
      }),
    });

    render(<ThroughputGauge />);
    expect(screen.getByText('500,000')).toBeInTheDocument();
  });

  it('shows history count from data array length', () => {
    setupSSE({
      throughput: defaultSSE({
        connected: true,
        data: [
          { messagesPerSecond: 100 },
          { messagesPerSecond: 200 },
          { messagesPerSecond: 300 },
        ],
        latest: { messagesPerSecond: 300, peakPerSecond: 300, totalMessages: 600 },
      }),
    });

    render(<ThroughputGauge />);
    expect(screen.getByText('3s')).toBeInTheDocument();
  });

  it('renders sparkline bars from history data', () => {
    setupSSE({
      throughput: defaultSSE({
        connected: true,
        data: [
          { messagesPerSecond: 100 },
          { messagesPerSecond: 200 },
        ],
        latest: { messagesPerSecond: 200, peakPerSecond: 200, totalMessages: 300 },
      }),
    });

    const { container } = render(<ThroughputGauge />);
    const bars = container.querySelectorAll('.tg-spark-bar');
    expect(bars.length).toBe(2);
  });

  it('sparkline bars have height proportional to max', () => {
    setupSSE({
      throughput: defaultSSE({
        connected: true,
        data: [
          { messagesPerSecond: 50 },
          { messagesPerSecond: 100 },
        ],
        latest: { messagesPerSecond: 100, peakPerSecond: 100, totalMessages: 150 },
      }),
    });

    const { container } = render(<ThroughputGauge />);
    const bars = container.querySelectorAll('.tg-spark-bar');
    // First bar: 50/100 = 50%, second bar: 100/100 = 100%
    expect(bars[0].style.height).toBe('50%');
    expect(bars[1].style.height).toBe('100%');
  });

  it('shows UNKNOWN badge when no validation data', () => {
    render(<ThroughputGauge />);
    expect(screen.getByText('UNKNOWN')).toBeInTheDocument();
  });

  it('shows PASS badge from validation stream', () => {
    setupSSE({
      validation: defaultSSE({
        connected: true,
        latest: {
          results: {
            THROUGHPUT: { status: 'PASS', details: { dropDetected: false } },
          },
        },
      }),
    });

    render(<ThroughputGauge />);
    expect(screen.getByText('PASS')).toBeInTheDocument();
  });

  it('shows drop alert when dropDetected is true', () => {
    setupSSE({
      validation: defaultSSE({
        connected: true,
        latest: {
          results: {
            THROUGHPUT: { status: 'WARN', details: { dropDetected: true } },
          },
        },
      }),
    });

    render(<ThroughputGauge />);
    expect(screen.getByTestId('tg-drop-alert')).toBeInTheDocument();
    expect(screen.getByText(/drop detected/i)).toBeInTheDocument();
  });

  it('does not show drop alert when dropDetected is false', () => {
    setupSSE({
      validation: defaultSSE({
        connected: true,
        latest: {
          results: {
            THROUGHPUT: { status: 'PASS', details: { dropDetected: false } },
          },
        },
      }),
    });

    render(<ThroughputGauge />);
    expect(screen.queryByTestId('tg-drop-alert')).toBeNull();
  });

  it('subscribes to correct SSE endpoints', () => {
    render(<ThroughputGauge />);

    expect(mockUseSSE).toHaveBeenCalledWith(
      '/api/stream/throughput', 'throughput', expect.objectContaining({ maxItems: 60 }),
    );
    expect(mockUseSSE).toHaveBeenCalledWith(
      '/api/stream/validation', 'validation', expect.objectContaining({ maxItems: 1 }),
    );
  });

  it('has sparkline with aria-label for accessibility', () => {
    render(<ThroughputGauge />);
    expect(screen.getByLabelText('Throughput history')).toBeInTheDocument();
  });
});
