package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionInvocation;

/**
 * Orchestrates synchronous actions before asynchronous instruction delivery.
 */
public interface ActionDispatcher {

    void dispatch(ActionInvocation invocation);
}

