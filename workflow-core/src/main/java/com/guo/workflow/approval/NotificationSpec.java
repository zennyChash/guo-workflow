package com.guo.workflow.approval;

public record NotificationSpec(
        NotificationTrigger trigger,
        String channel,
        String recipient,
        String format,
        String title,
        String content) {
}
