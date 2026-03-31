package com.signalcend.hivemq;

import com.hivemq.extension.sdk.api.interceptors.publish.PublishPacketInboundInterceptor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.json.JSONObject;

public class PayloadExtractor implements PublishPacketInboundInterceptor {
    
    @Override
    public void onInboundPublish(PublishPacketInboundInterceptor.Context context) {
        ByteBuf payload = context.getPublishPacket().getPayload();
        byte[] bytes = new byte[payload.readableBytes()];
        payload.readBytes(bytes);
        
        String jsonPayload = new String(bytes);
        JSONObject json = new JSONObject(jsonPayload);
        
        // Extract SignalCend fields
        String deviceId = json.optString("device_id", "unknown");
        String eventType = json.optString("event_type", "message");
        
        // Log or process extracted data
        System.out.println("SignalCend: " + deviceId + " → " + eventType);
    }
}
