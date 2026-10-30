package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.dispatcher.DefaultActionDefinitionParser;
import com.guo.workflow.message.MessageRequest;
import com.guo.workflow.message.MessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageActionTest {

    private final DefaultActionDefinitionParser parser =
            new DefaultActionDefinitionParser();
    private MessageAction action;

    @AfterEach
    void closeExecutor() {
        if (action != null) {
            action.destroy();
        }
    }

    @Test
    void sendsMultipleChannelsConcurrentlyAndRendersTemplates() {
        CountDownLatch channelsReady = new CountDownLatch(2);
        RecordingSender dingtalk = new RecordingSender("dingtalk", channelsReady, null);
        RecordingSender email = new RecordingSender("email", channelsReady, null);
        action = new MessageAction(List.of(dingtalk, email), 2);

        action.execute(
                invocation(context()),
                definition("""
                        <actions>
                          <action id="notify"
                                  event="complete"
                                  type="job"
                                  doType="message"
                                  sync="false">
                            <message channel="dingtalk"
                                     to="#{salesUserId},#{$attrs.financeManagerId}"
                                     format="markdown">
                              <title>订单 #{businessKey} 审批通知</title>
                              <content>客户 #{customerName} 的订单已通过，金额 #{orderAmount}。</content>
                            </message>
                            <message channel="email" to="#{managerEmail}">
                              <content>任务 #{taskId} 已完成。</content>
                            </message>
                          </action>
                        </actions>
                        """));

        MessageRequest dingtalkMessage = dingtalk.singleMessage();
        assertEquals("notify", dingtalkMessage.actionId());
        assertEquals("ORDER-100", dingtalkMessage.businessKey());
        assertEquals(
                List.of("sales-1", "finance-1"),
                dingtalkMessage.recipients());
        assertEquals("markdown", dingtalkMessage.format());
        assertEquals("订单 ORDER-100 审批通知", dingtalkMessage.title());
        assertEquals(
                "客户 Acme 的订单已通过，金额 1200。",
                dingtalkMessage.content());

        MessageRequest emailMessage = email.singleMessage();
        assertEquals(List.of("manager@example.com"), emailMessage.recipients());
        assertEquals("text", emailMessage.format());
        assertNull(emailMessage.title());
        assertEquals("任务 TASK-1 已完成。", emailMessage.content());
    }

    @Test
    void wrapsUnknownChannelAsActionException() {
        action = new MessageAction(
                List.of(new RecordingSender("dingtalk", null, null)),
                2);

        FlowableActionException exception = assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation(context()),
                        definition(message("sms", "#{salesUserId}", "<content>hello</content>"))));

        assertTrue(exception.getMessage().contains("sms"));
    }

    @Test
    void wrapsSenderFailureAsActionException() {
        IOException failure = new IOException("channel unavailable");
        action = new MessageAction(
                List.of(new RecordingSender("dingtalk", null, failure)),
                2);

        FlowableActionException exception = assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation(context()),
                        definition(message(
                                "dingtalk",
                                "#{salesUserId}",
                                "<content>hello</content>"))));

        assertSame(failure, exception.getCause());
    }

    @Test
    void rejectsMissingRequiredMessageFields() {
        action = new MessageAction(
                List.of(new RecordingSender("dingtalk", null, null)),
                2);

        assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation(context()),
                        definition("""
                                <actions>
                                  <action event="complete" type="job"
                                          doType="message" sync="false">
                                    <message to="#{salesUserId}">
                                      <content>hello</content>
                                    </message>
                                  </action>
                                </actions>
                                """)));
        assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation(context()),
                        definition("""
                                <actions>
                                  <action event="complete" type="job"
                                          doType="message" sync="false">
                                    <message channel="dingtalk">
                                      <content>hello</content>
                                    </message>
                                  </action>
                                </actions>
                                """)));
        assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation(context()),
                        definition(message("dingtalk", "#{salesUserId}", ""))));
    }

    @Test
    void rejectsRecipientsThatRenderEmpty() {
        action = new MessageAction(
                List.of(new RecordingSender("dingtalk", null, null)),
                2);

        ActionContext context = new ActionContext(
                "ORDER-100",
                "order-approval",
                "PROCESS-1",
                "TASK-1",
                "manager-approval",
                Map.of("salesUserId", ""),
                Map.of());

        assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation(context),
                        definition(message(
                                "dingtalk",
                                "#{salesUserId}",
                                "<content>hello</content>"))));
    }

    private String message(String channel, String to, String childXml) {
        return """
                <actions>
                  <action event="complete" type="job" doType="message" sync="false">
                    <message channel="%s" to="%s">
                      %s
                    </message>
                  </action>
                </actions>
                """.formatted(channel, to, childXml);
    }

    private ActionDefinition definition(String xml) {
        return parser.parse(xml).get(0);
    }

    private ActionInvocation invocation(ActionContext context) {
        return new ActionInvocation(ActionEvent.COMPLETE, context, "");
    }

    private ActionContext context() {
        return new ActionContext(
                "ORDER-100",
                "order-approval",
                "PROCESS-1",
                "TASK-1",
                "manager-approval",
                Map.of(
                        "salesUserId", "sales-1",
                        "customerName", "Acme",
                        "orderAmount", 1200,
                        "managerEmail", "manager@example.com"),
                Map.of("financeManagerId", "finance-1"));
    }

    private static final class RecordingSender implements MessageSender {

        private final String channel;
        private final CountDownLatch channelsReady;
        private final Exception failure;
        private final List<MessageRequest> messages =
                Collections.synchronizedList(new ArrayList<>());

        private RecordingSender(
                String channel,
                CountDownLatch channelsReady,
                Exception failure) {
            this.channel = channel;
            this.channelsReady = channelsReady;
            this.failure = failure;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public void send(MessageRequest message) throws Exception {
            messages.add(message);
            if (channelsReady != null) {
                channelsReady.countDown();
                if (!channelsReady.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Message channels did not execute concurrently");
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private MessageRequest singleMessage() {
            assertEquals(1, messages.size());
            return messages.get(0);
        }
    }
}
