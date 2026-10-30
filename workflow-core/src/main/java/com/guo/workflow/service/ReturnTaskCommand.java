package com.guo.workflow.service;

/**
 * Returns one active task to a configured previous approval node.
 */
public record ReturnTaskCommand(
        String taskId,
        String targetActivityId,
        String comment) {
}
