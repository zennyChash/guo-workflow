package com.guo.workflow.message;

/**
 * Pluggable transport for one message channel.
 *
 * <p>Implementations are discovered as Spring beans and routed by their
 * case-insensitive channel name.</p>
 */
public interface MessageSender {

    String channel();

    void send(MessageRequest message) throws Exception;
}
