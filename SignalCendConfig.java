package com.signalcend.hivemq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * SignalCendConfig loads and validates all configuration from
 * signalcend.properties in the HiveMQ extensions/signalcend-hivemq-extension/ folder.
 *
 * Required properties:
 *   signalcend.api.key        — your SignalCend API key
 *   signalcend.api.secret     — your SignalCend API secret (HMAC signing)
 *
 * Optional properties:
 *   signalcend.api.base.url              — default: https://api.signalcend.com/v1
 *   signalcend.topic.filter              — comma-separated topic prefixes to arbitrate
 *                                          default: # (all topics)
 *   signalcend.status.field              — JSON field name for device status
 *                                          default: status
 *   signalcend.device.id.field           — JSON field name for device ID
 *                                          default: device_id
 *   signalcend.timestamp.field           — JSON field name for event timestamp
 *                                          default: timestamp
 *   signalcend.signal.strength.field     — JSON field name for RSSI
 *                                          default: signal_strength
 *   signalcend.sequence.field            — JSON field name for sequence number
 *                                          default: sequence
 *   signalcend.reconnect.window.seconds  — default reconnect window
 *                                          default: 30
 *   signalcend.http.timeout.ms           — HTTP call timeout in milliseconds
 *                                          default: 5000
 *   signalcend.act.on.confirm            — whether to pass through CONFIRM verdicts
 *                                          default: true
 *   signalcend.block.on.log.only         — whether to drop LOG_ONLY messages
 *                                          default: false
 *   signalcend.session.id.prefix         — prefix for session IDs
 *                                          default: hivemq
 */
public class SignalCendConfig {

    private static final Logger log = LoggerFactory.getLogger(SignalCendConfig.class);

    // Required
    private final String apiKey;
    private final String apiSecret;

    // Optional with defaults
    private final String baseUrl;
    private final List<String> topicFilters;
    private final String statusField;
    private final String deviceIdField;
    private final String timestampField;
    private final String signalStrengthField;
    private final String sequenceField;
    private final int reconnectWindowSeconds;
    private final int httpTimeoutMs;
    private final boolean actOnConfirm;
    private final boolean blockOnLogOnly;
    private final String sessionIdPrefix;

    public SignalCendConfig(final File extensionHomeFolder) {
        final Properties props = new Properties();
        final File configFile = new File(extensionHomeFolder, "signalcend.properties");

        if (configFile.exists()) {
            try (final FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
                log.info("SignalCend: loaded configuration from {}", configFile.getAbsolutePath());
            } catch (final IOException e) {
                log.error("SignalCend: failed to load configuration file — {}", e.getMessage());
                throw new RuntimeException("Failed to load signalcend.properties", e);
            }
        } else {
            log.warn("SignalCend: no signalcend.properties found at {} — " +
                    "using environment variables or defaults", configFile.getAbsolutePath());
        }

        // Required — check environment variables as fallback
        this.apiKey = resolve(props, "signalcend.api.key", "SIGNALCEND_API_KEY", null);
        this.apiSecret = resolve(props, "signalcend.api.secret", "SIGNALCEND_API_SECRET", null);

        if (this.apiKey == null || this.apiKey.isBlank()) {
            throw new IllegalStateException(
                    "SignalCend: signalcend.api.key is required. " +
                    "Set it in signalcend.properties or SIGNALCEND_API_KEY environment variable.");
        }
        if (this.apiSecret == null || this.apiSecret.isBlank()) {
            throw new IllegalStateException(
                    "SignalCend: signalcend.api.secret is required. " +
                    "Set it in signalcend.properties or SIGNALCEND_API_SECRET environment variable.");
        }

        // Optional
        this.baseUrl = resolve(props, "signalcend.api.base.url",
                "SIGNALCEND_API_BASE_URL", "https://api.signalcend.com/v1");

        final String topicFilterStr = resolve(props, "signalcend.topic.filter",
                "SIGNALCEND_TOPIC_FILTER", "#");
        this.topicFilters = topicFilterStr.equals("#")
                ? Collections.singletonList("#")
                : Arrays.asList(topicFilterStr.split(","));

        this.statusField        = resolve(props, "signalcend.status.field", null, "status");
        this.deviceIdField      = resolve(props, "signalcend.device.id.field", null, "device_id");
        this.timestampField     = resolve(props, "signalcend.timestamp.field", null, "timestamp");
        this.signalStrengthField= resolve(props, "signalcend.signal.strength.field", null, "signal_strength");
        this.sequenceField      = resolve(props, "signalcend.sequence.field", null, "sequence");

        this.reconnectWindowSeconds = Integer.parseInt(
                resolve(props, "signalcend.reconnect.window.seconds", null, "30"));
        this.httpTimeoutMs = Integer.parseInt(
                resolve(props, "signalcend.http.timeout.ms", null, "5000"));

        this.actOnConfirm  = Boolean.parseBoolean(
                resolve(props, "signalcend.act.on.confirm", null, "true"));
        this.blockOnLogOnly = Boolean.parseBoolean(
                resolve(props, "signalcend.block.on.log.only", null, "false"));

        this.sessionIdPrefix = resolve(props, "signalcend.session.id.prefix", null, "hivemq");

        log.info("SignalCend configuration loaded — baseUrl={} topicFilters={} " +
                 "reconnectWindow={}s httpTimeout={}ms actOnConfirm={} blockOnLogOnly={}",
                baseUrl, topicFilters, reconnectWindowSeconds,
                httpTimeoutMs, actOnConfirm, blockOnLogOnly);
    }

    private String resolve(final Properties props,
                           final String propKey,
                           final String envKey,
                           final String defaultValue) {
        String value = props.getProperty(propKey);
        if (value == null && envKey != null) {
            value = System.getenv(envKey);
        }
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }

    public String getApiKey()               { return apiKey; }
    public String getApiSecret()            { return apiSecret; }
    public String getBaseUrl()              { return baseUrl; }
    public List<String> getTopicFilters()   { return topicFilters; }
    public String getStatusField()          { return statusField; }
    public String getDeviceIdField()        { return deviceIdField; }
    public String getTimestampField()       { return timestampField; }
    public String getSignalStrengthField()  { return signalStrengthField; }
    public String getSequenceField()        { return sequenceField; }
    public int getReconnectWindowSeconds()  { return reconnectWindowSeconds; }
    public int getHttpTimeoutMs()           { return httpTimeoutMs; }
    public boolean isActOnConfirm()         { return actOnConfirm; }
    public boolean isBlockOnLogOnly()       { return blockOnLogOnly; }
    public String getSessionIdPrefix()      { return sessionIdPrefix; }
}
