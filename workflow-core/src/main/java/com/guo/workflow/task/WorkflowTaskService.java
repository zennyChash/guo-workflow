package com.guo.workflow.task;

import java.util.List;
import java.util.Optional;

/**
 * Stable task facade that prevents host applications from depending directly on
 * Flowable's task API.
 */
public interface WorkflowTaskService {

    List<TaskView> findTodoTasks(
            String tenantId,
            String operatorId,
            int limit);

    Optional<TaskView> findTask(String taskId);

    void claimTask(ClaimTaskCommand command);

    void unclaimTask(UnclaimTaskCommand command);

    void transferTask(TransferTaskCommand command);

    void completeTask(CompleteTaskCommand command);
}
