package com.guo.workflow;

import com.guo.workflow.service.StartProcessCommand;
import com.guo.workflow.service.WorkflowEngineService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowStartContextIntegrationTest {

    @Autowired
    private WorkflowEngineService workflowEngineService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startsTenantProcessWithInitiatorAndCoreVariables() {
        String processKey = "tenantStartProcess";
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://guo.workflow/test">
                  <process id="tenantStartProcess" name="租户发起测试" isExecutable="true">
                    <startEvent id="start" />
                    <sequenceFlow sourceRef="start" targetRef="approval" />
                    <userTask id="approval" name="审批"
                              flowable:assignee="${approver}" />
                    <sequenceFlow sourceRef="approval" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """;
        repositoryService.createDeployment()
                .tenantId("tenant-a")
                .addString("tenant-start.bpmn20.xml", bpmnXml)
                .deploy();
        jdbcTemplate.update(
                "INSERT INTO t_process_config "
                        + "(process_definition_key, business_key_var) VALUES (?, ?)",
                processKey,
                "orderNo");

        String processInstanceId = workflowEngineService.startProcess(
                new StartProcessCommand(
                        processKey,
                        "starter-1",
                        "tenant-a",
                        Map.of(
                                "orderNo", "ORDER-TENANT-1",
                                "approver", "approver-1")));

        ProcessInstance processInstance = runtimeService
                .createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        assertNotNull(processInstance);
        assertEquals("tenant-a", processInstance.getTenantId());
        assertEquals(
                "starter-1",
                historyService.createHistoricProcessInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .singleResult()
                        .getStartUserId());
        assertEquals(
                "starter-1",
                runtimeService.getVariable(processInstanceId, "initiator"));
        assertEquals(
                "tenant-a",
                runtimeService.getVariable(processInstanceId, "tenantId"));
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        assertEquals("approver-1", task.getAssignee());
    }
}
