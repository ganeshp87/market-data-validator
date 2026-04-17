package com.marketdata.validator.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.validator.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Parses Binance WebSocket trade messages into Tick objects.
 *
 * Binance trade stream format:
 * {
 *   "e": "trade",        // event type
 *   "s": "BTCUSDT",      // symbol
 *   "p": "45123.45",     // price (string — safe for BigDecimal)
 *   "q": "0.123",        // quantity (string)
 *   "T": 1711000000000,  // trade time (epoch ms)
 *   "t": 123456789       // trade ID (used as sequence number)
 * }
 *
 * URL pattern: wss://stream.binance.com:9443/ws/{symbol}@trade
 * For multiple symbols: wss://stream.binance.com:9443/stream?streams=btcusdt@trade/ethusdt@trade
 */
public class BinanceAdapter implements FeedAdapter {

    private static final Logger log = LoggerFactory.getLogger(BinanceAdapter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getSubscribeMessage(List<String> symbols) {
        List<String> streams = symbols.stream()
                .map(s -> s.toLowerCase() + "@trade")
                .toList();

        return String.format(
                "{\"method\":\"SUBSCRIBE\",\"params\":%s,\"id\":1}",
                toJsonArray(streams));
    }

    @Override
    public String getUnsubscribeMessage(List<String> symbols) {
        List<String> streams = symbols.stream()
                .map(s -> s.toLowerCase() + "@trade")
                .toList();

        return String.format(
                "{\"method\":\"UNSUBSCRIBE\",\"params\":%s,\"id\":2}",
                toJsonArray(streams));
    }

    @Override
    public Tick parseTick(String rawMessage) {
        try {
            JsonNode node = objectMapper.readTree(rawMessage);

            // Binance sends various message types; only parse trades
            if (!node.has("e") || !"trade".equals(node.get("e").asText())) {
                return null;
            }

            JsonNode sNode = node.get("s");
            JsonNode pNode = node.get("p");
            JsonNode qNode = node.get("q");
            JsonNode tNode = node.get("T");
            JsonNode seqNode = node.get("t");
            if (sNode == null || pNode == null || qNode == null || tNode == null || seqNode == null) {
                log.warn("Binance trade message missing required fields");
                return null;
            }

            Tick tick = new Tick();
            tick.setSymbol(sNode.asText());
            tick.setPrice(new BigDecimal(pNode.asText()));
            tick.setVolume(new BigDecimal(qNode.asText()));
            tick.setExchangeTimestamp(Instant.ofEpochMilli(tNode.asLong()));
            tick.setSequenceNum(seqNode.asLong());
            tick.setReceivedTimestamp(Instant.now());
            tick.setCorrelationId(UUID.randomUUID().toString());

            return tick;
        } catch (Exception e) {
            log.warn("Failed to parse Binance message: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isHeartbeat(String rawMessage) {
        // Binance sends ping frames at the WebSocket protocol level,
        // not as JSON messages. However, subscription confirmations
        // come as JSON with a "result" field.
        try {
            JsonNode node = objectMapper.readTree(rawMessage);
            return node.has("result") && !node.has("e");
        } catch (Exception e) {
            return false;
        }
    }

    private String toJsonArray(List<String> items) {
        return items.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
