package com.guo.workflow.message;

/**
 * Minimal HTTP transport used by built-in webhook message channels.
 */
public interface WebhookClient {

    String postJson(String url, String body) throws Exception;
}
