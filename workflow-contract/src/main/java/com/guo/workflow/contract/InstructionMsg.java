package com.guo.workflow.contract;

/**
 * Self-contained task instruction for an asynchronous consumer.
 */
public record InstructionMsg(
        String taskId,
        String businessKey,
        ActionEvent event,
        ActionType type,
        ActionInvocation invocation,
        ActionDefinition definition) {
}

