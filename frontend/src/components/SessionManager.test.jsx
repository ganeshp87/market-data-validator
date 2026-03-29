import React from 'react';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import SessionManager from './SessionManager';

// ── Helpers ──────────────────────────────────────────────

function makeSession(overrides = {}) {
  return {
    id: 1,
    name: 'test-session',
    feedId: 'feed-1',
    status: 'COMPLETED',
    startedAt: '2024-01-01T10:00:00Z',
    endedAt: '2024-01-01T10:15:00Z',
    tickCount: 5432,
    byteSize: 102400,
    ...overrides,
  };
}

function mockFetch(handler) {
  return vi.fn(async (url, opts) => {
    const result = handler(url, opts);
    if (result instanceof Promise) return result;
    return result;
  });
}

function jsonResponse(data, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => data,
    text: async () => JSON.stringify(data),
    blob: async () => new Blob([JSON.stringify(data)]),
  };
}

function errorResponse(status, message = '') {
  return {
    ok: false,
    status,
    json: async () => ({ error: message }),
    text: async () => message,
  };
}

describe('SessionManager', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  // ── Rendering & session list ──────────────────────────

  it('renders heading', async () => {
    global.fetch = mockFetch(() => jsonResponse([]));
    render(<SessionManager />);
    expect(screen.getByText(/💾 Sessions/)).toBeInTheDocument();
  });

  it('shows empty state when no sessions', async () => {
    global.fetch = mockFetch(() => jsonResponse([]));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText(/No recorded sessions yet/)).toBeInTheDocument();
    });
  });

  it('renders completed session cards', async () => {
    const sessions = [makeSession({ id: 1, name: 'btc-morning' }), makeSession({ id: 2, name: 'eth-volatile' })];
    global.fetch = mockFetch(() => jsonResponse(sessions));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText('btc-morning')).toBeInTheDocument();
      expect(screen.getByText('eth-volatile')).toBeInTheDocument();
    });
  });

  it('displays tick count in session card', async () => {
    global.fetch = mockFetch(() => jsonResponse([makeSession({ tickCount: 12345 })]));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText('12,345')).toBeInTheDocument();
    });
  });

  it('displays formatted byte size', async () => {
    global.fetch = mockFetch(() => jsonResponse([makeSession({ byteSize: 1048576 })]));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText('1.0 MB')).toBeInTheDocument();
    });
  });

  it('shows session status badge', async () => {
    global.fetch = mockFetch(() => jsonResponse([makeSession({ status: 'COMPLETED' })]));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    });
  });

  it('shows feed ID in card', async () => {
    global.fetch = mockFetch(() => jsonResponse([makeSession({ feedId: 'binance-1' })]));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText(/binance-1/)).toBeInTheDocument();
    });
  });

  // ── Polling ───────────────────────────────────────────

  it('polls sessions periodically', async () => {
    let callCount = 0;
    global.fetch = mockFetch(() => {
      callCount++;
      return jsonResponse([]);
    });
    render(<SessionManager />);
    await waitFor(() => expect(callCount).toBe(1));

    await act(async () => { vi.advanceTimersByTime(3000); });
    await waitFor(() => expect(callCount).toBe(2));

    await act(async () => { vi.advanceTimersByTime(3000); });
    await waitFor(() => expect(callCount).toBe(3));
  });

  // ── Recording: Start button ───────────────────────────

  it('shows Start Recording button when not recording', async () => {
    global.fetch = mockFetch(() => jsonResponse([]));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText(/Start Recording/)).toBeInTheDocument();
    });
  });

  it('opens start recording dialog on click', async () => {
    global.fetch = mockFetch(() => jsonResponse([]));
    render(<SessionManager />);
    await waitFor(() => screen.getByText(/Start Recording/));

    fireEvent.click(screen.getByText(/Start Recording/));
    expect(screen.getByText('Session Name')).toBeInTheDocument();
    expect(screen.getByText('Feed ID')).toBeInTheDocument();
  });

  it('start dialog can be cancelled', async () => {
    global.fetch = mockFetch(() => jsonResponse([]));
    render(<SessionManager />);
    await waitFor(() => screen.getByText(/Start Recording/));

    fireEvent.click(screen.getByText(/Start Recording/));
    expect(screen.getByText('Session Name')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Cancel'));
    expect(screen.queryByText('Session Name')).not.toBeInTheDocument();
  });

  it('start dialog submits with name and feedId', async () => {
    let capturedBody = null;
    global.fetch = mockFetch((url, opts) => {
      if (url === '/api/sessions/start' && opts?.method === 'POST') {
        capturedBody = JSON.parse(opts.body);
        return jsonResponse(makeSession({ status: 'RECORDING', name: 'my-session' }), 201);
      }
      return jsonResponse([]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText(/Start Recording/));

    fireEvent.click(screen.getByText(/Start Recording/));
    fireEvent.change(screen.getByPlaceholderText('btc-morning-session'), { target: { value: 'my-session' } });
    fireEvent.change(screen.getByPlaceholderText('feed-1'), { target: { value: 'binance-ws' } });

    fireEvent.click(screen.getByText(/Start$/));
    await waitFor(() => {
      expect(capturedBody).toEqual({ name: 'my-session', feedId: 'binance-ws' });
    });
  });

  it('start dialog submit button disabled when fields empty', async () => {
    global.fetch = mockFetch(() => jsonResponse([]));
    render(<SessionManager />);
    await waitFor(() => screen.getByText(/Start Recording/));

    fireEvent.click(screen.getByText(/Start Recording/));
    const submitBtn = screen.getByText(/Start$/);
    expect(submitBtn).toBeDisabled();
  });

  // ── Recording: Active recording display ───────────────

  it('shows active recording with stop button', async () => {
    const recordingSession = makeSession({ status: 'RECORDING', endedAt: null, tickCount: 1234, byteSize: 5678 });
    global.fetch = mockFetch(() => jsonResponse([recordingSession]));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText('test-session')).toBeInTheDocument();
      expect(screen.getByText(/Stop/)).toBeInTheDocument();
    });
  });

  it('calls stop endpoint on Stop click', async () => {
    const recordingSession = makeSession({ id: 42, status: 'RECORDING', endedAt: null });
    let stopCalled = false;
    global.fetch = mockFetch((url, opts) => {
      if (url === '/api/sessions/42/stop' && opts?.method === 'POST') {
        stopCalled = true;
        return jsonResponse(makeSession({ id: 42, status: 'COMPLETED' }));
      }
      return jsonResponse([recordingSession]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText(/Stop/));

    fireEvent.click(screen.getByText(/Stop/));
    await waitFor(() => expect(stopCalled).toBe(true));
  });

  // ── Delete ────────────────────────────────────────────

  it('shows delete button with confirmation', async () => {
    global.fetch = mockFetch(() => jsonResponse([makeSession()]));
    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    fireEvent.click(screen.getByTitle('Delete session'));
    expect(screen.getByText('Confirm')).toBeInTheDocument();
    expect(screen.getByText('Cancel')).toBeInTheDocument();
  });

  it('calls delete endpoint on confirm', async () => {
    let deleteCalled = false;
    global.fetch = mockFetch((url, opts) => {
      if (url === '/api/sessions/1' && opts?.method === 'DELETE') {
        deleteCalled = true;
        return { ok: true, status: 204, json: async () => ({}), text: async () => '' };
      }
      return jsonResponse([makeSession()]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    fireEvent.click(screen.getByTitle('Delete session'));
    fireEvent.click(screen.getByText('Confirm'));
    await waitFor(() => expect(deleteCalled).toBe(true));
  });

  it('cancels delete on Cancel click', async () => {
    global.fetch = mockFetch(() => jsonResponse([makeSession()]));
    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    fireEvent.click(screen.getByTitle('Delete session'));
    expect(screen.getByText('Confirm')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Cancel'));
    expect(screen.queryByText('Confirm')).not.toBeInTheDocument();
  });

  // ── Replay ────────────────────────────────────────────

  it('calls replay endpoint on Replay click', async () => {
    let replayCalled = false;
    global.fetch = mockFetch((url, opts) => {
      if (url === '/api/sessions/1/replay' && opts?.method === 'POST') {
        replayCalled = true;
        return jsonResponse({ sessionId: 1, ticksReplayed: 100, results: [] });
      }
      return jsonResponse([makeSession()]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    fireEvent.click(screen.getByText(/Replay/));
    await waitFor(() => expect(replayCalled).toBe(true));
  });

  it('shows replay panel after replay starts', async () => {
    global.fetch = mockFetch((url, opts) => {
      if (url === '/api/sessions/1/replay') {
        return jsonResponse({ sessionId: 1, ticksReplayed: 50, results: [] });
      }
      return jsonResponse([makeSession()]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    fireEvent.click(screen.getByText(/Replay/));
    await waitFor(() => {
      expect(screen.getByText(/Replay — Session #1/)).toBeInTheDocument();
    });
  });

  it('shows validation results after replay completes', async () => {
    const results = [
      { area: 'ACCURACY', status: 'PASS', message: 'All accurate' },
      { area: 'ORDERING', status: 'FAIL', message: '2 violations' },
    ];
    global.fetch = mockFetch((url) => {
      if (url === '/api/sessions/1/replay') {
        return jsonResponse({ sessionId: 1, ticksReplayed: 100, results });
      }
      return jsonResponse([makeSession()]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    fireEvent.click(screen.getByText(/Replay/));
    await waitFor(() => {
      expect(screen.getByText('ACCURACY')).toBeInTheDocument();
      expect(screen.getByText('All accurate')).toBeInTheDocument();
      expect(screen.getByText('ORDERING')).toBeInTheDocument();
      expect(screen.getByText('2 violations')).toBeInTheDocument();
    });
  });

  it('replay panel can be closed', async () => {
    global.fetch = mockFetch((url) => {
      if (url === '/api/sessions/1/replay') {
        return jsonResponse({ sessionId: 1, ticksReplayed: 50, results: [] });
      }
      return jsonResponse([makeSession()]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    fireEvent.click(screen.getByText(/Replay/));
    await waitFor(() => screen.getByText(/Replay — Session #1/));

    fireEvent.click(screen.getByText('✕'));
    expect(screen.queryByText(/Replay — Session #1/)).not.toBeInTheDocument();
  });

  // ── Export ────────────────────────────────────────────

  it('triggers JSON export download', async () => {
    const createObjectURL = vi.fn(() => 'blob:test');
    const revokeObjectURL = vi.fn();
    global.URL.createObjectURL = createObjectURL;
    global.URL.revokeObjectURL = revokeObjectURL;

    let exportUrl = null;
    global.fetch = mockFetch((url) => {
      if (url.includes('/export')) {
        exportUrl = url;
        return jsonResponse([]);
      }
      return jsonResponse([makeSession()]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    fireEvent.click(screen.getByText(/JSON/));
    await waitFor(() => {
      expect(exportUrl).toBe('/api/sessions/1/export?format=json');
    });
  });

  it('triggers CSV export download', async () => {
    const createObjectURL = vi.fn(() => 'blob:test');
    const revokeObjectURL = vi.fn();
    global.URL.createObjectURL = createObjectURL;
    global.URL.revokeObjectURL = revokeObjectURL;

    let exportUrl = null;
    global.fetch = mockFetch((url) => {
      if (url.includes('/export')) {
        exportUrl = url;
        return jsonResponse([]);
      }
      return jsonResponse([makeSession()]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    fireEvent.click(screen.getByText(/CSV/));
    await waitFor(() => {
      expect(exportUrl).toBe('/api/sessions/1/export?format=csv');
    });
  });

  // ── Error handling ────────────────────────────────────

  it('shows error on fetch failure', async () => {
    global.fetch = mockFetch(() => errorResponse(500, 'Server error'));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText(/Server error|HTTP 500/)).toBeInTheDocument();
    });
  });

  it('shows error on start recording failure', async () => {
    global.fetch = mockFetch((url, opts) => {
      if (url === '/api/sessions/start') {
        return errorResponse(409, 'Already recording');
      }
      return jsonResponse([]);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText(/Start Recording/));

    fireEvent.click(screen.getByText(/Start Recording/));
    fireEvent.change(screen.getByPlaceholderText('btc-morning-session'), { target: { value: 'test' } });
    fireEvent.change(screen.getByPlaceholderText('feed-1'), { target: { value: 'feed' } });

    fireEvent.click(screen.getByText(/Start$/));
    await waitFor(() => {
      expect(screen.getByText(/Already recording/)).toBeInTheDocument();
    });
  });

  // ── Disabled states ───────────────────────────────────

  it('disables replay for non-COMPLETED sessions', async () => {
    global.fetch = mockFetch(() => jsonResponse([makeSession({ status: 'FAILED' })]));
    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));

    const replayBtn = screen.getByTitle('Replay through validators');
    expect(replayBtn).toBeDisabled();
  });

  it('recording sessions not shown in saved list', async () => {
    const sessions = [
      makeSession({ id: 1, name: 'active', status: 'RECORDING', endedAt: null }),
      makeSession({ id: 2, name: 'saved-one', status: 'COMPLETED' }),
    ];
    global.fetch = mockFetch(() => jsonResponse(sessions));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText('Saved Sessions (1)')).toBeInTheDocument();
      expect(screen.getByText('saved-one')).toBeInTheDocument();
    });
  });

  // ── Compare Mode ──────────────────────────────────────

  it('shows compare section when two or more completed sessions', async () => {
    const sessions = [
      makeSession({ id: 1, name: 'session-a' }),
      makeSession({ id: 2, name: 'session-b' }),
    ];
    global.fetch = mockFetch(() => jsonResponse(sessions));
    render(<SessionManager />);
    await waitFor(() => {
      expect(screen.getByText(/Compare Sessions/)).toBeInTheDocument();
    });
  });

  it('hides compare section when less than two sessions', async () => {
    global.fetch = mockFetch(() => jsonResponse([makeSession()]));
    render(<SessionManager />);
    await waitFor(() => screen.getByText('test-session'));
    expect(screen.queryByText(/Compare Sessions/)).not.toBeInTheDocument();
  });

  it('compare button disabled when same session selected', async () => {
    const sessions = [
      makeSession({ id: 1, name: 'session-a' }),
      makeSession({ id: 2, name: 'session-b' }),
    ];
    global.fetch = mockFetch(() => jsonResponse(sessions));
    render(<SessionManager />);
    await waitFor(() => screen.getByText(/Compare Sessions/));

    const selects = screen.getAllByRole('combobox');
    fireEvent.change(selects[0], { target: { value: '1' } });
    fireEvent.change(selects[1], { target: { value: '1' } });

    expect(screen.getByText('🔍 Compare')).toBeDisabled();
  });

  it('calls compare endpoint and shows results', async () => {
    const sessions = [
      makeSession({ id: 1, name: 'session-a' }),
      makeSession({ id: 2, name: 'session-b' }),
    ];
    const compareResult = {
      sessionA: { id: 1, name: 'session-a', tickCount: 100 },
      sessionB: { id: 2, name: 'session-b', tickCount: 200 },
      priceDifferences: [{ symbol: 'BTCUSDT', avgPriceA: '45000', avgPriceB: '45500', diffPercent: 1.11 }],
      volumeDifferences: [],
      sequenceGaps: { totalGapsA: 0, totalGapsB: 2, newGapsInB: 2 },
      latencyPatterns: {
        sessionA: { count: 100, p50: 50, p95: 120, max: 200 },
        sessionB: { count: 200, p50: 60, p95: 150, max: 300 },
      },
      missingSymbols: { onlyInA: [], onlyInB: [] },
    };

    global.fetch = mockFetch((url, opts) => {
      if (url === '/api/compare' && opts?.method === 'POST') {
        return jsonResponse(compareResult);
      }
      return jsonResponse(sessions);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText(/Compare Sessions/));

    const selects = screen.getAllByRole('combobox');
    fireEvent.change(selects[0], { target: { value: '1' } });
    fireEvent.change(selects[1], { target: { value: '2' } });
    fireEvent.click(screen.getByText('🔍 Compare'));

    await waitFor(() => {
      expect(screen.getByText('BTCUSDT')).toBeInTheDocument();
      expect(screen.getByText(/\+1.11%/)).toBeInTheDocument();
    });
  });

  it('shows compare error message', async () => {
    const sessions = [
      makeSession({ id: 1, name: 'session-a' }),
      makeSession({ id: 2, name: 'session-b' }),
    ];

    global.fetch = mockFetch((url, opts) => {
      if (url === '/api/compare' && opts?.method === 'POST') {
        return { ok: false, status: 400, json: async () => ({ error: 'Session A not found' }), text: async () => 'fail' };
      }
      return jsonResponse(sessions);
    });

    render(<SessionManager />);
    await waitFor(() => screen.getByText(/Compare Sessions/));

    const selects = screen.getAllByRole('combobox');
    fireEvent.change(selects[0], { target: { value: '1' } });
    fireEvent.change(selects[1], { target: { value: '2' } });
    fireEvent.click(screen.getByText('🔍 Compare'));

    await waitFor(() => {
      expect(screen.getByText(/Session A not found/)).toBeInTheDocument();
    });
  });
});
