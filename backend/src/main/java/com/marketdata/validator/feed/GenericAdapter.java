package com.marketdata.validator.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketdata.validator.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Configurable adapter for custom/generic WebSocket feeds.
 *
 * Instead of hardcoding field names like BinanceAdapter or FinnhubAdapter,
 * GenericAdapter uses a field-mapping configuration to extract tick data
 * from arbitrary JSON message formats.
 *
 * Default field mappings (matching a common format):
 *   symbol → "symbol", price → "price", volume → "volume",
 *   timestamp → "timestamp", sequence → "sequence"
 *
 * Override via constructor to support any JSON feed format.
 */
public class GenericAdapter implements FeedAdapter {

    private static final Logger log = LoggerFactory.getLogger(GenericAdapter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Configurable JSON field names
    private final String symbolField;
    private final String priceField;
    private final String volumeField;
    private final String timestampField;
    private final String sequenceField;
    private final String typeField;
    private final String tradeTypeValue;

    /**
     * Default constructor with common field names.
     */
    public GenericAdapter() {
        this(Map.of());
    }

    /**
     * Constructor with custom field mappings.
     * Supported keys: symbol, price, volume, timestamp, sequence, typeField, tradeTypeValue
     */
    public GenericAdapter(Map<String, String> fieldMappings) {
        this.symbolField = fieldMappings.getOrDefault("symbol", "symbol");
        this.priceField = fieldMappings.getOrDefault("price", "price");
        this.volumeField = fieldMappings.getOrDefault("volume", "volume");
        this.timestampField = fieldMappings.getOrDefault("timestamp", "timestamp");
        this.sequenceField = fieldMappings.getOrDefault("sequence", "sequence");
        this.typeField = fieldMappings.getOrDefault("typeField", "type");
        this.tradeTypeValue = fieldMappings.getOrDefault("tradeTypeValue", "trade");
    }

    @Override
    public String getSubscribeMessage(List<String> symbols) {
        String symbolList = symbols.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return String.format("{\"action\":\"subscribe\",\"symbols\":%s}", symbolList);
    }

    @Override
    public String getUnsubscribeMessage(List<String> symbols) {
        String symbolList = symbols.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return String.format("{\"action\":\"unsubscribe\",\"symbols\":%s}", symbolList);
    }

    @Override
    public Tick parseTick(String rawMessage) {
        try {
            JsonNode node = objectMapper.readTree(rawMessage);

            // If typeField is present, only parse matching trade messages
            if (node.has(typeField) && !tradeTypeValue.equals(node.get(typeField).asText())) {
                return null;
            }

            // Symbol is required
            if (!node.has(symbolField)) {
                return null;
            }

            Tick tick = new Tick();
            tick.setSymbol(node.get(symbolField).asText());

            if (node.has(priceField)) {
                tick.setPrice(new BigDecimal(node.get(priceField).asText()));
            }
            if (node.has(volumeField)) {
                tick.setVolume(new BigDecimal(node.get(volumeField).asText()));
            }
            if (node.has(timestampField)) {
                long ts = node.get(timestampField).asLong();
                tick.setExchangeTimestamp(Instant.ofEpochMilli(ts));
            } else {
                tick.setExchangeTimestamp(Instant.now());
            }
            if (node.has(sequenceField)) {
                tick.setSequenceNum(node.get(sequenceField).asLong());
            }

            tick.setReceivedTimestamp(Instant.now());
            tick.setCorrelationId(UUID.randomUUID().toString());

            return tick;
        } catch (Exception e) {
            log.warn("Failed to parse generic message: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isHeartbeat(String rawMessage) {
        try {
            JsonNode node = objectMapper.readTree(rawMessage);
            if (node.has(typeField)) {
                String type = node.get(typeField).asText();
                return "ping".equals(type) || "heartbeat".equals(type) || "pong".equals(type);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
