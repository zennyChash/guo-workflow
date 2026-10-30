package com.guo.workflow;

import com.guo.workflow.approval.ApprovalProcessPublisher;
import com.guo.workflow.approval.PublishedApprovalProcess;
import com.guo.workflow.async.AsyncTaskConsumer;
import com.guo.workflow.message.MessageRequest;
import com.guo.workflow.message.MessageSender;
import com.guo.workflow.mirror.ProcessInstanceMirror;
import com.guo.workflow.service.StartProcessCommand;
import com.guo.workflow.service.WorkflowEngineService;
import com.guo.workflow.service.WorkflowQueryService;
import com.guo.workflow.task.CompleteTaskCommand;
import com.guo.workflow.task.TaskView;
import com.guo.workflow.task.WorkflowTaskService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Import(MilestoneEndToEndIntegrationTest.CaptureConfiguration.class)
class MilestoneEndToEndIntegrationTest {

    private static final String PROCESS_KEY = "milestoneApproval";
    private static final String TENANT_ID = "tenant-milestone";
    private static final String BUSINESS_KEY = "ORDER-MILESTONE-1";
    private static final List<MessageRequest> MESSAGES = new CopyOnWriteArrayList<>();

    @Autowired
    private ApprovalProcessPublisher publisher;
    @Autowired
    private WorkflowEngineService workflowEngineService;
    @Autowired
    private WorkflowTaskService workflowTaskService;
    @Autowired
    private WorkflowQueryService queryService;
    @Autowired
    private AsyncTaskConsumer asyncTaskConsumer;
    @Autowired
    private TaskService taskService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void completesApprovalNotificationAndMirrorChain() {
        MESSAGES.clear();
        jdbcTemplate.update(
                "DELETE FROM t_process_config WHERE process_definition_key = ?",
                PROCESS_KEY);
        jdbcTemplate.update(
                "INSERT INTO t_process_config "
                        + "(process_definition_key, business_key_var) VALUES (?, ?)",
                PROCESS_KEY,
                "orderNo");

        PublishedApprovalProcess published = publisher.publish("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:guo="http://guo.workflow/schema/approval"
                             targetNamespace="http://guo.workflow/test">
                  <process id="milestoneApproval" name="里程碑审批" isExecutable="true">
                    <startEvent id="start" />
                    <sequenceFlow sourceRef="start" targetRef="finance" />
                    <userTask id="finance" name="财务会签">
                      <extensionElements>
                        <guo:approval mode="COUNTERSIGN"
                                      completionPolicy="ALL"
                                      approvers="${financeApprovers}">
                          <guo:notification trigger="assignment"
                                            channel="capture"
                                            to="#{currentApprover}">
                            <guo:content>待审批 #{businessKey}</guo:content>
                          </guo:notification>
                          <guo:notification trigger="node-completed"
                                            channel="capture"
                                            to="#{initiator}">
                            <guo:content>会签完成 #{businessKey}</guo:content>
                          </guo:notification>
                        </guo:approval>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow sourceRef="finance" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """, TENANT_ID);

        String processInstanceId = workflowEngineService.startProcess(
                new StartProcessCommand(
                        PROCESS_KEY,
                        "starter-1",
                        TENANT_ID,
                        Map.of(
                                "orderNo", BUSINESS_KEY,
                                "financeApprovers", List.of("finance-a", "finance-b"))));

        List<Task> financeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .list();
        assertEquals(2, financeTasks.size());
        for (Task task : financeTasks) {
            TaskView view = workflowTaskService.findTask(task.getId())
                    .orElseThrow();
            workflowTaskService.completeTask(new CompleteTaskCommand(
                    view.taskId(),
                    view.assigneeId(),
                    "同意",
                    Map.of("financeApproved", true)));
        }

        awaitAsyncCompletion();
        assertEquals(3, MESSAGES.size());
        assertTrue(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult() == null);
        ProcessInstanceMirror mirror = queryService
                .findProcess(TENANT_ID, processInstanceId)
                .orElseThrow();
        assertEquals("COMPLETED", mirror.processStatus());
        assertEquals(BUSINESS_KEY, mirror.businessKey());
        assertEquals(0, mirror.activeTaskCount());
        assertTrue(queryService.findActivity(TENANT_ID, processInstanceId).stream()
                .anyMatch(event -> "START".equals(event.eventType())));
        assertTrue(queryService.findActivity(TENANT_ID, processInstanceId).stream()
                .anyMatch(event -> "END".equals(event.eventType())));
    }

    private void awaitAsyncCompletion() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            asyncTaskConsumer.consumePendingTasks();
            Integer unresolved = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_async_task "
                            + "WHERE business_key = ? "
                            + "AND status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT')",
                    Integer.class,
                    BUSINESS_KEY);
            if (unresolved != null && unresolved == 0 && MESSAGES.size() >= 3) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for async tasks", exception);
            }
        }
        throw new AssertionError("Async action chain did not complete within 10 seconds");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfiguration {

        @Bean
        MessageSender captureMessageSender() {
            return new MessageSender() {
                @Override
                public String channel() {
                    return "capture";
                }

                @Override
                public void send(MessageRequest message) {
                    MESSAGES.add(message);
                }
            };
        }
    }
}
