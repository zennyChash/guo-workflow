package com.guo.workflow.trigger;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.dispatcher.ActionDispatcher;
import com.guo.workflow.repository.ActionConfigRepository;

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

         // 【重构点】：从数据库读取 XML 配置
        Optional<ActionConfigRepository.ActionConfig> configOpt =
                configRepository.findByNodeAndEvent(processKey, taskDefinitionKey, event.value());

        if (configOpt.isEmpty() || configOpt.get().actionXml() == null || configOpt.get().actionXml().isBlank()) {
            log.debug("未配置动作 XML, processKey={}, taskKey={}, event={}", processKey, taskDefinitionKey, event.value());
            return;
        }
        String actionXml = configOpt.get().actionXml();

        String processInstanceId = delegateTask.getProcessInstanceId();
        String businessKey = resolveBusinessKey(processInstanceId);

        // 组装上下文（注意把 businessKey 塞进 variables，防止 SQL 里取不到）
        Map<String, Object> variables = new HashMap<>(delegateTask.getVariables());
        if (businessKey != null) {
            variables.put("businessKey", businessKey);
        }

        ActionContext context = new ActionContext(
                businessKey,
                processKey,
                processInstanceId,
                delegateTask.getId(),
                taskDefinitionKey,
                variables,
                new HashMap<>());

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
