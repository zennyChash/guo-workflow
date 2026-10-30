package com.guo.workflow.task;

import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.service.WorkflowContextVariables;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default task facade with explicit operator and assignee checks.
 */
@Service
public class DefaultWorkflowTaskService implements WorkflowTaskService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultWorkflowTaskService.class);
    private static final int MAX_PAGE_SIZE = 500;

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final ObjectProvider<WorkflowGroupResolver> groupResolverProvider;

    public DefaultWorkflowTaskService(
            TaskService taskService,
            RuntimeService runtimeService,
            ObjectProvider<WorkflowGroupResolver> groupResolverProvider) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.groupResolverProvider = groupResolverProvider;
    }

    @Override
    public List<TaskView> findTodoTasks(
            String tenantId,
            String operatorId,
            int limit) {
        String operator = requireText(operatorId, "Operator id");
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new FlowableActionException(
                    "Todo query limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        List<String> groupIds = resolveGroupIds(operator);
        var query = taskService.createTaskQuery();
        if (groupIds.isEmpty()) {
            query.taskCandidateOrAssigned(operator);
        } else {
            query.or()
                    .taskCandidateUser(operator)
                    .taskCandidateGroupIn(groupIds)
                    .taskAssignee(operator)
                    .endOr();
        }
        query.active().orderByTaskCreateTime().desc();
        if (tenantId != null && !tenantId.isBlank()) {
            query.taskTenantId(tenantId.trim());
        }
        return query.listPage(0, limit).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public Optional<TaskView> findTask(String taskId) {
        Task task = taskService.createTaskQuery()
                .taskId(requireText(taskId, "Task id"))
                .singleResult();
        return Optional.ofNullable(task).map(this::toView);
    }

    @Override
    @Transactional
    public void claimTask(ClaimTaskCommand command) {
        if (command == null) {
            throw new FlowableActionException("Claim command must not be null");
        }
        String operator = requireText(command.operatorId(), "Operator id");
        Task task = requireTask(command.taskId());
        if (operator.equals(task.getAssignee())) {
            return;
        }
        if (task.getAssignee() != null && !task.getAssignee().isBlank()) {
            throw new FlowableActionException(
                    "Task is already assigned to another user: " + task.getId());
        }
        if (!isCandidate(task.getId(), operator)) {
            throw new FlowableActionException(
                    "Operator is not a direct candidate of task: " + task.getId());
        }
        taskService.claim(task.getId(), operator);
        log.info("Task claimed taskId={}, operatorId={}", task.getId(), operator);
    }

    @Override
    @Transactional
    public void unclaimTask(UnclaimTaskCommand command) {
        if (command == null) {
            throw new FlowableActionException("Unclaim command must not be null");
        }
        String operator = requireText(command.operatorId(), "Operator id");
        Task task = requireAssignedTask(command.taskId(), operator);
        taskService.unclaim(task.getId());
        log.info("Task unclaimed taskId={}, operatorId={}", task.getId(), operator);
    }

    @Override
    @Transactional
    public void transferTask(TransferTaskCommand command) {
        if (command == null) {
            throw new FlowableActionException("Transfer command must not be null");
        }
        String operator = requireText(command.operatorId(), "Operator id");
        String targetUserId = requireText(
                command.targetUserId(),
                "Transfer target user id");
        Task task = requireAssignedTask(command.taskId(), operator);
        if (targetUserId.equals(task.getAssignee())) {
            return;
        }
        taskService.setAssignee(task.getId(), targetUserId);
        log.info(
                "Task transferred taskId={}, from={}, to={}",
                task.getId(),
                operator,
                targetUserId);
    }

    @Override
    @Transactional
    public void completeTask(CompleteTaskCommand command) {
        if (command == null) {
            throw new FlowableActionException("Complete command must not be null");
        }
        String operator = requireText(command.operatorId(), "Operator id");
        Task task = requireAssignedTask(command.taskId(), operator);
        if (task.getDelegationState() == DelegationState.PENDING) {
            throw new FlowableActionException(
                    "Delegated task must be resolved by the delegate before completion: "
                            + task.getId());
        }

        String comment = normalizeOptional(command.comment());
        if (comment != null) {
            taskService.addComment(
                    task.getId(),
                    task.getProcessInstanceId(),
                    comment);
        }
        Map<String, Object> variables = new HashMap<>(command.variables());
        if (comment != null) {
            variables.put("approvalComment", comment);
        }
        taskService.setVariableLocal(
                task.getId(),
                WorkflowContextVariables.OPERATOR,
                operator);
        taskService.complete(task.getId(), variables);
        log.info(
                "Task completed taskId={}, operatorId={}, processInstanceId={}",
                task.getId(),
                operator,
                task.getProcessInstanceId());
    }

    private Task requireTask(String taskId) {
        String normalizedId = requireText(taskId, "Task id");
        Task task = taskService.createTaskQuery()
                .taskId(normalizedId)
                .singleResult();
        if (task == null) {
            throw new FlowableActionException(
                    "Active task not found: " + normalizedId);
        }
        return task;
    }

    private Task requireAssignedTask(String taskId, String operatorId) {
        Task task = requireTask(taskId);
        if (task.getAssignee() == null
                || !operatorId.equals(task.getAssignee())) {
            throw new FlowableActionException(
                    "Task is not assigned to the operator: " + task.getId());
        }
        return task;
    }

    private boolean isCandidate(String taskId, String operatorId) {
        List<String> groupIds = resolveGroupIds(operatorId);
        return taskService.getIdentityLinksForTask(taskId).stream()
                .filter(link -> "candidate".equals(link.getType()))
                .anyMatch(link -> operatorId.equals(link.getUserId())
                        || (link.getGroupId() != null
                        && groupIds.contains(link.getGroupId())));
    }

    private List<String> resolveGroupIds(String operatorId) {
        WorkflowGroupResolver resolver =
                groupResolverProvider.getIfAvailable();
        if (resolver == null) {
            return List.of();
        }
        List<String> resolved = resolver.groupIds(operatorId);
        if (resolved == null) {
            return List.of();
        }
        return resolved.stream()
                .filter(groupId -> groupId != null && !groupId.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private TaskView toView(Task task) {
        ProcessInstance processInstance = runtimeService
                .createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();
        return new TaskView(
                task.getId(),
                task.getProcessInstanceId(),
                task.getProcessDefinitionId(),
                processInstance == null
                        ? processDefinitionKey(task.getProcessDefinitionId())
                        : processInstance.getProcessDefinitionKey(),
                processInstance == null
                        ? null
                        : processInstance.getBusinessKey(),
                task.getTenantId(),
                task.getTaskDefinitionKey(),
                task.getName(),
                task.getAssignee(),
                task.getOwner(),
                task.getDelegationState() == null
                        ? null
                        : task.getDelegationState().name(),
                toInstant(task.getCreateTime()),
                toInstant(task.getDueDate()));
    }

    private String processDefinitionKey(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        int separator = processDefinitionId.indexOf(':');
        return separator < 0
                ? processDefinitionId
                : processDefinitionId.substring(0, separator);
    }

    private java.time.Instant toInstant(Date value) {
        return value == null ? null : value.toInstant();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new FlowableActionException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
