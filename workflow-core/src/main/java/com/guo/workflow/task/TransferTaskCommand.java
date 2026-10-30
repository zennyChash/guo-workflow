package com.guo.workflow.task;

public record TransferTaskCommand(
        String taskId,
        String operatorId,
        String targetUserId) {
}
