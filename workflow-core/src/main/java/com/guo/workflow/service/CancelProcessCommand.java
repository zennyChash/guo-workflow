package com.guo.workflow.service;

/**
 * Cancels an active process instance while retaining Flowable history.
 */
public record CancelProcessCommand(
        String processInstanceId,
        String reason) {
}
