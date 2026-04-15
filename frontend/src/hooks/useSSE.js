import { useState, useEffect, useRef, useCallback } from 'react';

/**
 * useSSE — Custom React hook for Server-Sent Events.
 *
 * Subscribes to an SSE endpoint and returns live-updating data.
 *
 * Features:
 *   - Auto-connect on mount, auto-cleanup on unmount
 *   - Auto-reconnect with exponential backoff (1s → 2s → 4s → … → 30s max)
 *   - JSON parsing of event data
 *   - Bounded buffer (keeps last `maxItems` events by default)
 *   - Connection status tracking
 *
 * @param {string} url       — SSE endpoint path (e.g. '/api/stream/ticks')
 * @param {string} eventName — SSE event name to listen for (e.g. 'tick')
 * @param {object} options   — { maxItems: 500, enabled: true }
 * @returns {{ data: any[], latest: any, error: Error|null, connected: boolean, clear: Function }}
 */
export default function useSSE(url, eventName, options = {}) {
  const { maxItems = 500, enabled = true } = options;

  const [data, setData] = useState([]);
  const [latest, setLatest] = useState(null);
  const [error, setError] = useState(null);
  const [connected, setConnected] = useState(false);

  // Refs for values that shouldn't trigger re-renders
  const eventSourceRef = useRef(null);
  const retryCountRef = useRef(0);
  const retryTimerRef = useRef(null);
  const mountedRef = useRef(true);

  const clear = useCallback(() => {
    setData([]);
    setLatest(null);
  }, []);

  useEffect(() => {
    mountedRef.current = true;

    // Clear stale data when URL/deps change (prevents race with late-arriving events)
    setData([]);
    setLatest(null);

    if (!enabled || !url || !eventName) {
      return;
    }

    function connect() {
      // Close any existing connection
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }

      const source = new EventSource(url);
      eventSourceRef.current = source;

      source.addEventListener('open', () => {
        if (!mountedRef.current) return;
        setConnected(true);
        setError(null);
        retryCountRef.current = 0; // Reset backoff on successful connect
      });

      source.addEventListener(eventName, (event) => {
        if (!mountedRef.current) return;
        try {
          const parsed = JSON.parse(event.data);
          setLatest(parsed);
          setData((prev) => {
            const next = [...prev, parsed];
            // Bounded buffer: keep only the last maxItems
            return next.length > maxItems ? next.slice(next.length - maxItems) : next;
          });
        } catch (e) {
          // Non-JSON data — store raw
          setLatest(event.data);
          setData((prev) => {
            const next = [...prev, event.data];
            return next.length > maxItems ? next.slice(next.length - maxItems) : next;
          });
        }
      });

      source.addEventListener('error', () => {
        if (!mountedRef.current) return;
        setConnected(false);
        source.close();
        eventSourceRef.current = null;

        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s max
        const delay = Math.min(
          Math.pow(2, retryCountRef.current) * 1000,
          30000
        );
        retryCountRef.current += 1;

        setError(new Error(`SSE disconnected. Reconnecting in ${delay / 1000}s…`));

        retryTimerRef.current = setTimeout(() => {
          if (mountedRef.current) {
            connect();
          }
        }, delay);
      });
    }

    connect();

    // Cleanup on unmount or dependency change.
    // Order matters: cancel the reconnect timer BEFORE closing the EventSource.
    // If the timer fires after mountedRef is false but before clearTimeout, the
    // guard inside the callback prevents connect() from running. Cancelling first
    // ensures no abandoned EventSource is ever created after cleanup.
    return () => {
      mountedRef.current = false;
      if (retryTimerRef.current) {
        clearTimeout(retryTimerRef.current);
        retryTimerRef.current = null;
      }
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
        eventSourceRef.current = null;
      }
    };
  }, [url, eventName, enabled, maxItems]);

  return { data, latest, error, connected, clear };
}
