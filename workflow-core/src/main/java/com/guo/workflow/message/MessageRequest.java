package com.guo.workflow.message;

import java.util.List;

/**
 * Channel-neutral message payload passed to a {@link MessageSender}.
 *
 * <p>The action and business identifiers remain stable across asynchronous
 * retries so senders can implement idempotent delivery.</p>
 */
public record MessageRequest(
        String channel,
        String actionId,
        String businessKey,
        List<String> recipients,
        String format,
        String title,
        String content) {

    public MessageRequest {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }
}
