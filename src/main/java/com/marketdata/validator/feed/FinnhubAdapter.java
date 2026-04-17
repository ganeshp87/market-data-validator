package com.marketdata.validator.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.validator.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Parses Finnhub WebSocket trade messages into Tick objects.
 *
 * Finnhub trade stream format:
 * {
 *   "type": "trade",
 *   "data": [
 *     { "s": "AAPL", "p": 178.50, "v": 100, "t": 1711000000000, "c": ["1"] },
 *     { "s": "AAPL", "p": 178.52, "v": 50, "t": 1711000000001, "c": ["1"] }
 *   ]
 * }
 *
 * Subscribe: {"type":"subscribe","symbol":"AAPL"}
 * Unsubscribe: {"type":"unsubscribe","symbol":"AAPL"}
 *
 * URL: wss://ws.finnhub.io?token=YOUR_API_KEY
 */
public class FinnhubAdapter implements FeedAdapter {

    private static final Logger log = LoggerFactory.getLogger(FinnhubAdapter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getSubscribeMessage(List<String> symbols) {
        // Finnhub requires one subscribe message per symbol
        // We send them as a JSON array of commands for batch sending
        return symbols.stream()
                .map(s -> String.format("{\"type\":\"subscribe\",\"symbol\":\"%s\"}", s))
                .collect(Collectors.joining("\n"));
    }

    @Override
    public String getUnsubscribeMessage(List<String> symbols) {
        return symbols.stream()
                .map(s -> String.format("{\"type\":\"unsubscribe\",\"symbol\":\"%s\"}", s))
                .collect(Collectors.joining("\n"));
    }

    @Override
    public Tick parseTick(String rawMessage) {
        try {
            JsonNode node = objectMapper.readTree(rawMessage);

            // Only parse trade messages
            if (!node.has("type") || !"trade".equals(node.get("type").asText())) {
                return null;
            }

            JsonNode data = node.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                return null;
            }

            // Parse the first trade in the array (most common case).
            // For multi-trade arrays, parseTicks() returns all of them.
            return parseTradeNode(data.get(0));
        } catch (Exception e) {
            log.warn("Failed to parse Finnhub message: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse all trades from a Finnhub message.
     * Finnhub batches multiple trades into a single message.
     */
    public List<Tick> parseTicks(String rawMessage) {
        List<Tick> ticks = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(rawMessage);
            if (!node.has("type") || !"trade".equals(node.get("type").asText())) {
                return ticks;
            }

            JsonNode data = node.get("data");
            if (data == null || !data.isArray()) {
                return ticks;
            }

            for (JsonNode tradeNode : data) {
                Tick tick = parseTradeNode(tradeNode);
                if (tick != null) {
                    ticks.add(tick);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Finnhub message batch: {}", e.getMessage());
        }
        return ticks;
    }

    @Override
    public boolean isHeartbeat(String rawMessage) {
        try {
            JsonNode node = objectMapper.readTree(rawMessage);
            return node.has("type") && "ping".equals(node.get("type").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private Tick parseTradeNode(JsonNode tradeNode) {
        try {
            JsonNode sNode = tradeNode.get("s");
            JsonNode pNode = tradeNode.get("p");
            JsonNode vNode = tradeNode.get("v");
            JsonNode tNode = tradeNode.get("t");
            if (sNode == null || pNode == null || vNode == null || tNode == null) {
                log.warn("Finnhub trade node missing required fields");
                return null;
            }

            Tick tick = new Tick();
            tick.setSymbol(sNode.asText());
            tick.setPrice(new BigDecimal(pNode.asText()));
            tick.setVolume(new BigDecimal(vNode.asText()));
            tick.setExchangeTimestamp(Instant.ofEpochMilli(tNode.asLong()));
            tick.setReceivedTimestamp(Instant.now());
            tick.setCorrelationId(UUID.randomUUID().toString());

            // Finnhub doesn't provide a sequence number — use trade timestamp as a proxy
            tick.setSequenceNum(tNode.asLong());

            return tick;
        } catch (Exception e) {
            log.warn("Failed to parse Finnhub trade node: {}", e.getMessage());
            return null;
        }
    }
}
