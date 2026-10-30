package com.guo.workflow.message;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * JDK HTTP client implementation with bounded connection and request timeouts.
 */
public class JdkWebhookClient implements WebhookClient {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public JdkWebhookClient(int connectTimeoutSeconds, int requestTimeoutSeconds) {
        if (connectTimeoutSeconds < 1 || requestTimeoutSeconds < 1) {
            throw new IllegalArgumentException(
                    "Webhook timeouts must be positive");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
    }

    @Override
    public String postJson(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Webhook request failed with HTTP " + response.statusCode());
        }
        return response.body();
    }
}
