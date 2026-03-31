package com.signalcend.hivemq;

import com.signalcend.sdk.SignalCend;
import com.signalcend.sdk.model.Message;

public class SignalCendClient {
    private final SignalCend signalCend;
    
    public SignalCendClient(SignalCendConfig config) {
        this.signalCend = new SignalCend(config.getApiKey(), config.getApiSecret(), config.getBaseUrl());
    }
    
    public void sendPayload(String topic, String payload) {
        Message message = new Message();
        message.setTopic(topic);
        message.setPayload(payload);
        signalCend.sendMessage(message);
    }
}
