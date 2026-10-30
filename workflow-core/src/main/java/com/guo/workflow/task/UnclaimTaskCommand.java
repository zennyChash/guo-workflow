package com.guo.workflow.task;

public record UnclaimTaskCommand(
        String taskId,
        String operatorId) {
}
