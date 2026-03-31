package com.signalcend.hivemq;

import com.hivemq.extension.sdk.api.annotations.ConfigurationAttribute;
import com.hivemq.extension.sdk.api.annotations.GeneralConfiguration;

@GeneralConfiguration("signalcend")
public class SignalCendConfig {
    
    @ConfigurationAttribute("api_key")
    private String apiKey;
    
    @ConfigurationAttribute("api_secret")
    private String apiSecret;
    
    @ConfigurationAttribute("base_url")
    private String baseUrl = "https://api.signalcend.com/v1";
    
    // Getters/setters
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
