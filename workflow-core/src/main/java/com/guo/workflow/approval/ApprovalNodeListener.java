package com.guo.workflow.approval;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionTriggerScope;
import com.guo.workflow.dispatcher.ActionDispatcher;
import com.guo.workflow.repository.ActionConfigRepository;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.event.FlowableActivityEvent;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Dispatches NODE-scoped actions after a multi-instance approval node completes.
 */
@Component("approvalNodeListener")
public class ApprovalNodeListener implements FlowableEventListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNodeListener.class);
    private static final Set<FlowableEventType> EVENT_TYPES = Set.of(
            FlowableEngineEventType.MULTI_INSTANCE_ACTIVITY_COMPLETED,
            FlowableEngineEventType.MULTI_INSTANCE_ACTIVITY_COMPLETED_WITH_CONDITION);

    private final ActionConfigRepository configRepository;
    private final ActionDispatcher actionDispatcher;
    private final ObjectProvider<RuntimeService> runtimeServiceProvider;
    private final ObjectProvider<RepositoryService> repositoryServiceProvider;

    public ApprovalNodeListener(
            ActionConfigRepository configRepository,
            ActionDispatcher actionDispatcher,
            ObjectProvider<RuntimeService> runtimeServiceProvider,
            ObjectProvider<RepositoryService> repositoryServiceProvider) {
        this.configRepository = configRepository;
        this.actionDispatcher = actionDispatcher;
        this.runtimeServiceProvider = runtimeServiceProvider;
        this.repositoryServiceProvider = repositoryServiceProvider;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (!(event instanceof FlowableActivityEvent activityEvent)
                || !(event instanceof FlowableEngineEvent engineEvent)) {
            return;
        }
        String processDefinitionId = engineEvent.getProcessDefinitionId();
        String processInstanceId = engineEvent.getProcessInstanceId();
        String activityId = activityEvent.getActivityId();
        if (processDefinitionId == null || activityId == null) {
            return;
        }

        RuntimeService runtimeService = runtimeServiceProvider.getIfAvailable();
        RepositoryService repositoryService = repositoryServiceProvider.getIfAvailable();
        if (runtimeService == null || repositoryService == null) {
            log.warn("Flowable services unavailable for approval node event");
            return;
        }
        ProcessDefinition definition =
                repositoryService.getProcessDefinition(processDefinitionId);
        if (definition == null) {
            return;
        }
        var config = configRepository.findByDefinitionAndScope(
                processDefinitionId,
                definition.getKey(),
                activityId,
                ActionTriggerScope.NODE,
                ActionEvent.COMPLETE.value());
        if (config.isEmpty() || config.get().actionXml().isBlank()) {
            return;
        }

        ProcessInstance processInstance = runtimeService
                .createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        Map<String, Object> variables = processInstance == null
                ? Map.of()
                : new HashMap<>(processInstance.getProcessVariables());
        String nodeName = resolveNodeName(
                activityId,
                activityEvent.getActivityName(),
                variables);
        Map<String, Object> attributes = new HashMap<>();
        String tenantId = processInstance == null
                ? null
                : processInstance.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            attributes.put("tenantId", tenantId);
        }
        Object businessKeyVariable = variables.get("businessKey");
        String businessKey = businessKeyVariable == null
                ? processInstance == null
                        ? processInstanceId
                        : processInstance.getBusinessKey()
                : businessKeyVariable.toString();
        variables.put("currentActivityName", nodeName);
        ActionContext context = new ActionContext(
                businessKey,
                definition.getKey(),
                processInstanceId,
                engineEvent.getExecutionId(),
                activityId,
                variables,
                attributes);
        actionDispatcher.dispatch(new ActionInvocation(
                ActionEvent.COMPLETE,
                context,
                config.get().actionXml()));
    }

    private String resolveNodeName(
            String activityId,
            String activityName,
            Map<String, Object> variables) {
        for (String key : new String[] {
                "nodeName_" + activityId,
                "nodeName",
                "activityName_" + activityId,
                "activityName"
        }) {
            Object value = variables.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return activityName == null || activityName.isBlank()
                ? activityId
                : activityName;
    }

    @Override
    public boolean isFailOnException() {
        return true;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }

    @Override
    public Collection<? extends FlowableEventType> getTypes() {
        return EVENT_TYPES;
    }
}
