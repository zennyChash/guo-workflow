package com.guo.workflow.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guo.workflow.business.BusinessOperationHandler;
import com.guo.workflow.business.BusinessOperationRequest;
import com.guo.workflow.business.BusinessOperationResult;
import com.guo.workflow.business.DefaultBusinessOperationRegistry;
import com.guo.workflow.business.LocalBusinessOperationGateway;
import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.InstructionMsg;
import com.guo.workflow.dispatcher.DefaultActionDefinitionParser;
import com.guo.workflow.dispatcher.DefaultActionDispatcher;
import com.guo.workflow.dispatcher.BusinessActionRegistry;
import com.guo.workflow.executor.ExportAction;
import com.guo.workflow.executor.BusinessOperationAction;
import com.guo.workflow.executor.MessageAction;
import com.guo.workflow.export.FileExportRequest;
import com.guo.workflow.export.FileExportGateway;
import com.guo.workflow.message.MessageRequest;
import com.guo.workflow.message.MessageSender;
import com.guo.workflow.mirror.ProcessBusinessLinkRepository;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncTaskFlowIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:"
                        + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        jdbcTemplate = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        jdbcTemplate.execute("""
                CREATE TABLE t_async_task (
                    task_id VARCHAR(64) PRIMARY KEY,
                    business_key VARCHAR(128) NOT NULL,
                    tenant_id VARCHAR(64),
                    action_type VARCHAR(32) NOT NULL,
                    payload_json CLOB NOT NULL,
                    result_json CLOB,
                    status VARCHAR(20) NOT NULL,
                    claim_token VARCHAR(64),
                    retry_count INT DEFAULT 0 NOT NULL,
                    max_retry_count INT DEFAULT 5 NOT NULL,
                    next_retry_time TIMESTAMP,
                    error_msg CLOB,
                    create_time TIMESTAMP NOT NULL,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE t_process_business_link (
                    link_id VARCHAR(64) PRIMARY KEY,
                    process_instance_id VARCHAR(64) NOT NULL,
                    tenant_id VARCHAR(64),
                    system_code VARCHAR(64),
                    business_type VARCHAR(64),
                    business_id VARCHAR(128),
                    business_key VARCHAR(128),
                    business_title VARCHAR(255),
                    relation_type VARCHAR(32),
                    business_sync_status VARCHAR(32),
                    business_url VARCHAR(1000),
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
    }

    @Test
    void dispatchesPersistsConsumesAndCompletesInstruction() throws Exception {
        MessageAction messageAction = new MessageAction(
                List.of(new NoOpMessageSender("dingtalk")),
                1);
        BusinessActionRegistry registry = (type, doType) -> {
            assertEquals(ActionType.JOB, type);
            assertEquals(ActionDoType.MESSAGE, doType);
            return messageAction;
        };
        JdbcInstructionPublisher publisher = new JdbcInstructionPublisher(
                jdbcTemplate,
                objectMapper);
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                registry,
                publisher);

        dispatcher.dispatch(invocation());

        Map<String, Object> pending = jdbcTemplate.queryForMap(
                "SELECT * FROM t_async_task");
        assertEquals("PENDING", pending.get("status"));
        assertEquals("JOB", pending.get("action_type"));
        assertEquals("tenant-1", pending.get("tenant_id"));
        assertNotNull(pending.get("task_id"));

        String payloadJson = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM t_async_task",
                String.class);
        assertNotNull(payloadJson);
        assertTrue(payloadJson.contains("\"taskId\""));
        InstructionMsg persistedMessage = objectMapper.readValue(
                payloadJson,
                InstructionMsg.class);
        assertEquals("ORDER-100", persistedMessage.businessKey());
        assertEquals(
                "dingtalk",
                persistedMessage.definition().nodes().get(0).attributes().get("channel"));

        AsyncTaskConsumer consumer = new AsyncTaskConsumer(
                jdbcTemplate,
                objectMapper,
                registry,
                new SyncTaskExecutor(),
                10,
                2,
                300,
                0,
                600);
        consumer.consumePendingTasks();

        Map<String, Object> completed = jdbcTemplate.queryForMap(
                "SELECT * FROM t_async_task");
        assertEquals("SUCCESS", completed.get("status"));
        assertEquals(0, ((Number) completed.get("retry_count")).intValue());
        assertFalse(String.valueOf(completed.get("task_id")).isBlank());
        assertNull(completed.get("claim_token"));
        assertNull(completed.get("next_retry_time"));
        messageAction.destroy();
    }

    @Test
    void exportsFileAsynchronouslyAndCompletesInstruction() {
        AtomicReference<FileExportRequest> received = new AtomicReference<>();
        FileExportGateway exportGateway = new FileExportGateway() {
            @Override
            public String export(FileExportRequest request) {
                received.set(request);
                return "ticket-123";
            }
        };
        BusinessActionRegistry registry = (type, doType) -> {
            assertEquals(ActionType.JOB, type);
            assertEquals(ActionDoType.EXPORT, doType);
            return new ExportAction(exportGateway);
        };
        JdbcInstructionPublisher publisher = new JdbcInstructionPublisher(
                jdbcTemplate,
                objectMapper);
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                registry,
                publisher);

        dispatcher.dispatch(invocation("""
                <actions>
                  <action id="export-orders"
                          event="complete"
                          type="job"
                          doType="export"
                          sync="false">
                    <target fileType="csv"
                            expTaskId="task_order_export_csv" />
                  </action>
                </actions>
                """));

        RuntimeService runtimeService = mock(RuntimeService.class);
        ProcessInstanceQuery processInstanceQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processInstanceQuery);
        when(processInstanceQuery.processInstanceId("PROCESS-1"))
                .thenReturn(processInstanceQuery);
        when(processInstanceQuery.count()).thenReturn(1L);
        AsyncTaskConsumer consumer = new AsyncTaskConsumer(
                jdbcTemplate,
                objectMapper,
                registry,
                new SyncTaskExecutor(),
                10,
                2,
                300,
                0,
                600);
        consumer.setRuntimeService(runtimeService);
        consumer.consumePendingTasks();

        assertNotNull(received.get());
        assertEquals("csv", received.get().fileType());
        assertEquals("ORDER-100", received.get().context().businessKey());
        String resultJson = jdbcTemplate.queryForObject(
                "SELECT result_json FROM t_async_task",
                String.class);
        assertNotNull(resultJson);
        assertTrue(resultJson.contains("exportTicket_manager_approval_export_orders"));
        assertTrue(resultJson.contains("ticket-123"));
        assertTrue(resultJson.contains(
                "exportUniqueBizKey_manager_approval_export_orders"));
        assertTrue(resultJson.contains("WF_EXPORT_V1_"));
        verify(runtimeService).setVariables(
                eq("PROCESS-1"),
                argThat(variables ->
                        "ticket-123".equals(
                                variables.get(
                                        "exportTicket_manager_approval_export_orders"))
                                && String.valueOf(
                                        variables.get(
                                                "exportUniqueBizKey_manager_approval_export_orders"))
                                        .startsWith("WF_EXPORT_V1_")));
        assertEquals(
                "SUCCESS",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM t_async_task",
                        String.class));
    }

    @Test
    void invokesBusinessOperationAsynchronouslyAndPersistsLink() {
        AtomicReference<BusinessOperationRequest> received = new AtomicReference<>();
        BusinessOperationHandler handler = new BusinessOperationHandler() {
            @Override
            public String actionKey() {
                return "purchase.createDraft";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public BusinessOperationResult execute(BusinessOperationRequest request) {
                received.set(request);
                return BusinessOperationResult.success(
                        "ERP",
                        "DRAFT-1001",
                        "PURCHASE_DRAFT",
                        "采购草稿",
                        "/purchase-drafts/DRAFT-1001",
                        "created",
                        Map.of());
            }
        };
        BusinessOperationAction action = new BusinessOperationAction(
                new LocalBusinessOperationGateway(
                        new DefaultBusinessOperationRegistry(List.of(handler))),
                new ProcessBusinessLinkRepository(jdbcTemplate));
        BusinessActionRegistry registry = (type, doType) -> {
            assertEquals(ActionType.JOB, type);
            assertEquals(ActionDoType.BUSINESS, doType);
            return action;
        };
        JdbcInstructionPublisher publisher = new JdbcInstructionPublisher(
                jdbcTemplate,
                objectMapper);
        new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                registry,
                publisher)
                .dispatch(invocation("""
                        <actions>
                          <action id="create-draft"
                                  event="complete"
                                  type="job"
                                  doType="business"
                                  sync="false">
                            <business actionKey="purchase.createDraft" version="1">
                              <param name="applicationId" value="#{businessKey}" />
                            </business>
                          </action>
                        </actions>
                        """));

        new AsyncTaskConsumer(
                jdbcTemplate,
                objectMapper,
                registry,
                new SyncTaskExecutor(),
                10,
                2,
                300,
                0,
                600)
                .consumePendingTasks();

        assertNotNull(received.get());
        assertEquals("ORDER-100", received.get().businessKey());
        assertEquals(
                "SUCCESS",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM t_async_task",
                        String.class));
        assertEquals(
                "DRAFT-1001",
                jdbcTemplate.queryForObject(
                        "SELECT business_id FROM t_process_business_link",
                        String.class));
        String resultJson = jdbcTemplate.queryForObject(
                "SELECT result_json FROM t_async_task",
                String.class);
        assertTrue(resultJson.contains("businessOperationResultId_manager_approval_create_draft"));
    }

    @Test
    void marksTaskForRetryWhenActionFails() {
        MessageAction messageAction = new MessageAction(
                List.of(new FailingMessageSender("dingtalk")),
                1);
        BusinessActionRegistry registry = (type, doType) -> messageAction;
        JdbcInstructionPublisher publisher = new JdbcInstructionPublisher(
                jdbcTemplate,
                objectMapper);
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                registry,
                publisher);

        dispatcher.dispatch(invocation("""
                <actions>
                  <action id="fail-notification"
                          event="complete"
                          type="job"
                          doType="message"
                          sync="false">
                    <message channel="dingtalk" to="#{salesUserId}">
                      <content>failure test</content>
                    </message>
                  </action>
                </actions>
                """));

        AsyncTaskConsumer consumer = new AsyncTaskConsumer(
                jdbcTemplate,
                objectMapper,
                registry,
                new SyncTaskExecutor(),
                10,
                2,
                300,
                0,
                600);
        consumer.consumePendingTasks();
        consumer.consumePendingTasks();

        Map<String, Object> failed = jdbcTemplate.queryForMap(
                "SELECT * FROM t_async_task");
        assertEquals("RETRY_WAIT", failed.get("status"));
        assertEquals(1, ((Number) failed.get("retry_count")).intValue());
        assertNotNull(failed.get("error_msg"));
        assertTrue(String.valueOf(failed.get("error_msg")).length() <= 2000);
        assertNotNull(failed.get("next_retry_time"));
        messageAction.destroy();
    }

    @Test
    void marksTaskAsDeadWhenRetryLimitIsReached() {
        BusinessAction failingAction = new BusinessAction() {
            @Override
            public ActionType type() {
                return ActionType.JOB;
            }

            @Override
            public ActionDoType doType() {
                return ActionDoType.MESSAGE;
            }

            @Override
            public void execute(ActionInvocation invocation, ActionDefinition definition) {
                throw new IllegalStateException("permanent failure");
            }
        };
        BusinessActionRegistry registry = (type, doType) -> failingAction;
        JdbcInstructionPublisher publisher = new JdbcInstructionPublisher(
                jdbcTemplate,
                objectMapper);
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                registry,
                publisher);
        dispatcher.dispatch(invocation());
        jdbcTemplate.update("UPDATE t_async_task SET max_retry_count = 0");

        AsyncTaskConsumer consumer = new AsyncTaskConsumer(
                jdbcTemplate,
                objectMapper,
                registry,
                new SyncTaskExecutor(),
                10,
                2,
                300,
                0,
                600);
        consumer.consumePendingTasks();

        Map<String, Object> dead = jdbcTemplate.queryForMap(
                "SELECT * FROM t_async_task");
        assertEquals("DEAD", dead.get("status"));
        assertEquals(0, ((Number) dead.get("retry_count")).intValue());
        assertNull(dead.get("next_retry_time"));
        assertNotNull(dead.get("error_msg"));
    }

    @Test
    void claimedTaskIsNotSubmittedByAnotherConsumer() {
        AtomicInteger executions = new AtomicInteger();
        BusinessAction countingAction = new BusinessAction() {
            @Override
            public ActionType type() {
                return ActionType.JOB;
            }

            @Override
            public ActionDoType doType() {
                return ActionDoType.MESSAGE;
            }

            @Override
            public void execute(ActionInvocation invocation, ActionDefinition definition) {
                executions.incrementAndGet();
            }
        };
        BusinessActionRegistry registry = (type, doType) -> countingAction;
        JdbcInstructionPublisher publisher = new JdbcInstructionPublisher(
                jdbcTemplate,
                objectMapper);
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                registry,
                publisher);
        dispatcher.dispatch(invocation());

        CapturingTaskExecutor firstExecutor = new CapturingTaskExecutor();
        CapturingTaskExecutor secondExecutor = new CapturingTaskExecutor();
        new AsyncTaskConsumer(
                jdbcTemplate,
                objectMapper,
                registry,
                firstExecutor,
                10,
                2,
                300,
                0,
                600)
                .consumePendingTasks();
        new AsyncTaskConsumer(
                jdbcTemplate,
                objectMapper,
                registry,
                secondExecutor,
                10,
                2,
                300,
                0,
                600)
                .consumePendingTasks();

        assertEquals(1, firstExecutor.size());
        assertEquals(0, secondExecutor.size());
        firstExecutor.runAll();
        assertEquals(1, executions.get());
        assertEquals(
                "SUCCESS",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM t_async_task",
                        String.class));
    }

    @Test
    void recoversStaleProcessingTask() {
        MessageAction messageAction = new MessageAction(
                List.of(new NoOpMessageSender("dingtalk")),
                1);
        BusinessActionRegistry registry = (type, doType) -> messageAction;
        JdbcInstructionPublisher publisher = new JdbcInstructionPublisher(
                jdbcTemplate,
                objectMapper);
        new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                registry,
                publisher)
                .dispatch(invocation());
        jdbcTemplate.update("""
                UPDATE t_async_task
                SET status = 'PROCESSING',
                    claim_token = 'stale-token',
                    update_time = DATEADD('MINUTE', -11, CURRENT_TIMESTAMP)
                """);

        new AsyncTaskConsumer(
                jdbcTemplate,
                objectMapper,
                registry,
                new SyncTaskExecutor(),
                10,
                2,
                300,
                0,
                600)
                .consumePendingTasks();

        assertEquals(
                "SUCCESS",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM t_async_task",
                        String.class));
        messageAction.destroy();
    }

    private ActionInvocation invocation() {
        return invocation("""
                <actions>
                  <action id="notify-sales"
                          event="complete"
                          type="job"
                          doType="message"
                          sync="false">
                    <message channel="dingtalk" to="#{salesUserId}">
                      <content>订单 #{businessKey} 已审批</content>
                    </message>
                  </action>
                </actions>
                """);
    }

    private ActionInvocation invocation(String xml) {
        ActionContext context = new ActionContext(
                "ORDER-100",
                "order-approval",
                "PROCESS-1",
                "TASK-1",
                "manager-approval",
                Map.of("orderStatus", "APPROVING", "salesUserId", "sales-1"),
                Map.of("channel", "source", "tenantId", "tenant-1"));
        return new ActionInvocation(ActionEvent.COMPLETE, context, xml);
    }

    private record NoOpMessageSender(String channel) implements MessageSender {

        @Override
        public void send(MessageRequest message) {
            // Test sender.
        }
    }

    private record FailingMessageSender(String channel) implements MessageSender {

        @Override
        public void send(MessageRequest message) throws Exception {
            throw new java.io.IOException("simulated channel failure");
        }
    }

    private static final class CapturingTaskExecutor implements TaskExecutor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        int size() {
            return tasks.size();
        }

        void runAll() {
            List.copyOf(tasks).forEach(Runnable::run);
            tasks.clear();
        }
    }
}
