package com.livecraft.global;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 의 app.* 설정 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendUrl,
        Auth auth,
        LiveKit livekit,
        Toss toss,
        Recording recording,
        Plugin plugin
) {
    public record Auth(int tokenTtlHours, boolean testLoginEnabled, String testPassword) {}

    public record LiveKit(String url, String apiUrl, String apiKey, String apiSecret, int publisherGraceSeconds) {}

    public record Toss(String baseUrl, String clientKey, String secretKey) {}

    public record Recording(String dir) {}

    public record Plugin(int ackTimeoutSeconds, int maxAttempts) {}
}
