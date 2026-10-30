package com.guo.workflow.task;

public record ClaimTaskCommand(
        String taskId,
        String operatorId) {
}
