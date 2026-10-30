package com.guo.workflow.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * WeCom group robot webhook sender.
 */
public class WeComMessageSender implements MessageSender {

    private final String webhook;
    private final String recipientType;
    private final WebhookClient webhookClient;
    private final ObjectMapper objectMapper;

    public WeComMessageSender(
            String webhook,
            String recipientType,
            WebhookClient webhookClient,
            ObjectMapper objectMapper) {
        if (webhook == null || webhook.isBlank()) {
            throw new IllegalArgumentException("WeCom webhook must not be blank");
        }
        this.webhook = webhook.trim();
        this.recipientType = normalizeRecipientType(recipientType);
        this.webhookClient = webhookClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return "wecom";
    }

    @Override
    public void send(MessageRequest message) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        String format = message.format() == null
                ? "text"
                : message.format().toLowerCase(Locale.ROOT);
        if ("markdown".equals(format)) {
            payload.put("msgtype", "markdown");
            String title = message.title() == null || message.title().isBlank()
                    ? ""
                    : "### " + message.title() + "\n";
            payload.putObject("markdown").put("content", title + message.content());
        } else {
            payload.put("msgtype", "text");
            ObjectNode text = payload.putObject("text");
            text.put("content", message.content());
            String recipientField = "mobile".equals(recipientType)
                    ? "mentioned_mobile_list"
                    : "mentioned_list";
            var recipients = text.putArray(recipientField);
            message.recipients().forEach(recipients::add);
        }

        String response = webhookClient.postJson(
                webhook,
                objectMapper.writeValueAsString(payload));
        validateResponse(response);
    }

    private void validateResponse(String response) throws Exception {
        JsonNode body = objectMapper.readTree(response);
        int errorCode = body.path("errcode").asInt(-1);
        if (errorCode != 0) {
            throw new IllegalStateException(
                    "WeCom webhook rejected the message: "
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
                    "Unsupported WeCom recipient type: " + value);
        };
    }
}
