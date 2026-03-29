package com.marketdata.validator.feed;

import com.marketdata.validator.model.Tick;

import java.util.List;

/**
 * Adapter interface for parsing exchange-specific WebSocket message formats.
 * Each exchange (Binance, Finnhub, etc.) sends data in a different JSON format.
 * Adapters translate raw messages into our unified Tick model.
 *
 * Strategy pattern: FeedConnection holds a FeedAdapter and delegates parsing to it.
 */
public interface FeedAdapter {

    /**
     * Build the subscribe message to send over WebSocket.
     * Each exchange has a different subscription protocol.
     */
    String getSubscribeMessage(List<String> symbols);

    /**
     * Build the unsubscribe message to send over WebSocket.
     */
    String getUnsubscribeMessage(List<String> symbols);

    /**
     * Parse a raw WebSocket message into a Tick.
     * Returns null if the message is not a trade (e.g., subscription confirmation).
     */
    Tick parseTick(String rawMessage);

    /**
     * Check if the message is a heartbeat/ping that should be ignored by validators.
     */
    boolean isHeartbeat(String rawMessage);
}
