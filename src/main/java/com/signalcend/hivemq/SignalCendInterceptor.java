package com.signalcend.hivemq;

import com.hivemq.extension.sdk.api.interceptors.publish.PublishInboundInterceptor;
import com.hivemq.extension.sdk.api.interceptors.publish.parameter.PublishInboundInterceptorArgs;
import com.hivemq.extension.sdk.api.packets.publish.ModifiableInboundPublishPacket;

public class SignalCendInterceptor implements PublishInboundInterceptor {
    
    private final SignalCendClient client;
    
    public SignalCendInterceptor(SignalCendClient client) {
        this.client = client;
    }
    
    @Override
    public void onInboundPublish(PublishInboundInterceptorArgs args) {
        ModifiableInboundPublishPacket packet = args.getPublishPacket();
        String topic = packet.getTopic();
        String payload = packet.getPayloadToPublish().toString(Charset.defaultCharset());
        
        // Forward to SignalCend
        client.sendPayload(topic, payload);
    }
}
