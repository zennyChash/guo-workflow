package com.guo.workflow.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * DingTalk group robot webhook sender.
 */
public class DingTalkMessageSender implements MessageSender {

    private final String webhook;
    private final String secret;
    private final String recipientType;
    private final WebhookClient webhookClient;
    private final ObjectMapper objectMapper;

    public DingTalkMessageSender(
            String webhook,
            String secret,
            String recipientType,
            WebhookClient webhookClient,
            ObjectMapper objectMapper) {
        if (webhook == null || webhook.isBlank()) {
            throw new IllegalArgumentException("DingTalk webhook must not be blank");
        }
        this.webhook = webhook.trim();
        this.secret = secret == null || secret.isBlank() ? null : secret.trim();
        this.recipientType = normalizeRecipientType(recipientType);
        this.webhookClient = webhookClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return "dingtalk";
    }

    @Override
    public void send(MessageRequest message) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        String format = message.format() == null
                ? "text"
                : message.format().toLowerCase(Locale.ROOT);
        if ("markdown".equals(format)) {
            payload.put("msgtype", "markdown");
            ObjectNode markdown = payload.putObject("markdown");
            markdown.put(
                    "title",
                    message.title() == null || message.title().isBlank()
                            ? "工作流通知"
                            : message.title());
            markdown.put("text", message.content());
        } else {
            payload.put("msgtype", "text");
            payload.putObject("text").put("content", message.content());
        }

        ObjectNode at = payload.putObject("at");
        String recipientField = "mobile".equals(recipientType)
                ? "atMobiles"
                : "atUserIds";
        var recipients = at.putArray(recipientField);
        message.recipients().forEach(recipients::add);

        String response = webhookClient.postJson(
                signedUrl(),
                objectMapper.writeValueAsString(payload));
        validateResponse(response);
    }

    private String signedUrl() throws Exception {
        if (secret == null) {
            return webhook;
        }
        String timestamp = Long.toString(System.currentTimeMillis());
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String sign = URLEncoder.encode(
                Base64.getEncoder().encodeToString(mac.doFinal(
                        stringToSign.getBytes(StandardCharsets.UTF_8))),
                StandardCharsets.UTF_8);
        return webhook
                + (webhook.contains("?") ? "&" : "?")
                + "timestamp="
                + timestamp
                + "&sign="
                + sign;
    }

    private void validateResponse(String response) throws Exception {
        JsonNode body = objectMapper.readTree(response);
        int errorCode = body.path("errcode").asInt(-1);
        if (errorCode != 0) {
            throw new IllegalStateException(
                    "DingTalk webhook rejected the message: "
                            + body.path("errmsg").asText("unknown error"));
        }
    }

    private String normalizeRecipientType(String value) {
        String normalized = value == null
                ? "user-id"
                : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "user-id", "userid", "user" -> "user-id";
            case "mobile", "phone" -> "mobile";
            default -> throw new IllegalArgumentException(
                    "Unsupported DingTalk recipient type: " + value);
        };
    }
}
