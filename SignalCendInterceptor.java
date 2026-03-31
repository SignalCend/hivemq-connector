package com.signalcend.hivemq;

import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.interceptor.publish.PublishInboundInterceptor;
import com.hivemq.extension.sdk.api.interceptor.publish.parameter.PublishInboundInput;
import com.hivemq.extension.sdk.api.interceptor.publish.parameter.PublishInboundOutput;
import com.hivemq.extension.sdk.api.packets.publish.ModifiablePublishPacket;
import com.hivemq.extension.sdk.api.services.Services;
import com.signalcend.hivemq.SignalCendClient.ArbitrationResult;
import com.signalcend.hivemq.PayloadExtractor.ExtractedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SignalCendInterceptor intercepts every inbound MQTT PUBLISH packet,
 * extracts device state fields from the payload, and routes the event
 * through SignalCend's multi-signal arbitration API.
 *
 * Behavior by recommended_action:
 *
 *   ACT      — message delivered as-is (authoritative state confirmed)
 *   CONFIRM  — message delivered with X-SignalCend-Action: CONFIRM header injected
 *              into user properties (MQTT 5) or logged (MQTT 3.x)
 *              Downstream consumers can branch on this property.
 *   LOG_ONLY — message delivered with X-SignalCend-Action: LOG_ONLY user property.
 *              If signalcend.block.on.log.only=true, message is dropped entirely.
 *
 * All arbitration is asynchronous — the interceptor never blocks the
 * HiveMQ broker thread per HiveMQ extension best practices.
 *
 * Topic filtering:
 *   Only topics matching signalcend.topic.filter are arbitrated.
 *   All other topics pass through without modification.
 */
