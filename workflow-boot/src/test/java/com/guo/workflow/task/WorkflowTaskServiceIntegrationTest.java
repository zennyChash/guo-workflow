package com.guo.workflow.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guo.workflow.repository.ActionConfigRepository;
import com.guo.workflow.contract.ActionTriggerScope;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Import(WorkflowTaskServiceIntegrationTest.TestResolverConfiguration.class)
class WorkflowTaskServiceIntegrationTest {

    private static final String PROCESS_KEY = "taskFacadeProcess";

    @Autowired
    private WorkflowTaskService workflowTaskService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ActionConfigRepository actionConfigRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void queriesClaimsTransfersAndCompletesTasksThroughFacade() {
        ProcessDefinition definition = deployProcess();
        configureCompleteAction(definition);
        ProcessInstance process = runtimeService.startProcessInstanceByKey(
                PROCESS_KEY,
                "TASK-FACADE-1",
                Map.of("nextApprover", "carol"));

        List<TaskView> aliceTodo = workflowTaskService.findTodoTasks(
                null,
                "alice",
                20);
        assertEquals(1, aliceTodo.size());
        String candidateTaskId = aliceTodo.get(0).taskId();
        assertEquals("candidateTask", aliceTodo.get(0).activityId());

        workflowTaskService.claimTask(new ClaimTaskCommand(
                candidateTaskId,
                "alice"));
        assertEquals(
                "alice",
                workflowTaskService.findTask(candidateTaskId)
                        .orElseThrow()
                        .assigneeId());
        workflowTaskService.unclaimTask(new UnclaimTaskCommand(
                candidateTaskId,
                "alice"));

        workflowTaskService.claimTask(new ClaimTaskCommand(
                candidateTaskId,
                "bob"));
        workflowTaskService.transferTask(new TransferTaskCommand(
                candidateTaskId,
                "bob",
                "dave"));
        assertThrows(
                FlowableActionException.class,
                () -> workflowTaskService.transferTask(new TransferTaskCommand(
                        candidateTaskId,
                        "bob",
                        "alice")));

        workflowTaskService.completeTask(new CompleteTaskCommand(
                candidateTaskId,
                "dave",
                "提交到下一节点",
                Map.of("departmentApproved", true)));

        Task assigned = taskService.createTaskQuery()
                .processInstanceId(process.getId())
                .taskDefinitionKey("assignedTask")
                .singleResult();
        assertNotNull(assigned);
        assertEquals("carol", assigned.getAssignee());
        assertThrows(
                FlowableActionException.class,
                () -> workflowTaskService.completeTask(new CompleteTaskCommand(
                        assigned.getId(),
                        "alice",
                        "not assignee",
                        Map.of())));

        workflowTaskService.completeTask(new CompleteTaskCommand(
                assigned.getId(),
                "carol",
                "财务同意",
                Map.of("financeApproved", true)));

        assertTrue(workflowTaskService.findTask(assigned.getId()).isEmpty());
        assertFalse(taskService.getTaskComments(assigned.getId()).isEmpty());
        assertTrue(hasOperator("TASK-FACADE-1", "carol"));
    }

    @Test
    void resolvesHostGroupsForTodoQueriesAndClaims() {
        deployGroupProcess();
        ProcessInstance process = runtimeService.startProcessInstanceByKey(
                "groupCandidateProcess",
                "TASK-GROUP-1");

        List<TaskView> todo = workflowTaskService.findTodoTasks(
                null,
                "group-user",
                20);
        assertEquals(1, todo.size());
        assertEquals("groupCandidateTask", todo.get(0).activityId());

        workflowTaskService.claimTask(new ClaimTaskCommand(
                todo.get(0).taskId(),
                "group-user"));
        Task claimed = taskService.createTaskQuery()
                .processInstanceId(process.getId())
                .singleResult();
        assertEquals("group-user", claimed.getAssignee());
    }

    private ProcessDefinition deployProcess() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://guo.workflow/test">
                  <process id="taskFacadeProcess" name="任务门面测试" isExecutable="true">
                    <startEvent id="start" />
                    <sequenceFlow sourceRef="start" targetRef="candidateTask" />
                    <userTask id="candidateTask" name="候选任务"
                              flowable:candidateUsers="alice,bob" />
                    <sequenceFlow sourceRef="candidateTask" targetRef="assignedTask" />
                    <userTask id="assignedTask" name="财务审批"
                              flowable:assignee="${nextApprover}">
                      <extensionElements>
                        <flowable:taskListener event="complete"
                                               delegateExpression="${actionTaskListener}" />
                      </extensionElements>
                    </userTask>
                    <sequenceFlow sourceRef="assignedTask" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """;
        repositoryService.createDeployment()
                .addString("task-facade.bpmn20.xml", bpmnXml)
                .deploy();
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(PROCESS_KEY)
                .latestVersion()
                .singleResult();
    }

    private void deployGroupProcess() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://guo.workflow/test">
                  <process id="groupCandidateProcess" name="候选组测试" isExecutable="true">
                    <startEvent id="start" />
                    <sequenceFlow sourceRef="start" targetRef="groupCandidateTask" />
                    <userTask id="groupCandidateTask" name="财务组审批"
                              flowable:candidateGroups="finance" />
                    <sequenceFlow sourceRef="groupCandidateTask" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """;
        repositoryService.createDeployment()
                .addString("group-candidate.bpmn20.xml", bpmnXml)
                .deploy();
    }

    private void configureCompleteAction(ProcessDefinition definition) {
        actionConfigRepository.upsertGenerated(
                definition.getKey(),
                definition.getId(),
                "assignedTask",
                ActionTriggerScope.TASK,
                "complete",
                """
                        <actions>
                          <action event="complete" type="job" doType="message" sync="false">
                            <message channel="dingtalk" to="#{$attrs.operatorId}">
                              <content>#{approvalComment}</content>
                            </message>
                          </action>
                        </actions>
                        """);
    }

    private boolean hasOperator(String businessKey, String operatorId) {
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload_json FROM t_async_task WHERE business_key = ?",
                String.class,
                businessKey);
        return payloads.stream().anyMatch(payload -> {
            try {
                JsonNode root = objectMapper.readTree(payload);
                return operatorId.equals(root.path("invocation")
                        .path("context")
                        .path("attributes")
                        .path("operatorId")
                        .asText());
            } catch (Exception exception) {
                return false;
            }
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestResolverConfiguration {

        @Bean
        WorkflowGroupResolver workflowGroupResolver() {
            return operatorId -> "group-user".equals(operatorId)
                    ? List.of("finance")
                    : List.of();
        }
    }
}
