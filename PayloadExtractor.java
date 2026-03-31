package com.signalcend.hivemq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.extension.sdk.api.packets.publish.PublishPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * PayloadExtractor pulls device state fields from an MQTT PUBLISH packet.
 *
 * Field resolution order for each value:
 *   1. JSON body field matching the configured field name
 *   2. MQTT topic segment (device ID can often be inferred from topic)
 *   3. Sensible default or null
 *
 * The extractor is intentionally permissive — if a field is missing
 * SignalCend will still arbitrate with the signals it has.
 * Only device_id and status are required for a valid resolve call.
 */
public class PayloadExtractor {

    private static final Logger log = LoggerFactory.getLogger(PayloadExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SignalCendConfig config;

    public PayloadExtractor(final SignalCendConfig config) {
        this.config = config;
    }

    public ExtractedPayload extract(final PublishPacket packet) {
        final String topic   = packet.getTopic();
        final String rawBody = readPayload(packet);
        JsonNode json        = null;

        if (rawBody != null && !rawBody.isBlank()) {
            try {
                json = MAPPER.readTree(rawBody);
            } catch (final Exception e) {
                log.debug("SignalCend: payload on topic {} is not JSON — " +
                          "inferring fields from topic", topic);
            }
        }

        final String deviceId  = extractDeviceId(json, topic);
        final String status    = extractStatus(json, topic);
        final String timestamp = extractTimestamp(json);
        final Double rssi      = extractSignalStrength(json);
        final Long   sequence  = extractSequence(json);

        return new ExtractedPayload(deviceId, status, timestamp, rssi, sequence, rawBody);
    }

    private String extractDeviceId(final JsonNode json, final String topic) {
        // Try configured JSON field first
        if (json != null) {
            final JsonNode node = json.get(config.getDeviceIdField());
            if (node != null && !node.isNull()) return node.asText();

            // Common aliases
            for (final String alias : new String[]{"id", "device", "client_id",
                    "clientId", "deviceId", "sensor_id", "node_id"}) {
                final JsonNode alt = json.get(alias);
                if (alt != null && !alt.isNull()) return alt.asText();
            }
        }

        // Infer from topic — common patterns:
        // devices/{device_id}/state
        // sensors/{device_id}/telemetry
        // fleet/{device_id}/status
        final String[] segments = topic.split("/");
        if (segments.length >= 2) {
            // Return the second segment as device ID (most common convention)
            return segments[1];
        }
        if (segments.length == 1) {
            return segments[0];
        }

        return "unknown_device";
    }

    private String extractStatus(final JsonNode json, final String topic) {
        if (json != null) {
            final JsonNode node = json.get(config.getStatusField());
            if (node != null && !node.isNull()) return node.asText();

            // Common aliases
            for (final String alias : new String[]{"state", "value",
                    "device_state", "connection_status", "online"}) {
                final JsonNode alt = json.get(alias);
                if (alt != null && !alt.isNull()) {
                    final String val = alt.asText();
                    // Handle boolean online field
                    if ("true".equalsIgnoreCase(val))  return "online";
                    if ("false".equalsIgnoreCase(val)) return "offline";
                    return val;
                }
            }
        }

        // Infer from topic last segment
        // e.g. devices/sensor_007/offline  →  "offline"
        final String[] segments = topic.split("/");
        if (segments.length > 0) {
            final String last = segments[segments.length - 1].toLowerCase();
            if (last.equals("online") || last.equals("offline") ||
                last.equals("error") || last.equals("warning") ||
                last.equals("idle")  || last.equals("updating") ||
                last.equals("initializing")) {
                return last;
            }
        }

        return "online"; // safe default — arbitration will evaluate
    }

    private String extractTimestamp(final JsonNode json) {
        if (json != null) {
            final JsonNode node = json.get(config.getTimestampField());
            if (node != null && !node.isNull()) return node.asText();

            // Common aliases
            for (final String alias : new String[]{"ts", "time", "event_time",
                    "created_at", "occurred_at", "event_timestamp"}) {
                final JsonNode alt = json.get(alias);
                if (alt != null && !alt.isNull()) return alt.asText();
            }
        }
        // Default to current server time as ISO 8601
        return Instant.now().toString();
    }

    private Double extractSignalStrength(final JsonNode json) {
        if (json == null) return null;
        final JsonNode node = json.get(config.getSignalStrengthField());
        if (node != null && node.isNumber()) return node.asDouble();

        // Common aliases
        for (final String alias : new String[]{"rssi", "snr", "signal",
                "signal_level", "rf_level"}) {
            final JsonNode alt = json.get(alias);
            if (alt != null && alt.isNumber()) return alt.asDouble();
        }
        return null;
    }

    private Long extractSequence(final JsonNode json) {
        if (json == null) return null;
        final JsonNode node = json.get(config.getSequenceField());
        if (node != null && node.isIntegralNumber()) return node.asLong();

        // Common aliases
        for (final String alias : new String[]{"seq", "sequence_number",
                "msg_id", "counter", "seq_num"}) {
            final JsonNode alt = json.get(alias);
            if (alt != null && alt.isIntegralNumber()) return alt.asLong();
        }
        return null;
    }

    private String readPayload(final PublishPacket packet) {
        final Optional<ByteBuffer> payloadOpt = packet.getPayload();
        if (payloadOpt.isEmpty()) return null;
        final ByteBuffer buf = payloadOpt.get();
        final byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Immutable container for fields extracted from an MQTT payload.
     */
    public static class ExtractedPayload {
        private final String deviceId;
        private final String status;
        private final String timestamp;
        private final Double signalStrength;
        private final Long   sequence;
        private final String rawBody;

        public ExtractedPayload(final String deviceId, final String status,
                                final String timestamp, final Double signalStrength,
                                final Long sequence, final String rawBody) {
            this.deviceId       = deviceId;
            this.status         = status;
            this.timestamp      = timestamp;
            this.signalStrength = signalStrength;
            this.sequence       = sequence;
            this.rawBody        = rawBody;
        }

        public String getDeviceId()       { return deviceId; }
        public String getStatus()         { return status; }
        public String getTimestamp()      { return timestamp; }
        public Double getSignalStrength() { return signalStrength; }
        public Long   getSequence()       { return sequence; }
        public String getRawBody()        { return rawBody; }
    }
}
