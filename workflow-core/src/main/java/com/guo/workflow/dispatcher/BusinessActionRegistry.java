package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;

/**
 * Strategy registry used by the dispatcher to resolve an action by type.
 */
public interface BusinessActionRegistry {

    BusinessAction resolve(ActionType type, ActionDoType doType);
}

