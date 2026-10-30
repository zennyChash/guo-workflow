package com.guo.workflow.service;

/**
 * Reserved variable names used while dispatching workflow actions.
 */
public final class WorkflowContextVariables {

    public static final String OPERATION = "_guoWorkflowOperation";
    public static final String RETURN_TARGET = "_guoReturnTarget";
    public static final String OPERATOR = "_guoOperatorId";

    private WorkflowContextVariables() {
    }
}
