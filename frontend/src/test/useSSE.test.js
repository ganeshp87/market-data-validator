import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import useSSE from '../hooks/useSSE';

// --- Mock EventSource ---

class MockEventSource {
  static instances = [];

  constructor(url) {
    this.url = url;
    this.readyState = 0; // CONNECTING
    this._listeners = {};
    MockEventSource.instances.push(this);
  }

  addEventListener(type, handler) {
    if (!this._listeners[type]) this._listeners[type] = [];
    this._listeners[type].push(handler);
  }

  removeEventListener(type, handler) {
    if (!this._listeners[type]) return;
    this._listeners[type] = this._listeners[type].filter((h) => h !== handler);
  }

  close() {
    this.readyState = 2; // CLOSED
  }

  // Test helpers
  _emit(type, data) {
    const handlers = this._listeners[type] || [];
    handlers.forEach((h) => h(data));
  }

  _open() {
    this.readyState = 1; // OPEN
    this._emit('open', {});
  }

  _sendEvent(eventName, jsonData) {
    this._emit(eventName, { data: JSON.stringify(jsonData) });
  }

  _error() {
    this._emit('error', {});
  }
}

// Install mock
beforeEach(() => {
  MockEventSource.instances = [];
  vi.stubGlobal('EventSource', MockEventSource);
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function latestSource() {
  return MockEventSource.instances[MockEventSource.instances.length - 1];
}

// --- Tests ---

describe('useSSE', () => {
  it('returns initial state before connection', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));

    expect(result.current.data).toEqual([]);
    expect(result.current.latest).toBeNull();
    expect(result.current.error).toBeNull();
    expect(result.current.connected).toBe(false);
  });

  it('creates EventSource with the given URL', () => {
    renderHook(() => useSSE('/api/stream/ticks', 'tick'));

    expect(MockEventSource.instances).toHaveLength(1);
    expect(latestSource().url).toBe('/api/stream/ticks');
  });

  it('sets connected=true on open', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));

    act(() => latestSource()._open());

    expect(result.current.connected).toBe(true);
    expect(result.current.error).toBeNull();
  });

  it('parses JSON event data into latest and data array', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    act(() => latestSource()._open());

    const tick = { symbol: 'BTCUSDT', price: '45000.00', latency: 12 };
    act(() => latestSource()._sendEvent('tick', tick));

    expect(result.current.latest).toEqual(tick);
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data[0]).toEqual(tick);
  });

  it('accumulates multiple events in order', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    act(() => latestSource()._open());

    const t1 = { symbol: 'BTCUSDT', price: '45000' };
    const t2 = { symbol: 'ETHUSDT', price: '3000' };
    const t3 = { symbol: 'BTCUSDT', price: '45001' };

    act(() => {
      latestSource()._sendEvent('tick', t1);
      latestSource()._sendEvent('tick', t2);
      latestSource()._sendEvent('tick', t3);
    });

    expect(result.current.data).toHaveLength(3);
    expect(result.current.data[0]).toEqual(t1);
    expect(result.current.data[2]).toEqual(t3);
    expect(result.current.latest).toEqual(t3);
  });

  it('respects maxItems bounded buffer', () => {
    const { result } = renderHook(() =>
      useSSE('/api/stream/ticks', 'tick', { maxItems: 3 })
    );
    act(() => latestSource()._open());

    for (let i = 0; i < 5; i++) {
      act(() => latestSource()._sendEvent('tick', { seq: i }));
    }

    expect(result.current.data).toHaveLength(3);
    // Should keep the last 3: seq 2, 3, 4
    expect(result.current.data[0].seq).toBe(2);
    expect(result.current.data[2].seq).toBe(4);
  });

  it('sets error and disconnected on EventSource error', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    act(() => latestSource()._open());
    expect(result.current.connected).toBe(true);

    act(() => latestSource()._error());

    expect(result.current.connected).toBe(false);
    expect(result.current.error).toBeInstanceOf(Error);
    expect(result.current.error.message).toContain('Reconnecting');
  });

  it('reconnects with exponential backoff after error', () => {
    renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    const firstSource = latestSource();

    // First error → reconnect after 1s
    act(() => firstSource._error());
    expect(MockEventSource.instances).toHaveLength(1); // Not yet reconnected

    act(() => vi.advanceTimersByTime(1000));
    expect(MockEventSource.instances).toHaveLength(2); // Reconnected

    // Second error → reconnect after 2s
    act(() => latestSource()._error());
    act(() => vi.advanceTimersByTime(1000));
    expect(MockEventSource.instances).toHaveLength(2); // Not yet
    act(() => vi.advanceTimersByTime(1000));
    expect(MockEventSource.instances).toHaveLength(3); // Now reconnected
  });

  it('resets retry count on successful reconnect', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));

    // Disconnect
    act(() => latestSource()._error());
    act(() => vi.advanceTimersByTime(1000)); // Reconnect after 1s (attempt 0)

    // Successful reconnect
    act(() => latestSource()._open());
    expect(result.current.connected).toBe(true);

    // Next error should restart backoff at 1s (not 2s)
    act(() => latestSource()._error());
    act(() => vi.advanceTimersByTime(1000));
    expect(MockEventSource.instances).toHaveLength(3);
  });

  it('caps backoff at 30 seconds', () => {
    renderHook(() => useSSE('/api/stream/ticks', 'tick'));

    // Simulate many errors to push backoff past 30s
    for (let i = 0; i < 10; i++) {
      act(() => latestSource()._error());
      act(() => vi.advanceTimersByTime(30000));
    }

    // All reconnects should have happened (backoff capped at 30s)
    expect(MockEventSource.instances.length).toBeGreaterThan(5);
  });

  it('closes EventSource on unmount', () => {
    const { unmount } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    const source = latestSource();
    act(() => source._open());

    unmount();

    expect(source.readyState).toBe(2); // CLOSED
  });

  it('clears retry timer on unmount', () => {
    const { unmount } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));

    // Trigger error (starts reconnect timer)
    act(() => latestSource()._error());
    const instancesBefore = MockEventSource.instances.length;

    unmount();

    // Advance time — should NOT create a new EventSource
    act(() => vi.advanceTimersByTime(30000));
    expect(MockEventSource.instances).toHaveLength(instancesBefore);
  });

  it('does not connect when enabled=false', () => {
    renderHook(() =>
      useSSE('/api/stream/ticks', 'tick', { enabled: false })
    );

    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('does not connect when url is empty', () => {
    renderHook(() => useSSE('', 'tick'));

    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('clear() resets data and latest', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    act(() => latestSource()._open());
    act(() => latestSource()._sendEvent('tick', { price: '100' }));

    expect(result.current.data).toHaveLength(1);

    act(() => result.current.clear());

    expect(result.current.data).toEqual([]);
    expect(result.current.latest).toBeNull();
  });

  it('ignores events for wrong event name', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    act(() => latestSource()._open());

    // Send a "validation" event — should be ignored since we listen for "tick"
    act(() => latestSource()._sendEvent('validation', { status: 'PASS' }));

    expect(result.current.data).toHaveLength(0);
    expect(result.current.latest).toBeNull();
  });

  it('handles non-JSON data gracefully', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    act(() => latestSource()._open());

    // Send raw string (not valid JSON)
    act(() => {
      latestSource()._emit('tick', { data: 'not-json' });
    });

    expect(result.current.data).toHaveLength(1);
    expect(result.current.data[0]).toBe('not-json');
  });

  it('reconnects to new URL when url prop changes', () => {
    const { rerender } = renderHook(
      ({ url }) => useSSE(url, 'tick'),
      { initialProps: { url: '/api/stream/ticks' } }
    );

    expect(MockEventSource.instances).toHaveLength(1);
    expect(latestSource().url).toBe('/api/stream/ticks');

    rerender({ url: '/api/stream/ticks?symbol=BTCUSDT' });

    // Old one closed, new one opened
    expect(MockEventSource.instances[0].readyState).toBe(2); // CLOSED
    expect(MockEventSource.instances).toHaveLength(2);
    expect(latestSource().url).toBe('/api/stream/ticks?symbol=BTCUSDT');
  });

  // --- Edge cases ---

  it('handles empty event data without crashing', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    act(() => latestSource()._open());

    act(() => {
      latestSource()._emit('tick', { data: '' });
    });

    // Should not crash; data length should be 1 with empty string
    expect(result.current.data).toHaveLength(1);
  });

  it('handles broken JSON event data', () => {
    const { result } = renderHook(() => useSSE('/api/stream/ticks', 'tick'));
    act(() => latestSource()._open());

    act(() => {
      latestSource()._emit('tick', { data: '{bad json' });
    });

    // Should handle gracefully without crashing
    expect(result.current.data).toHaveLength(1);
  });

  it('handles rapid events in sequence', () => {
    const { result } = renderHook(() =>
      useSSE('/api/stream/ticks', 'tick', { maxItems: 10 })
    );
    act(() => latestSource()._open());

    // Send 20 events rapidly
    act(() => {
      for (let i = 0; i < 20; i++) {
        latestSource()._emit('tick', {
          data: JSON.stringify({ seq: i, symbol: 'BTCUSDT' }),
        });
      }
    });

    // Should be bounded to maxItems
    expect(result.current.data.length).toBeLessThanOrEqual(10);
    expect(result.current.latest).toEqual({ seq: 19, symbol: 'BTCUSDT' });
  });
});
