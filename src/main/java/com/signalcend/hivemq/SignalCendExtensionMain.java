package com.signalcend.hivemq;

import com.hivemq.extension.sdk.api.ExtensionMain;
import com.hivemq.extension.sdk.api.annotations.ExtensionEntryPoint;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.services.ExtensionLifecycleService;

@ExtensionEntryPoint
public class SignalCendExtensionMain implements ExtensionMain {

    @Override
    public void extensionStart(@NotNull ExtensionLifecycleService extensionLifecycleService) {
        SignalCendConfig config = new SignalCendConfig();
        // Load config from properties file
        extensionLifecycleService.loadExtensionConfig(config);
        
        SignalCendClient client = new SignalCendClient(config);
        
        // Register interceptor
        extensionLifecycleService.registerPublishInboundInterceptor(
            "SignalCendInterceptor", 
            new SignalCendInterceptor(client));
    }

    @Override
    public void extensionStop() {
        // Cleanup
    }
}
