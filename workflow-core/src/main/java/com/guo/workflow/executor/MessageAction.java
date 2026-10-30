package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.message.MessageRequest;
import com.guo.workflow.message.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends one or more messages through pluggable channel implementations.
 */
@Component
public class MessageAction implements BusinessAction, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MessageAction.class);
    private static final String DEFAULT_FORMAT = "text";
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final Map<String, MessageSender> senders;
    private final ExecutorService executor;

    @Autowired
    public MessageAction(
            List<MessageSender> senders,
            @Value("${guo.workflow.message.max-concurrency:8}") int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException(
                    "guo.workflow.message.max-concurrency must be positive");
        }
        this.senders = indexSenders(senders);
        this.executor = Executors.newFixedThreadPool(
                maxConcurrency,
                new MessageThreadFactory());
    }

    @Override
    public ActionType type() {
        return ActionType.JOB;
    }

    @Override
    public ActionDoType doType() {
        return ActionDoType.MESSAGE;
    }

    @Override
    public void execute(ActionInvocation invocation, ActionDefinition definition) {
        List<MessageRequest> messages = parseMessages(
                definition,
                invocation.context());
        List<Callable<Void>> tasks = messages.stream()
                .<Callable<Void>>map(message -> () -> {
                    send(message);
                    return null;
                })
                .toList();

        List<Future<Void>> futures;
        try {
            futures = executor.invokeAll(tasks);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FlowableActionException(
                    "Message sending was interrupted",
                    exception);
        } catch (RejectedExecutionException exception) {
            throw new FlowableActionException(
                    "Message executor rejected the send request",
                    exception);
        }

        List<FlowableActionException> failures = new java.util.ArrayList<>();
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new FlowableActionException(
                        "Waiting for message sending was interrupted",
                        exception);
            } catch (ExecutionException exception) {
                failures.add(wrapFailure(exception.getCause()));
            }
        }
        if (!failures.isEmpty()) {
            FlowableActionException failure = failures.get(0);
            failures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private List<MessageRequest> parseMessages(
            ActionDefinition definition,
            ActionContext context) {
        List<ActionNode> messageNodes = definition.nodes().stream()
                .filter(node -> "message".equals(node.name()))
                .toList();
        if (messageNodes.isEmpty()) {
            throw new FlowableActionException(
                    "Message action must configure at least one <message> node");
        }
        return messageNodes.stream()
                .map(node -> toMessageRequest(
                        node,
                        context,
                        definition.actionId()))
                .toList();
    }

    private MessageRequest toMessageRequest(
            ActionNode node,
            ActionContext context,
            String actionId) {
        String channel = normalizeChannel(requiredAttribute(node, "channel"));
        String toTemplate = requiredAttribute(node, "to");
        String format = optionalAttribute(node, "format", DEFAULT_FORMAT)
                .toLowerCase(Locale.ROOT);

        String renderedTo = ActionTemplateResolver.render(
                toTemplate,
                context,
                "Message");
        List<String> recipients = Arrays.stream(renderedTo.split(","))
                .map(String::trim)
                .filter(recipient -> !recipient.isEmpty())
                .toList();
        if (recipients.isEmpty()) {
            throw new FlowableActionException(
                    "Message recipients resolved to an empty value for channel: "
                            + channel);
        }

        String titleTemplate = childText(node, "title");
        String title = titleTemplate == null
                ? null
                : ActionTemplateResolver.render(titleTemplate, context, "Message");
        if (title != null && title.isBlank()) {
            title = null;
        }

        String contentTemplate = childText(node, "content");
        if (contentTemplate == null) {
            throw new FlowableActionException(
                    "Message content is required for channel: " + channel);
        }
        String content = ActionTemplateResolver.render(
                contentTemplate,
                context,
                "Message");
        if (content.isBlank()) {
            throw new FlowableActionException(
                    "Message content resolved to an empty value for channel: "
                            + channel);
        }

        return new MessageRequest(
                channel,
                actionId,
                context.businessKey(),
                recipients,
                format,
                title,
                content);
    }

    private void send(MessageRequest message) {
        MessageSender sender = senders.get(message.channel());
        if (sender == null) {
            throw new FlowableActionException(
                    "Message channel implementation not found: " + message.channel());
        }
        try {
            sender.send(message);
            log.info(
                    "Message sent via channel={}, recipientCount={}",
                    message.channel(),
                    message.recipients().size());
        } catch (Exception exception) {
            throw new FlowableActionException(
                    "Failed to send message via channel: " + message.channel(),
                    exception);
        }
    }

    private FlowableActionException wrapFailure(Throwable failure) {
        if (failure instanceof FlowableActionException actionException) {
            return actionException;
        }
        return new FlowableActionException("Message sending failed", failure);
    }

    private Map<String, MessageSender> indexSenders(List<MessageSender> candidates) {
        Map<String, MessageSender> indexed = new LinkedHashMap<>();
        List<MessageSender> resolvedCandidates =
                candidates == null ? List.of() : candidates;
        for (MessageSender sender : resolvedCandidates) {
            String channel = normalizeChannel(sender.channel());
            MessageSender existing = indexed.putIfAbsent(channel, sender);
            if (existing != null) {
                throw new FlowableActionException(
                        "Duplicate message channel implementation: " + channel);
            }
        }
        return Map.copyOf(indexed);
    }

    private String requiredAttribute(ActionNode node, String name) {
        String value = optionalAttribute(node, name, null);
        if (value == null) {
            throw new FlowableActionException(
                    "Message node is missing required attribute: " + name);
        }
        return value;
    }

    private String optionalAttribute(ActionNode node, String name, String defaultValue) {
        String value = node.attributes().get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String childText(ActionNode parent, String name) {
        return parent.children().stream()
                .filter(child -> name.equals(child.name()))
                .map(ActionNode::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new FlowableActionException(
                    "Message sender channel must not be blank");
        }
        return channel.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private static final class MessageThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                    task,
                    "workflow-message-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
