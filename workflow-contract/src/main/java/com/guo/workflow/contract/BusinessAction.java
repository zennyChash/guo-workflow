package com.guo.workflow.contract;

/**
 * Unified contract implemented by every business action executor.
 */
public interface BusinessAction {

    ActionType type();

    ActionDoType doType();

    void execute(ActionInvocation invocation, ActionDefinition definition);
}

