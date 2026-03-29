import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import LatencyChart from '../components/LatencyChart';

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

// --- Mock canvas context (jsdom has no canvas) ---

const mockCtx = {
  fillStyle: '',
  strokeStyle: '',
  lineWidth: 1,
  font: '',
  textAlign: '',
  textBaseline: '',
  fillRect: vi.fn(),
  fillText: vi.fn(),
  beginPath: vi.fn(),
  moveTo: vi.fn(),
  lineTo: vi.fn(),
  stroke: vi.fn(),
  setLineDash: vi.fn(),
  measureText: vi.fn(() => ({ width: 60 })),
};

// --- Mock requestAnimationFrame ---

let rafCallback = null;
const originalRAF = globalThis.requestAnimationFrame;
const originalCAF = globalThis.cancelAnimationFrame;

beforeEach(() => {
  mockUseSSE.mockReturnValue(defaultSSE());

  globalThis.requestAnimationFrame = vi.fn((cb) => {
    rafCallback = cb;
    return 1;
  });
  globalThis.cancelAnimationFrame = vi.fn();

  // Mock canvas getContext
  HTMLCanvasElement.prototype.getContext = vi.fn(() => mockCtx);
});

afterEach(() => {
  vi.restoreAllMocks();
  globalThis.requestAnimationFrame = originalRAF;
  globalThis.cancelAnimationFrame = originalCAF;
  rafCallback = null;
});

// --- Tests ---

describe('LatencyChart', () => {
  it('renders the chart container', () => {
    render(<LatencyChart />);
    expect(screen.getByTestId('latency-chart')).toBeInTheDocument();
  });

  it('renders the heading', () => {
    render(<LatencyChart />);
    expect(screen.getByText(/Latency/)).toBeInTheDocument();
    expect(screen.getByText(/p50.*p95.*p99/)).toBeInTheDocument();
  });

  it('renders a canvas element', () => {
    render(<LatencyChart />);
    expect(screen.getByTestId('latency-canvas')).toBeInTheDocument();
  });

  it('shows Disconnected when not connected', () => {
    render(<LatencyChart />);
    expect(screen.getByText('Disconnected')).toBeInTheDocument();
  });

  it('shows Live when connected', () => {
    mockUseSSE.mockReturnValue(defaultSSE({ connected: true }));
    render(<LatencyChart />);
    expect(screen.getByText('Live')).toBeInTheDocument();
  });

  it('shows green dot when connected', () => {
    mockUseSSE.mockReturnValue(defaultSSE({ connected: true }));
    const { container } = render(<LatencyChart />);
    expect(container.querySelector('.status-dot.green')).not.toBeNull();
  });

  it('shows red dot when disconnected', () => {
    const { container } = render(<LatencyChart />);
    expect(container.querySelector('.status-dot.red')).not.toBeNull();
  });

  it('subscribes to /api/stream/latency with correct params', () => {
    render(<LatencyChart />);
    expect(mockUseSSE).toHaveBeenCalledWith(
      '/api/stream/latency',
      'latency',
      expect.objectContaining({ maxItems: 1 }),
    );
  });

  it('shows sample count', () => {
    render(<LatencyChart />);
    expect(screen.getByText('0 samples')).toBeInTheDocument();
  });

  it('starts requestAnimationFrame loop on mount', () => {
    render(<LatencyChart />);
    expect(globalThis.requestAnimationFrame).toHaveBeenCalled();
  });

  it('cancels animation frame on unmount', () => {
    const { unmount } = render(<LatencyChart />);
    unmount();
    expect(globalThis.cancelAnimationFrame).toHaveBeenCalled();
  });

  it('calls getContext on canvas during draw', () => {
    render(<LatencyChart />);
    // Trigger the animation callback
    if (rafCallback) rafCallback();
    expect(HTMLCanvasElement.prototype.getContext).toHaveBeenCalledWith('2d');
  });

  it('draws background fill on every frame', () => {
    render(<LatencyChart />);
    if (rafCallback) rafCallback();
    expect(mockCtx.fillRect).toHaveBeenCalled();
  });

  it('draws the SLA threshold line', () => {
    render(<LatencyChart />);
    if (rafCallback) rafCallback();
    // setLineDash is called for the dashed SLA line
    expect(mockCtx.setLineDash).toHaveBeenCalledWith([6, 4]);
    // then reset
    expect(mockCtx.setLineDash).toHaveBeenCalledWith([]);
  });

  it('draws "Waiting for data" when connected but no points', () => {
    mockUseSSE.mockReturnValue(defaultSSE({ connected: true }));
    render(<LatencyChart />);
    if (rafCallback) rafCallback();
    expect(mockCtx.fillText).toHaveBeenCalledWith(
      'Waiting for data…',
      expect.any(Number),
      expect.any(Number),
    );
  });

  it('draws "Disconnected" text when not connected and no points', () => {
    render(<LatencyChart />);
    if (rafCallback) rafCallback();
    expect(mockCtx.fillText).toHaveBeenCalledWith(
      'Disconnected',
      expect.any(Number),
      expect.any(Number),
    );
  });
});
