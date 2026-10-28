package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;

/**
 * No-op action used to exercise routing and dispatch before concrete executors exist.
 */
final class EmptyAction implements BusinessAction {

    private final ActionType type;
    private final ActionDoType doType;

    EmptyAction(ActionType type, ActionDoType doType) {
        this.type = type;
        this.doType = doType;
    }

    @Override
    public ActionType type() {
        return type;
    }

    @Override
    public ActionDoType doType() {
        return doType;
    }

    @Override
    public void execute(ActionInvocation invocation, ActionDefinition definition) {
        // Intentionally empty: this implementation is only a routing test fixture.
    }
}
