package com.guo.workflow.trigger;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionTriggerScope;
import com.guo.workflow.dispatcher.ActionDispatcher;
import com.guo.workflow.repository.ActionConfigRepository;
import com.guo.workflow.service.WorkflowContextVariables;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.service.delegate.BaseTaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bridges Flowable task lifecycle events into framework action dispatch.
 */
@Component("actionTaskListener")
public class ActionTaskListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(ActionTaskListener.class);

    private static final Map<String, ActionEvent> EVENT_MAPPING = Map.of(
            BaseTaskListener.EVENTNAME_CREATE, ActionEvent.CREATE,
            BaseTaskListener.EVENTNAME_ASSIGNMENT, ActionEvent.ASSIGNMENT,
            BaseTaskListener.EVENTNAME_COMPLETE, ActionEvent.COMPLETE,
            BaseTaskListener.EVENTNAME_DELETE, ActionEvent.DELETE);

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final ActionDispatcher actionDispatcher;
    private final ActionConfigRepository configRepository; // 新增：注入数据库查询组件

    public ActionTaskListener(
            RepositoryService repositoryService,
            RuntimeService runtimeService,
            ActionDispatcher actionDispatcher,
            ActionConfigRepository configRepository) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.actionDispatcher = actionDispatcher;
        this.configRepository = configRepository;
    }

    @Override
    public void notify(DelegateTask delegateTask) {
        ActionEvent event = EVENT_MAPPING.get(delegateTask.getEventName());
        if (event == null) {
            log.debug(
                    "Ignoring unsupported Flowable task event={} taskId={}",
                    delegateTask.getEventName(),
                    delegateTask.getId());
            return;
        }

        String processDefinitionId = delegateTask.getProcessDefinitionId();
        String taskDefinitionKey = delegateTask.getTaskDefinitionKey();
        String processKey = resolveProcessDefinitionKey(processDefinitionId);

        Optional<ActionConfigRepository.ActionConfig> configOpt =
                configRepository.findByDefinitionAndScope(
                        processDefinitionId,
                        processKey,
                        taskDefinitionKey,
                        ActionTriggerScope.TASK,
                        event.value());

        if (configOpt.isEmpty() || configOpt.get().actionXml() == null || configOpt.get().actionXml().isBlank()) {
            log.debug("未配置动作 XML, processKey={}, taskKey={}, event={}", processKey, taskDefinitionKey, event.value());
            return;
        }
        String actionXml = configOpt.get().actionXml();

        String processInstanceId = delegateTask.getProcessInstanceId();
        String businessKey = resolveBusinessKey(processInstanceId);

        Map<String, Object> variables = new HashMap<>(delegateTask.getVariables());
        if (businessKey != null) {
            variables.put("businessKey", businessKey);
        }

        Map<String, Object> attributes = new HashMap<>();
        String tenantId = delegateTask.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            attributes.put("tenantId", tenantId);
        }
        Object operation = variables.remove(
                WorkflowContextVariables.OPERATION);
        if (event == ActionEvent.DELETE
                && operation != null
                && !operation.toString().isBlank()) {
            attributes.put("operation", operation.toString());
        }
        Object returnTarget = variables.remove(
                WorkflowContextVariables.RETURN_TARGET);
        if (event == ActionEvent.DELETE
                && returnTarget != null
                && !returnTarget.toString().isBlank()) {
            attributes.put("returnTargetActivityId", returnTarget.toString());
        }
        Object operator = variables.remove(
                WorkflowContextVariables.OPERATOR);
        if (operator != null && !operator.toString().isBlank()) {
            attributes.put("operatorId", operator.toString());
        }

        ActionContext context = new ActionContext(
                businessKey,
                processKey,
                processInstanceId,
                delegateTask.getId(),
                taskDefinitionKey,
                variables,
                attributes);

        actionDispatcher.dispatch(new ActionInvocation(event, context, actionXml));
    }

    private String resolveBusinessKey(String processInstanceId) {
        if (processInstanceId != null) {
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (processInstance != null
                    && processInstance.getBusinessKey() != null
                    && !processInstance.getBusinessKey().isBlank()) {
                return processInstance.getBusinessKey();
            }
        }
        return processInstanceId;
    }

    private String resolveProcessDefinitionKey(String processDefinitionId) {
        if (processDefinitionId == null || processDefinitionId.isBlank()) {
            return null;
        }
        ProcessDefinition processDefinition =
                repositoryService.getProcessDefinition(processDefinitionId);
        if (processDefinition != null) {
            return processDefinition.getKey();
        }
        int separator = processDefinitionId.indexOf(':');
        return separator < 0
                ? processDefinitionId
                : processDefinitionId.substring(0, separator);
    }


}
