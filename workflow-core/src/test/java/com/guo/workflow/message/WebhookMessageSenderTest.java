package com.guo.workflow.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookMessageSenderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dingTalkBuildsMarkdownPayloadAndSignedUrl() throws Exception {
        CapturingWebhookClient client = new CapturingWebhookClient(
                "{\"errcode\":0,\"errmsg\":\"ok\"}");
        DingTalkMessageSender sender = new DingTalkMessageSender(
                "https://example.test/dingtalk?access_token=token",
                "secret",
                "user-id",
                client,
                objectMapper);

        sender.send(new MessageRequest(
                "dingtalk",
                "action-1",
                "ORDER-1",
                List.of("u1", "u2"),
                "markdown",
                "审批通知",
                "订单已通过"));

        JsonNode payload = objectMapper.readTree(client.body);
        assertEquals("markdown", payload.path("msgtype").asText());
        assertEquals("审批通知", payload.path("markdown").path("title").asText());
        assertEquals("u2", payload.path("at").path("atUserIds").get(1).asText());
        assertTrue(client.url.contains("timestamp="));
        assertTrue(client.url.contains("&sign="));
    }

    @Test
    void weComBuildsTextPayloadAndRejectsErrorResponse() throws Exception {
        CapturingWebhookClient success = new CapturingWebhookClient(
                "{\"errcode\":0,\"errmsg\":\"ok\"}");
        WeComMessageSender sender = new WeComMessageSender(
                "https://example.test/wecom",
                "mobile",
                success,
                objectMapper);

        sender.send(new MessageRequest(
                "wecom",
                "action-2",
                "ORDER-2",
                List.of("13800000000"),
                "text",
                null,
                "待审批"));

        JsonNode payload = objectMapper.readTree(success.body);
        assertEquals(
                "13800000000",
                payload.path("text")
                        .path("mentioned_mobile_list")
                        .get(0)
                        .asText());

        WeComMessageSender rejected = new WeComMessageSender(
                "https://example.test/wecom",
                "user-id",
                new CapturingWebhookClient(
                        "{\"errcode\":40001,\"errmsg\":\"invalid webhook\"}"),
                objectMapper);
        assertThrows(
                IllegalStateException.class,
                () -> rejected.send(new MessageRequest(
                        "wecom",
                        "action-3",
                        "ORDER-3",
                        List.of("u1"),
                        "text",
                        null,
                        "待审批")));
    }

    private static final class CapturingWebhookClient implements WebhookClient {

        private final String response;
        private String url;
        private String body;

        private CapturingWebhookClient(String response) {
            this.response = response;
        }

        @Override
        public String postJson(String url, String body) {
            this.url = url;
            this.body = body;
            return response;
        }
    }
}
