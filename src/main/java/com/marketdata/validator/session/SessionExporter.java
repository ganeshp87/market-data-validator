package com.marketdata.validator.session;

import com.marketdata.validator.model.Session;
import com.marketdata.validator.model.Tick;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports recorded sessions as JSON or CSV.
 *
 * Blueprint Section 5.5:
 *   - Export as JSON → valid JSON map with session metadata + ticks array
 *   - Export as CSV → correct headers and rows
 *   - Price precision preserved in export (BigDecimal.toString())
 */
@Component
public class SessionExporter {

    /**
     * Export session as a JSON-ready map with metadata and ticks.
     */
    public Map<String, Object> exportAsJson(Session session, List<Tick> ticks) {
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("sessionId", session.getId());
        export.put("name", session.getName());
        export.put("feedId", session.getFeedId());
        export.put("startedAt", session.getStartedAt());
        export.put("endedAt", session.getEndedAt());
        export.put("tickCount", session.getTickCount());
        export.put("ticks", ticks);
        return export;
    }

    /**
     * Export session ticks as a CSV string with headers.
     */
    public String exportAsCsv(List<Tick> ticks) {
        StringBuilder sb = new StringBuilder();
        sb.append("symbol,price,bid,ask,volume,sequenceNum,exchangeTimestamp,receivedTimestamp,feedId,correlationId\n");
        for (Tick tick : ticks) {
            sb.append(tick.getSymbol()).append(',');
            sb.append(tick.getPrice()).append(',');
            sb.append(tick.getBid() != null ? tick.getBid() : "").append(',');
            sb.append(tick.getAsk() != null ? tick.getAsk() : "").append(',');
            sb.append(tick.getVolume() != null ? tick.getVolume() : "").append(',');
            sb.append(tick.getSequenceNum()).append(',');
            sb.append(tick.getExchangeTimestamp()).append(',');
            sb.append(tick.getReceivedTimestamp()).append(',');
            sb.append(tick.getFeedId()).append(',');
            sb.append(tick.getCorrelationId() != null ? tick.getCorrelationId() : "").append('\n');
        }
        return sb.toString();
    }
}
