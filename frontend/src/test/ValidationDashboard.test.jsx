import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ValidationDashboard from '../components/ValidationDashboard';

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

beforeEach(() => {
  mockUseSSE.mockReturnValue(defaultSSE());
});

afterEach(() => {
  vi.restoreAllMocks();
});

// --- Sample validation data ---

function makeResult(area, status = 'PASS', overrides = {}) {
  return {
    area,
    status,
    message: `${area} is ${status}`,
    metric: 99.5,
    threshold: 99.0,
    timestamp: '2026-03-23T14:30:00Z',
    details: {},
    ...overrides,
  };
}

function makeValidationPayload(resultOverrides = {}) {
  const results = {};
  const areas = [
    'ACCURACY', 'LATENCY', 'COMPLETENESS', 'RECONNECTION',
    'THROUGHPUT', 'ORDERING', 'SUBSCRIPTION', 'STATEFUL', 'SOURCE',
  ];
  for (const area of areas) {
    results[area] = resultOverrides[area] || makeResult(area);
  }
  return {
    timestamp: '2026-03-23T14:30:00Z',
    results,
    overallStatus: 'PASS',
    ticksProcessed: 12340,
  };
}

// --- Tests ---

describe('ValidationDashboard', () => {
  it('renders heading', () => {
    render(<ValidationDashboard />);
    expect(screen.getByText('Validation Dashboard')).toBeInTheDocument();
  });

  it('renders all 9 validation area cards', () => {
    mockUseSSE.mockReturnValue(
      defaultSSE({ latest: makeValidationPayload() })
    );

    render(<ValidationDashboard />);

    expect(screen.getByText('Accuracy')).toBeInTheDocument();
    expect(screen.getByText('Latency')).toBeInTheDocument();
    expect(screen.getByText('Completeness')).toBeInTheDocument();
    expect(screen.getByText('Reconnection')).toBeInTheDocument();
    expect(screen.getByText('Throughput')).toBeInTheDocument();
    expect(screen.getByText('Ordering')).toBeInTheDocument();
    expect(screen.getByText('Subscription')).toBeInTheDocument();
    expect(screen.getByText('Stateful')).toBeInTheDocument();
  });

  it('shows 9 cards even with no data', () => {
    render(<ValidationDashboard />);

    // All 9 area names should be present regardless of data
    expect(screen.getByText('Accuracy')).toBeInTheDocument();
    expect(screen.getByText('Stateful')).toBeInTheDocument();
  });

  it('shows PASS status with green indicator', () => {
    mockUseSSE.mockReturnValue(
      defaultSSE({ latest: makeValidationPayload() })
    );

    const { container } = render(<ValidationDashboard />);

    const passCards = container.querySelectorAll('.vd-card.status-pass');
    expect(passCards.length).toBe(9);
  });

  it('shows WARN status for failing areas', () => {
    const payload = makeValidationPayload({
      COMPLETENESS: makeResult('COMPLETENESS', 'WARN', {
        message: '2 gaps found',
        metric: 2,
        threshold: 0,
      }),
    });

    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    const { container } = render(<ValidationDashboard />);

    const warnCards = container.querySelectorAll('.vd-card.status-warn');
    expect(warnCards.length).toBe(1);
  });

  it('shows FAIL status with red indicator', () => {
    const payload = makeValidationPayload({
      ACCURACY: makeResult('ACCURACY', 'FAIL', {
        message: 'Accuracy dropped below 99%',
      }),
    });

    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    const { container } = render(<ValidationDashboard />);

    const failCards = container.querySelectorAll('.vd-card.status-fail');
    expect(failCards.length).toBe(1);
  });

  it('displays overall status', () => {
    const payload = makeValidationPayload();
    payload.overallStatus = 'WARN';
    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    render(<ValidationDashboard />);

    expect(screen.getByText(/Overall: WARN/)).toBeInTheDocument();
  });

  it('displays ticks processed count', () => {
    mockUseSSE.mockReturnValue(
      defaultSSE({ latest: makeValidationPayload() })
    );

    render(<ValidationDashboard />);

    expect(screen.getByText('12,340 ticks processed')).toBeInTheDocument();
  });

  it('displays validation message on each card', () => {
    const payload = makeValidationPayload({
      LATENCY: makeResult('LATENCY', 'PASS', {
        message: 'p95: 42ms (threshold: 500ms)',
      }),
    });

    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    render(<ValidationDashboard />);

    expect(screen.getByText('p95: 42ms (threshold: 500ms)')).toBeInTheDocument();
  });

  it('expands card details on click', () => {
    const payload = makeValidationPayload({
      LATENCY: makeResult('LATENCY', 'PASS', {
        details: { p50: 28, p95: 42, p99: 87 },
      }),
    });

    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    render(<ValidationDashboard />);

    // Details should not be visible initially
    expect(screen.queryByText('p50')).not.toBeInTheDocument();

    // Click the Latency card
    fireEvent.click(screen.getByText('Latency'));

    // Details should now be visible
    expect(screen.getByText('p50')).toBeInTheDocument();
    expect(screen.getByText('28')).toBeInTheDocument();
    expect(screen.getByText('p95')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('collapses card details on second click', () => {
    const payload = makeValidationPayload({
      LATENCY: makeResult('LATENCY', 'PASS', {
        details: { p50: 28 },
      }),
    });

    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    render(<ValidationDashboard />);

    // Click to expand
    fireEvent.click(screen.getByText('Latency'));
    expect(screen.getByText('p50')).toBeInTheDocument();

    // Click to collapse
    fireEvent.click(screen.getByText('Latency'));
    expect(screen.queryByText('p50')).not.toBeInTheDocument();
  });

  it('formats accuracy metric as percentage', () => {
    const payload = makeValidationPayload({
      ACCURACY: makeResult('ACCURACY', 'PASS', { metric: 99.98, threshold: 99.9 }),
    });

    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    render(<ValidationDashboard />);

    expect(screen.getByText('Value: 99.98%')).toBeInTheDocument();
    expect(screen.getByText('Threshold: 99.90%')).toBeInTheDocument();
  });

  it('formats latency metric as ms', () => {
    const payload = makeValidationPayload({
      LATENCY: makeResult('LATENCY', 'PASS', { metric: 42, threshold: 500 }),
    });

    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    render(<ValidationDashboard />);

    expect(screen.getByText('Value: 42ms')).toBeInTheDocument();
    expect(screen.getByText('Threshold: 500ms')).toBeInTheDocument();
  });

  it('shows error message when SSE has error', () => {
    mockUseSSE.mockReturnValue(
      defaultSSE({ error: new Error('SSE disconnected. Reconnecting in 2s…') })
    );

    render(<ValidationDashboard />);

    expect(screen.getByText(/Reconnecting in 2s/)).toBeInTheDocument();
  });

  it('passes correct URL and event name to useSSE', () => {
    render(<ValidationDashboard />);

    expect(mockUseSSE).toHaveBeenCalledWith(
      '/api/stream/validation',
      'validation',
      expect.objectContaining({ maxItems: 1 })
    );
  });

  it('shows "Waiting for data" when no result for an area', () => {
    render(<ValidationDashboard />);

    // With no data, all cards should show the default message
    const waitingMessages = screen.getAllByText('Waiting for data…');
    expect(waitingMessages.length).toBe(9);
  });

  it('renders PASS icons on cards', () => {
    mockUseSSE.mockReturnValue(
      defaultSSE({ latest: makeValidationPayload() })
    );

    const { container } = render(<ValidationDashboard />);

    // All 9 cards should have the pass class
    const passCards = container.querySelectorAll('.vd-card.status-pass');
    expect(passCards.length).toBe(9);
  });

  // --- Edge cases ---

  it('renders mixed statuses: PASS, WARN, and FAIL simultaneously', () => {
    const payload = makeValidationPayload({
      ACCURACY: makeResult('ACCURACY', 'FAIL', { metric: 85.0 }),
      LATENCY: makeResult('LATENCY', 'WARN', { metric: 180 }),
      COMPLETENESS: makeResult('COMPLETENESS', 'FAIL', { metric: 90.0 }),
    });
    payload.overallStatus = 'FAIL';
    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    const { container } = render(<ValidationDashboard />);

    const failCards = container.querySelectorAll('.vd-card.status-fail');
    const warnCards = container.querySelectorAll('.vd-card.status-warn');
    const passCards = container.querySelectorAll('.vd-card.status-pass');
    expect(failCards.length).toBe(2);
    expect(warnCards.length).toBe(1);
    expect(passCards.length).toBe(6);
  });

  it('overall status reflects worst case among all areas', () => {
    const payload = makeValidationPayload({
      STATEFUL: makeResult('STATEFUL', 'FAIL', { metric: 80.0 }),
    });
    payload.overallStatus = 'FAIL';
    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));

    const { container } = render(<ValidationDashboard />);

    const overallEl = container.querySelector('.vd-overall.status-fail');
    expect(overallEl).not.toBeNull();
  });

  it('formats completeness metric as percentage not raw float', () => {
    const payload = makeValidationPayload({
      COMPLETENESS: makeResult('COMPLETENESS', 'FAIL', {
        metric: 6.200726833629408,
        threshold: 99.99,
        details: {},
      }),
    });
    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));
    render(<ValidationDashboard />);
    expect(screen.getByText('Value: 6.20%')).toBeInTheDocument();
    expect(screen.getByText('Threshold: 99.99%')).toBeInTheDocument();
  });

  it('completeness card shows gap events and missing seqNums from details', () => {
    const payload = makeValidationPayload({
      COMPLETENESS: makeResult('COMPLETENESS', 'FAIL', {
        metric: 16.3,
        details: { gapEventCount: 8, missingSequenceCount: 128 },
      }),
    });
    mockUseSSE.mockReturnValue(defaultSSE({ latest: payload }));
    render(<ValidationDashboard />);
    expect(screen.getByText('Gap events: 8')).toBeInTheDocument();
    expect(screen.getByText('Missing seqNums: 128')).toBeInTheDocument();
  });

  it('all 9 validator area names displayed correctly', () => {
    mockUseSSE.mockReturnValue(
      defaultSSE({ latest: makeValidationPayload() })
    );

    render(<ValidationDashboard />);

    const areaNames = [
      'Accuracy', 'Latency', 'Completeness', 'Reconnection',
      'Throughput', 'Ordering', 'Subscription', 'Stateful', 'Source',
    ];
    for (const name of areaNames) {
      expect(screen.getByText(name)).toBeInTheDocument();
    }
  });
});
