package com.guo.workflow.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.repository.ActionConfigRepository;
import com.guo.workflow.service.CancelProcessCommand;
import com.guo.workflow.service.ReturnTaskCommand;
import com.guo.workflow.service.WorkflowOperationService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowOperationIntegrationTest {

    @Autowired
    private ApprovalProcessPublisher publisher;
    @Autowired
    private WorkflowOperationService operationService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private ActionConfigRepository actionConfigRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void returnsWholeCountersignNodeOnlyToConfiguredTarget() {
        PublishedApprovalProcess published = deploy("returnApproval");
        configureDeleteCompensation(published);
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "returnApproval",
                "ORDER-RETURN",
                Map.of(
                        "departmentApprover", "dept-user",
                        "financeApprovers", List.of("finance-a", "finance-b")));
        Task department = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .taskDefinitionKey("departmentApproval")
                .singleResult();
        taskService.complete(department.getId());

        List<Task> financeTasks = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .list();
        assertEquals(2, financeTasks.size());
        assertEquals(
                "departmentApproval",
                operationService.allowedReturnTargets(
                                financeTasks.get(0).getId())
                        .get(0)
                        .activityId());

        assertThrows(
                FlowableActionException.class,
                () -> operationService.returnTask(new ReturnTaskCommand(
                        financeTasks.get(0).getId(),
                        "end",
                        "illegal target")));

        operationService.returnTask(new ReturnTaskCommand(
                financeTasks.get(0).getId(),
                "departmentApproval",
                "资料不完整"));

        Task returnedDepartment = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .taskDefinitionKey("departmentApproval")
                .singleResult();
        assertNotNull(returnedDepartment);
        assertEquals(
                0,
                taskService.createTaskQuery()
                        .processInstanceId(instance.getId())
                        .taskDefinitionKey("financeApproval")
                        .count());
        assertEquals(
                "资料不完整",
                runtimeService.getVariable(
                        instance.getId(),
                        "returnComment"));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_async_task WHERE business_key = ?",
                Integer.class,
                "ORDER-RETURN") > 0);
        assertTrue(hasOperation("ORDER-RETURN", "RETURN"));

        taskService.complete(returnedDepartment.getId());
        assertEquals(
                2,
                taskService.createTaskQuery()
                        .processInstanceId(instance.getId())
                        .taskDefinitionKey("financeApproval")
                        .count());
    }

    @Test
    void cancelsProcessAndRetainsHistoricInstance() {
        PublishedApprovalProcess published = deploy("cancelApproval");
        configureDeleteCompensation(published);
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "cancelApproval",
                "ORDER-CANCEL",
                Map.of(
                        "departmentApprover", "dept-user",
                        "financeApprovers", List.of("finance-a")));
        Task department = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        taskService.complete(department.getId());

        operationService.cancelProcess(new CancelProcessCommand(
                instance.getId(),
                "客户取消申请"));

        assertNull(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId())
                .singleResult());
        HistoricProcessInstance historic = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        assertNotNull(historic);
        assertNotNull(historic.getEndTime());
        assertTrue(historic.getDeleteReason().contains("客户取消申请"));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_async_task WHERE business_key = ?",
                Integer.class,
                "ORDER-CANCEL") > 0);
        assertTrue(hasOperation("ORDER-CANCEL", "CANCEL"));
    }

    @Test
    void failedSynchronousCompensationBlocksReturn() {
        PublishedApprovalProcess published = deploy("blockedReturnApproval");
        actionConfigRepository.upsertGenerated(
                published.processKey(),
                published.processDefinitionId(),
                "financeApproval",
                com.guo.workflow.contract.ActionTriggerScope.TASK,
                "delete",
                """
                        <actions>
                          <action event="delete" type="job" doType="script" sync="true">
                            <script>throw new IllegalStateException("blocked")</script>
                          </action>
                        </actions>
                        """);
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "blockedReturnApproval",
                "ORDER-BLOCKED",
                Map.of(
                        "departmentApprover", "dept-user",
                        "financeApprovers", List.of("finance-a")));
        Task department = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        taskService.complete(department.getId());
        Task finance = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult();

        assertThrows(
                FlowableActionException.class,
                () -> operationService.returnTask(new ReturnTaskCommand(
                        finance.getId(),
                        "departmentApproval",
                        "should be blocked")));

        assertEquals(
                1,
                taskService.createTaskQuery()
                        .processInstanceId(instance.getId())
                        .taskDefinitionKey("financeApproval")
                        .count());
        assertEquals(
                0,
                taskService.createTaskQuery()
                        .processInstanceId(instance.getId())
                        .taskDefinitionKey("departmentApproval")
                        .count());
    }

    private PublishedApprovalProcess deploy(String processKey) {
        return publisher.publish("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:guo="http://guo.workflow/schema/approval"
                             targetNamespace="http://guo.workflow/test">
                  <process id="%s" name="退回测试" isExecutable="true">
                    <startEvent id="start" />
                    <sequenceFlow sourceRef="start" targetRef="departmentApproval" />
                    <userTask id="departmentApproval" name="部门审批"
                              flowable:assignee="${departmentApprover}" />
                    <sequenceFlow sourceRef="departmentApproval" targetRef="financeApproval" />
                    <userTask id="financeApproval" name="财务会签">
                      <extensionElements>
                        <guo:approval mode="COUNTERSIGN"
                                      completionPolicy="ALL"
                                      approvers="${financeApprovers}">
                          <guo:return to="departmentApproval" />
                        </guo:approval>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow sourceRef="financeApproval" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """.formatted(processKey), null);
    }

    private void configureDeleteCompensation(
            PublishedApprovalProcess published) {
        actionConfigRepository.upsertGenerated(
                published.processKey(),
                published.processDefinitionId(),
                "financeApproval",
                com.guo.workflow.contract.ActionTriggerScope.TASK,
                "delete",
                """
                        <actions>
                          <action event="delete" type="job" doType="message" sync="false">
                            <message channel="dingtalk" to="#{currentApprover}">
                              <content>退回/取消操作 #{$attrs.operation}</content>
                            </message>
                          </action>
                        </actions>
                        """);
    }

    private boolean hasOperation(String businessKey, String operation) {
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload_json FROM t_async_task WHERE business_key = ?",
                String.class,
                businessKey);
        return payloads.stream().anyMatch(payload -> {
            try {
                JsonNode root = objectMapper.readTree(payload);
                return operation.equals(root.path("invocation")
                        .path("context")
                        .path("attributes")
                        .path("operation")
                        .asText());
            } catch (Exception exception) {
                return false;
            }
        });
    }
}
