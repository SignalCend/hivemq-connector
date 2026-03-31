package com.signalcend.hivemq;

import com.hivemq.extension.sdk.api.ExtensionMain;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.parameter.ExtensionStartInput;
import com.hivemq.extension.sdk.api.parameter.ExtensionStartOutput;
import com.hivemq.extension.sdk.api.parameter.ExtensionStopInput;
import com.hivemq.extension.sdk.api.parameter.ExtensionStopOutput;
import com.hivemq.extension.sdk.api.services.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SignalCendExtensionMain is the entry point for the SignalCend HiveMQ extension.
 *
 * On start:
 *   1. Loads configuration from signalcend.properties
 *   2. Initializes the SignalCend API client
 *   3. Registers the publish inbound interceptor globally
 *
 * The interceptor runs on every inbound MQTT PUBLISH packet,
 * routing matching topics through SignalCend's arbitration API
 * and injecting the verdict as MQTT 5 user properties before
 * the message is delivered to any subscriber.
 *
 * Installation:
 *   1. Build: mvn clean package
 *   2. Copy target/signalcend-hivemq-extension-1.0.0.jar to
 *      {HIVEMQ_HOME}/extensions/signalcend-hivemq-extension/
 *   3. Copy hivemq-extension.xml to the same folder
 *   4. Create signalcend.properties in the same folder with:
 *        signalcend.api.key=your-api-key
 *        signalcend.api.secret=your-api-secret
 *   5. Restart HiveMQ (or hot-deploy — HiveMQ supports live extension loading)
 */
public class SignalCendExtensionMain implements ExtensionMain {

    private static final Logger log = LoggerFactory.getLogger(SignalCendExtensionMain.class);

    private SignalCendConfig    config;
    private SignalCendClient    client;
    private SignalCendInterceptor interceptor;

    @Override
    public void extensionStart(
            final @NotNull ExtensionStartInput input,
            final @NotNull ExtensionStartOutput output) {

        log.info("SignalCend: extension starting — version {}",
                input.getExtensionInformation().getVersion());

        try {
            // Load configuration
            config = new SignalCendConfig(
                    input.getExtensionInformation().getExtensionHomeFolder());

            // Initialize the API client
            client = new SignalCendClient(config);

            // Create the interceptor
            interceptor = new SignalCendInterceptor(config, client);

            // Register as a global interceptor — fires on every inbound PUBLISH
            // regardless of which client sent the message
            Services.initializerRegistry().setClientInitializer(
                    (initializerInput, clientContext) ->
                            clientContext.addPublishInboundInterceptor(interceptor)
            );

            log.info("SignalCend: extension started successfully — " +
                     "intercepting topics: {} — arbitration endpoint: {}",
                    config.getTopicFilters(), config.getBaseUrl());

        } catch (final Exception e) {
            log.error("SignalCend: extension failed to start — {}", e.getMessage(), e);
            // Prevent the extension from partially loading in a broken state
            output.preventExtensionStartup("SignalCend configuration error: " + e.getMessage());
        }
    }

    @Override
    public void extensionStop(
            final @NotNull ExtensionStopInput input,
            final @NotNull ExtensionStopOutput output) {
        log.info("SignalCend: extension stopped");
    }
}
