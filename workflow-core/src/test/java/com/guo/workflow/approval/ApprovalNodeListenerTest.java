package com.guo.workflow.approval;

import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionTriggerScope;
import com.guo.workflow.dispatcher.ActionDispatcher;
import com.guo.workflow.repository.ActionConfigRepository;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.event.FlowableActivityEvent;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalNodeListenerTest {

    @Test
    void dispatchesConfiguredNodeAction() {
        ActionConfigRepository configRepository = mock(ActionConfigRepository.class);
        ActionDispatcher dispatcher = mock(ActionDispatcher.class);
        RuntimeService runtimeService = mock(RuntimeService.class);
        RepositoryService repositoryService = mock(RepositoryService.class);
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        FlowableActivityEvent event = mock(FlowableActivityEvent.class);

        when(repositoryService.getProcessDefinition("definition-1"))
                .thenReturn(definition);
        when(definition.getKey()).thenReturn("purchaseApproval");
        when(runtimeService.createProcessInstanceQuery()).thenReturn(query);
        when(query.processInstanceId("process-1")).thenReturn(query);
        when(query.singleResult()).thenReturn(processInstance);
        when(processInstance.getBusinessKey()).thenReturn("PA-1001");
        when(processInstance.getTenantId()).thenReturn("tenant-1");
        when(processInstance.getProcessVariables()).thenReturn(Map.of(
                "businessKey", "PA-1001",
                "nodeName_financeCountersign", "财务会签"));

        String actionXml = """
                <actions>
                  <action event="complete" type="job" doType="message" sync="false">
                    <message channel="dingtalk" to="user-1">
                      <content>会签完成</content>
                    </message>
                  </action>
                </actions>
                """;
        when(configRepository.findByDefinitionAndScope(
                "definition-1",
                "purchaseApproval",
                "financeCountersign",
                ActionTriggerScope.NODE,
                "complete"))
                .thenReturn(Optional.of(new ActionConfigRepository.ActionConfig(
                        1L,
                        "purchaseApproval",
                        "definition-1",
                        "financeCountersign",
                        ActionTriggerScope.NODE,
                        "complete",
                        actionXml,
                        1,
                        true,
                        null)));

        when(event.getProcessDefinitionId()).thenReturn("definition-1");
        when(event.getProcessInstanceId()).thenReturn("process-1");
        when(event.getExecutionId()).thenReturn("execution-1");
        when(event.getActivityId()).thenReturn("financeCountersign");
        when(event.getActivityName()).thenReturn("财务会签");

        ApprovalNodeListener listener = new ApprovalNodeListener(
                configRepository,
                dispatcher,
                provider(runtimeService),
                provider(repositoryService));

        listener.onEvent(event);

        ArgumentCaptor<ActionInvocation> captor =
                ArgumentCaptor.forClass(ActionInvocation.class);
        verify(dispatcher).dispatch(captor.capture());
        ActionInvocation invocation = captor.getValue();
        assertEquals("PA-1001", invocation.context().businessKey());
        assertEquals("tenant-1", invocation.context().attributes().get("tenantId"));
        assertEquals("financeCountersign", invocation.context().activityId());
        assertEquals("财务会签", invocation.context().variables().get(
                "currentActivityName"));
        assertEquals(actionXml, invocation.configurationXml());
    }

    private <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
