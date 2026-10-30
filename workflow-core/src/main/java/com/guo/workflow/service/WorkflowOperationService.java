package com.guo.workflow.service;

import java.util.List;

/**
 * Controlled business operations on active workflow tasks and instances.
 */
public interface WorkflowOperationService {

    List<ReturnTarget> allowedReturnTargets(String taskId);

    void returnTask(ReturnTaskCommand command);

    void cancelProcess(CancelProcessCommand command);
}
