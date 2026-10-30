package com.guo.workflow.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guo.workflow.approval.ApprovalNodeSpec;
import com.guo.workflow.approval.ApprovalReturnTarget;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.mirror.ProcessDefinitionCatalog;
import com.guo.workflow.mirror.ProcessDefinitionCatalogRepository;
import com.guo.workflow.service.CancelProcessCommand;
import com.guo.workflow.service.ReturnTarget;
import com.guo.workflow.service.ReturnTaskCommand;
import com.guo.workflow.service.WorkflowOperationService;
import com.guo.workflow.service.WorkflowContextVariables;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Constrains return and cancellation to explicit business-safe operations.
 */
@Service
public class DefaultWorkflowOperationService implements WorkflowOperationService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultWorkflowOperationService.class);

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final RepositoryService repositoryService;
    private final ProcessDefinitionCatalogRepository definitionRepository;
    private final ObjectMapper objectMapper;

    public DefaultWorkflowOperationService(
            RuntimeService runtimeService,
            TaskService taskService,
            RepositoryService repositoryService,
            ProcessDefinitionCatalogRepository definitionRepository,
            ObjectMapper objectMapper) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.repositoryService = repositoryService;
        this.definitionRepository = definitionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ReturnTarget> allowedReturnTargets(String taskId) {
        Task task = requireActiveTask(taskId);
        ApprovalNodeSpec node = requireApprovalNode(task);
        return node.allowedReturnTargets().stream()
                .map(target -> new ReturnTarget(
                        target.activityId(),
                        target.name()))
                .toList();
    }

    @Override
    @Transactional
    public void returnTask(ReturnTaskCommand command) {
        if (command == null) {
            throw new FlowableActionException("Return command must not be null");
        }
        String targetActivityId = requireText(
                command.targetActivityId(),
                "Return target activity id");
        Task task = requireActiveTask(command.taskId());
        ApprovalNodeSpec node = requireApprovalNode(task);
        boolean allowed = node.allowedReturnTargets().stream()
                .map(ApprovalReturnTarget::activityId)
                .anyMatch(targetActivityId::equals);
        if (!allowed) {
            throw new FlowableActionException(
                    "Return target is not allowed for activity "
                            + task.getTaskDefinitionKey()
                            + ": "
                            + targetActivityId);
        }
        validateTargetTask(task, targetActivityId);

        Map<String, Object> variables = new HashMap<>(
                runtimeService.getVariables(task.getProcessInstanceId()));
        variables.put(WorkflowContextVariables.OPERATION, "RETURN");
        variables.put(WorkflowContextVariables.RETURN_TARGET, targetActivityId);
        variables.put("returnSourceActivityId", task.getTaskDefinitionKey());
        variables.put("returnTargetActivityId", targetActivityId);
        String comment = normalizeOptional(command.comment());
        if (comment != null) {
            variables.put("approvalComment", comment);
            variables.put("returnComment", comment);
        }

        log.info(
                "Returning task taskId={}, processInstanceId={}, from={}, to={}",
                task.getId(),
                task.getProcessInstanceId(),
                task.getTaskDefinitionKey(),
                targetActivityId);
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(task.getProcessInstanceId())
                .moveActivityIdTo(task.getTaskDefinitionKey(), targetActivityId)
                .processVariables(variables)
                .changeState();
        runtimeService.removeVariable(
                task.getProcessInstanceId(),
                WorkflowContextVariables.OPERATION);
        runtimeService.removeVariable(
                task.getProcessInstanceId(),
                WorkflowContextVariables.RETURN_TARGET);
    }

    @Override
    @Transactional
    public void cancelProcess(CancelProcessCommand command) {
        if (command == null) {
            throw new FlowableActionException("Cancel command must not be null");
        }
        String processInstanceId = requireText(
                command.processInstanceId(),
                "Process instance id");
        ProcessInstance processInstance = runtimeService
                .createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (processInstance == null) {
            throw new FlowableActionException(
                    "Active process instance not found: " + processInstanceId);
        }

        String reason = normalizeOptional(command.reason());
        Map<String, Object> variables = new HashMap<>();
        variables.put(WorkflowContextVariables.OPERATION, "CANCEL");
        if (reason != null) {
            variables.put("cancelReason", reason);
            variables.put("approvalComment", reason);
        }
        runtimeService.setVariables(processInstanceId, variables);

        log.info(
                "Cancelling process processInstanceId={}, reason={}",
                processInstanceId,
                reason);
        runtimeService.deleteProcessInstance(
                processInstanceId,
                reason == null ? "业务取消" : "业务取消: " + reason);
    }

    private Task requireActiveTask(String taskId) {
        String normalizedTaskId = requireText(taskId, "Task id");
        Task task = taskService.createTaskQuery()
                .taskId(normalizedTaskId)
                .singleResult();
        if (task == null) {
            throw new FlowableActionException(
                    "Active task not found: " + normalizedTaskId);
        }
        return task;
    }

    private ApprovalNodeSpec requireApprovalNode(Task task) {
        ProcessDefinitionCatalog definition = definitionRepository
                .findByProcessDefinitionId(task.getProcessDefinitionId())
                .orElseThrow(() -> new FlowableActionException(
                        "Process definition was not published through the approval compiler: "
                                + task.getProcessDefinitionId()));
        if (definition.semanticJson() == null
                || definition.semanticJson().isBlank()) {
            throw new FlowableActionException(
                    "Approval semantics are missing for process definition: "
                            + task.getProcessDefinitionId());
        }
        try {
            JsonNode nodes = objectMapper
                    .readTree(definition.semanticJson())
                    .path("nodes");
            for (JsonNode node : nodes) {
                ApprovalNodeSpec candidate = objectMapper.treeToValue(
                        node,
                        ApprovalNodeSpec.class);
                if (task.getTaskDefinitionKey().equals(candidate.activityId())) {
                    return candidate;
                }
            }
        } catch (JsonProcessingException exception) {
            throw new FlowableActionException(
                    "Failed to read approval semantics for process definition: "
                            + task.getProcessDefinitionId(),
                    exception);
        }
        throw new FlowableActionException(
                "Approval node is not declared in process semantics: "
                        + task.getTaskDefinitionKey());
    }

    private void validateTargetTask(Task task, String targetActivityId) {
        BpmnModel model = repositoryService.getBpmnModel(
                task.getProcessDefinitionId());
        FlowElement target = model == null
                ? null
                : model.getFlowElement(targetActivityId);
        if (!(target instanceof UserTask)) {
            throw new FlowableActionException(
                    "Return target must be an existing user task: "
                            + targetActivityId);
        }
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
