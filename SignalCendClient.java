package com.signalcend.hivemq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * SignalCendClient handles the two-step sign + resolve flow against
 * the SignalCend v1 API. All calls are fully asynchronous and non-blocking
 * to comply with HiveMQ's extension execution model.
 *
 * The client:
 *   1. Builds the resolve payload from the intercepted MQTT message
 *   2. Signs it via POST /v1/sign using HMAC-SHA256
 *   3. Calls POST /v1/resolve with the X-Signature header
 *   4. Returns an ArbitrationResult containing the verdict fields
 */
public class SignalCendClient {

    private static final Logger log = LoggerFactory.getLogger(SignalCendClient.class);

    private final SignalCendConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public SignalCendClient(final SignalCendConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getHttpTimeoutMs()))
                .build();
    }

    /**
     * Asynchronously arbitrates a device state event.
     *
     * @param deviceId        the device identifier extracted from the payload
     * @param status          the reported device status (online/offline/error/etc.)
     * @param timestamp       ISO 8601 event timestamp from the device payload
     * @param signalStrength  RF signal strength in dBm (null if not present)
     * @param sequence        monotonic sequence number (null if not present)
     * @param sessionId       session identifier for stateful reconnect arbitration
     * @return CompletableFuture resolving to an ArbitrationResult
     */
    public CompletableFuture<ArbitrationResult> arbitrate(
            final String deviceId,
            final String status,
            final String timestamp,
            final Double signalStrength,
            final Long sequence,
            final String sessionId) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build the state payload
                final ObjectNode stateNode = mapper.createObjectNode();
                stateNode.put("device_id", deviceId);
                stateNode.put("status", status);
                stateNode.put("timestamp", timestamp);
                stateNode.put("reconnect_window_seconds", config.getReconnectWindowSeconds());
                if (signalStrength != null) {
                    stateNode.put("signal_strength", signalStrength);
                }
                if (sequence != null) {
                    stateNode.put("sequence", sequence);
                }

                final ObjectNode payload = mapper.createObjectNode();
                payload.put("api_key", config.getApiKey());
                payload.set("state", stateNode);
                if (sessionId != null) {
                    payload.put("session_id", sessionId);
                }

                final String body = mapper.writeValueAsString(payload);

                // Step 1 — Sign the payload
                final String signature = sign(body);

                // Step 2 — Resolve authoritative state
                final HttpRequest resolveRequest = HttpRequest.newBuilder()
                        .uri(URI.create(config.getBaseUrl() + "/resolve"))
                        .header("Content-Type", "application/json")
                        .header("X-Signature", signature)
                        .timeout(Duration.ofMillis(config.getHttpTimeoutMs()))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

                final HttpResponse<String> resolveResponse = httpClient.send(
                        resolveRequest, HttpResponse.BodyHandlers.ofString());

                if (resolveResponse.statusCode() != 200) {
                    log.warn("SignalCend: resolve returned HTTP {} for device {} — " +
                             "passing through original message",
                            resolveResponse.statusCode(), deviceId);
                    return ArbitrationResult.passThrough(status);
                }

                return parseResolveResponse(resolveResponse.body(), deviceId, status);

            } catch (final Exception e) {
                log.error("SignalCend: arbitration failed for device {} — {} — " +
                          "passing through original message", deviceId, e.getMessage());
                return ArbitrationResult.passThrough(status);
            }
        });
    }

    /**
     * Signs the payload body using HMAC-SHA256 with the configured API secret.
     * Calls /v1/sign to obtain the server-validated signature.
     * Falls back to local HMAC computation if the sign endpoint is unavailable.
     */
    private String sign(final String body) throws Exception {
        try {
            final HttpRequest signRequest = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl() + "/sign"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(config.getHttpTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            final HttpResponse<String> signResponse = httpClient.send(
                    signRequest, HttpResponse.BodyHandlers.ofString());

            if (signResponse.statusCode() == 200) {
                final JsonNode signJson = mapper.readTree(signResponse.body());
                final JsonNode sigNode = signJson.get("signature");
                if (sigNode != null && !sigNode.isNull()) {
                    return sigNode.asText();
                }
            }
        } catch (final Exception e) {
            log.debug("SignalCend: /sign endpoint unavailable — computing HMAC locally: {}",
                    e.getMessage());
        }

        // Local HMAC fallback — identical algorithm to the server
        return computeHmac(body, config.getApiSecret());
    }

    /**
     * Computes HMAC-SHA256 hex digest of the body using the provided secret.
     */
    private static String computeHmac(final String body, final String secret) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        final byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /**
     * Parses the /v1/resolve response JSON into an ArbitrationResult.
     */
    private ArbitrationResult parseResolveResponse(
            final String responseBody,
            final String deviceId,
            final String originalStatus) {
        try {
            final JsonNode root = mapper.readTree(responseBody);

            // Handle already_processed idempotency response
            final String responseStatus = root.path("status").asText("");
            final JsonNode resolvedState = root.path("resolved_state");

            if (resolvedState.isMissingNode() || resolvedState.isNull()) {
                log.debug("SignalCend: no resolved_state in response for device {} " +
                          "— passing through", deviceId);
                return ArbitrationResult.passThrough(originalStatus);
            }

            // Flat format response — resolved_state contains fields directly
            final String authoritativeStatus = resolvedState
                    .path("authoritative_status").asText(originalStatus);
            final String recommendedAction = resolvedState
                    .path("recommended_action").asText("ACT");
            final double confidence = resolvedState
                    .path("confidence").asDouble(1.0);
            final boolean raceConditionResolved = resolvedState
                    .path("race_condition_resolved").asBoolean(false);
            final boolean clockDriftCompensated = resolvedState
                    .path("clock_drift_compensated").asBoolean(false);
            final String resolutionSummary = resolvedState
                    .path("resolution_summary").asText("");

            log.debug("SignalCend: device={} original={} authoritative={} " +
                      "action={} confidence={} raceResolved={} driftCompensated={}",
                    deviceId, originalStatus, authoritativeStatus,
                    recommendedAction, confidence,
                    raceConditionResolved, clockDriftCompensated);

            return new ArbitrationResult(
                    authoritativeStatus,
                    recommendedAction,
                    confidence,
                    raceConditionResolved,
                    clockDriftCompensated,
                    resolutionSummary,
                    false  // not pass-through
            );

        } catch (final Exception e) {
            log.error("SignalCend: failed to parse resolve response for device {} — {}",
                    deviceId, e.getMessage());
            return ArbitrationResult.passThrough(originalStatus);
        }
    }

    /**
     * Immutable result from a SignalCend arbitration call.
     */
    public static class ArbitrationResult {

        private final String authoritativeStatus;
        private final String recommendedAction;
        private final double confidence;
        private final boolean raceConditionResolved;
        private final boolean clockDriftCompensated;
        private final String resolutionSummary;
        private final boolean passThrough;

        public ArbitrationResult(
                final String authoritativeStatus,
                final String recommendedAction,
                final double confidence,
                final boolean raceConditionResolved,
                final boolean clockDriftCompensated,
                final String resolutionSummary,
                final boolean passThrough) {
            this.authoritativeStatus    = authoritativeStatus;
            this.recommendedAction      = recommendedAction;
            this.confidence             = confidence;
            this.raceConditionResolved  = raceConditionResolved;
            this.clockDriftCompensated  = clockDriftCompensated;
            this.resolutionSummary      = resolutionSummary;
            this.passThrough            = passThrough;
        }

        public static ArbitrationResult passThrough(final String originalStatus) {
            return new ArbitrationResult(originalStatus, "ACT", 1.0,
                    false, false, "", true);
        }

        public String getAuthoritativeStatus()  { return authoritativeStatus; }
        public String getRecommendedAction()    { return recommendedAction; }
        public double getConfidence()           { return confidence; }
        public boolean isRaceConditionResolved(){ return raceConditionResolved; }
        public boolean isClockDriftCompensated(){ return clockDriftCompensated; }
        public String getResolutionSummary()    { return resolutionSummary; }
        public boolean isPassThrough()          { return passThrough; }
        public boolean isAct()    { return "ACT".equals(recommendedAction); }
        public boolean isConfirm(){ return "CONFIRM".equals(recommendedAction); }
        public boolean isLogOnly(){ return "LOG_ONLY".equals(recommendedAction); }
    }
}