public class SignalCendInterceptor implements PublishInboundInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SignalCendInterceptor.class);

    // MQTT 5 User Property keys injected by the extension
    static final String PROP_ACTION     = "X-SignalCend-Action";
    static final String PROP_CONFIDENCE = "X-SignalCend-Confidence";
    static final String PROP_STATUS     = "X-SignalCend-Authoritative-Status";
    static final String PROP_RACE       = "X-SignalCend-Race-Resolved";
    static final String PROP_DRIFT      = "X-SignalCend-Drift-Compensated";
    static final String PROP_SUMMARY    = "X-SignalCend-Summary";

    private final SignalCendConfig   config;
    private final SignalCendClient   client;
    private final PayloadExtractor   extractor;

    public SignalCendInterceptor(final SignalCendConfig config,
                                 final SignalCendClient client) {
        this.config    = config;
        this.client    = client;
        this.extractor = new PayloadExtractor(config);
    }

    @Override
    public void onInboundPublish(
            final @NotNull PublishInboundInput input,
            final @NotNull PublishInboundOutput output) {

        final String topic    = input.getPublishPacket().getTopic();
        final String clientId = input.getClientInformation().getClientId();

        // Topic filter — skip arbitration for topics not in the configured list
        if (!shouldArbitrate(topic)) {
            return;
        }

        // Make the output async so we never block the broker thread
        final var asyncOutput = output.async(
                java.time.Duration.ofMillis(config.getHttpTimeoutMs() + 500),
                com.hivemq.extension.sdk.api.async.TimeoutFallback.SUCCESS);

        // Extract device state fields from the payload
        final ExtractedPayload extracted = extractor.extract(input.getPublishPacket());

        // Build a session ID that ties sequential events for the same device together
        final String sessionId = config.getSessionIdPrefix() + ":" + extracted.getDeviceId();

        log.debug("SignalCend: intercepted topic={} clientId={} deviceId={} status={}",
                topic, clientId, extracted.getDeviceId(), extracted.getStatus());

        // Arbitrate asynchronously via the SignalCend API
        client.arbitrate(
                extracted.getDeviceId(),
                extracted.getStatus(),
                extracted.getTimestamp(),
                extracted.getSignalStrength(),
                extracted.getSequence(),
                sessionId
        ).whenComplete((result, throwable) -> {
            try {
                if (throwable != null) {
                    log.error("SignalCend: arbitration error for device {} on topic {} — {}",
                            extracted.getDeviceId(), topic, throwable.getMessage());
                    // Fail open — pass through on error
                    asyncOutput.resume();
                    return;
                }

                applyArbitrationResult(output.get(), result, extracted, topic);

            } finally {
                asyncOutput.resume();
            }
        });
    }

    /**
     * Applies the arbitration result to the MQTT packet.
     * Injects SignalCend user properties and optionally drops the message.
     */
    private void applyArbitrationResult(
            final PublishInboundOutput output,
            final ArbitrationResult result,
            final ExtractedPayload extracted,
            final String topic) {

        if (result.isPassThrough()) {
            return;
        }

        final ModifiablePublishPacket packet = output.getPublishPacket();

        // Log race condition resolutions — these are the ghost offline events being caught
        if (result.isRaceConditionResolved()) {
            log.info("SignalCend: RACE CONDITION RESOLVED — topic={} deviceId={} " +
                     "original={} authoritative={} confidence={}",
                    topic, extracted.getDeviceId(),
                    extracted.getStatus(), result.getAuthoritativeStatus(),
                    result.getConfidence());
        }

        // Log clock drift compensation
        if (result.isClockDriftCompensated()) {
            log.info("SignalCend: CLOCK DRIFT COMPENSATED — topic={} deviceId={} " +
                     "server arrival sequencing applied",
                    topic, extracted.getDeviceId());
        }

        // Handle LOG_ONLY — drop or annotate depending on config
        if (result.isLogOnly() && config.isBlockOnLogOnly()) {
            log.warn("SignalCend: DROPPING message — topic={} deviceId={} " +
                     "confidence={} action=LOG_ONLY — signal environment too degraded " +
                     "for autonomous action",
                    topic, extracted.getDeviceId(), result.getConfidence());
            output.preventPublishDelivery();
            return;
        }

        // Inject arbitration metadata as MQTT 5 user properties
        // These travel with the message to all downstream subscribers
        // allowing historians, MES, alerting systems to branch on the enum
        try {
            packet.getUserProperties().addUserProperty(
                    PROP_ACTION, result.getRecommendedAction());
            packet.getUserProperties().addUserProperty(
                    PROP_CONFIDENCE, String.valueOf(result.getConfidence()));
            packet.getUserProperties().addUserProperty(
                    PROP_STATUS, result.getAuthoritativeStatus());
            if (result.isRaceConditionResolved()) {
                packet.getUserProperties().addUserProperty(PROP_RACE, "true");
            }
            if (result.isClockDriftCompensated()) {
                packet.getUserProperties().addUserProperty(PROP_DRIFT, "true");
            }
            if (!result.getResolutionSummary().isEmpty()) {
                packet.getUserProperties().addUserProperty(
                        PROP_SUMMARY, result.getResolutionSummary());
            }
        } catch (final UnsupportedOperationException e) {
            // MQTT 3.x clients do not support user properties
            // Log the arbitration result instead
            log.debug("SignalCend: MQTT 3.x client — user properties not available, " +
                      "logging only: deviceId={} action={} confidence={}",
                    extracted.getDeviceId(),
                    result.getRecommendedAction(),
                    result.getConfidence());
        }

        // Publish arbitration verdict to a dedicated status topic
        // so device twins and shadow systems receive the authoritative state
        publishArbitrationVerdictToStatusTopic(extracted, result, topic);
    }

    /**
     * Publishes the arbitration verdict to a dedicated status topic:
     *   signalcend/arbitrated/{original_topic}
     *
     * This allows device twin systems (AWS Device Shadow, Azure Device Twin,
     * custom historians) to subscribe to authoritative state separately from
     * the raw event stream.
     */
    private void publishArbitrationVerdictToStatusTopic(
            final ExtractedPayload extracted,
            final ArbitrationResult result,
            final String originalTopic) {

        final String verdictTopic = "signalcend/arbitrated/" + originalTopic;
        final String verdictPayload = String.format(
                "{\"device_id\":\"%s\",\"authoritative_status\":\"%s\"," +
                "\"recommended_action\":\"%s\",\"confidence\":%.2f," +
                "\"race_condition_resolved\":%b,\"clock_drift_compensated\":%b}",
                extracted.getDeviceId(),
                result.getAuthoritativeStatus(),
                result.getRecommendedAction(),
                result.getConfidence(),
                result.isRaceConditionResolved(),
                result.isClockDriftCompensated()
        );

        try {
            Services.publishService().publish(
                    com.hivemq.extension.sdk.api.services.publish.Publish.builder()
                            .topic(verdictTopic)
                            .qos(com.hivemq.extension.sdk.api.packets.general.Qos.AT_LEAST_ONCE)
                            .payload(verdictPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .build()
            );
            log.debug("SignalCend: published verdict to {}", verdictTopic);
        } catch (final Exception e) {
            log.debug("SignalCend: could not publish verdict to {} — {}",
                    verdictTopic, e.getMessage());
        }
    }

    /**
     * Checks whether a topic should be arbitrated based on the configured topic filters.
     * Supports exact match and prefix wildcard matching.
     */
    private boolean shouldArbitrate(final String topic) {
        for (final String filter : config.getTopicFilters()) {
            if (filter.equals("#")) return true;
            if (filter.endsWith("/#")) {
                final String prefix = filter.substring(0, filter.length() - 2);
                if (topic.startsWith(prefix + "/") || topic.equals(prefix)) return true;
            }
            if (topic.equals(filter)) return true;
        }
        return false;
    }
}
