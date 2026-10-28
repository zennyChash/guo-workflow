package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.InstructionMsg;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.async.InstructionPublisher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultActionDispatcherTest {

    private static final String XML = """
            <actions>
              <action id="notify-sales"
                      event="complete"
                      type="job"
                      doType="message"
                      sync="false">
                <target>dingtalk</target>
                <template_id>order-approved</template_id>
                <template>Order #{businessKey} approved</template>
              </action>
              <action id="approve-order"
                      event="complete"
                      type="job"
                      doType="sql"
                      sync="true"
                      phase="before-commit"
                      datasource="biz_db">
                <sql>UPDATE t_order SET status = 'APPROVED'</sql>
              </action>
            </actions>
            """;

    @Test
    void executesSynchronousActionsBeforePublishingInstructions() {
        List<String> operations = new ArrayList<>();
        BusinessAction sqlAction = actionAddingTo(operations);
        BusinessActionRegistry registry = (type, doType) -> {
            assertEquals(ActionType.JOB, type);
            assertEquals(ActionDoType.SQL, doType);
            return sqlAction;
        };
        InstructionPublisher publisher = message ->
                operations.add("publish");
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                registry,
                publisher);

        dispatcher.dispatch(invocation(XML));

        assertEquals(
                List.of("execute:approve-order", "publish"),
                operations);
    }

    @Test
    void buildsInstructionMessageFromInvocationAndDefinition() {
        List<InstructionMsg> published = new ArrayList<>();
        String xml = """
                <actions>
                  <action id="notify-sales"
                          event="complete"
                          type="job"
                          doType="message"
                          sync="false">
                    <target>dingtalk</target>
                    <template_id>order-approved</template_id>
                    <template>Order #{businessKey} approved</template>
                  </action>
                </actions>
                """;
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                (type, doType) -> {
                    throw new AssertionError("Async actions must not execute synchronously");
                },
                published::add);

        ActionInvocation invocation = invocation(xml);
        dispatcher.dispatch(invocation);

        assertEquals(1, published.size());
        InstructionMsg message = published.get(0);
        assertFalse(message.taskId().isBlank());
        assertEquals("ORDER-100", message.businessKey());
        assertEquals(ActionEvent.COMPLETE, message.event());
        assertEquals(ActionType.JOB, message.type());
        assertEquals(ActionDoType.MESSAGE, message.definition().doType());
        assertSame(invocation, message.invocation());
        assertEquals("notify-sales", message.definition().actionId());
        assertEquals("dingtalk", message.definition().nodes().get(0).text());
    }

    @Test
    void wrapsSynchronousFailureAndDoesNotPublishInstruction() {
        List<InstructionMsg> published = new ArrayList<>();
        BusinessAction failingAction = new BusinessAction() {
            @Override
            public ActionType type() {
                return ActionType.JOB;
            }

            @Override
            public ActionDoType doType() {
                return ActionDoType.SQL;
            }

            @Override
            public void execute(ActionInvocation invocation, ActionDefinition definition) {
                throw new IllegalStateException("database unavailable");
            }
        };
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                (type, doType) -> failingAction,
                published::add);

        FlowableActionException exception = assertThrows(
                FlowableActionException.class,
                () -> dispatcher.dispatch(invocation(XML)));

        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertTrue(published.isEmpty());
    }

    @Test
    void publishesAsyncScriptWithoutResolvingAnExecutor() {
        List<InstructionMsg> published = new ArrayList<>();
        String xml = """
                <actions>
                  <action id="export-file"
                          event="complete"
                          type="job"
                          doType="script"
                          sync="false">
                    <script>return orderId != null</script>
                  </action>
                </actions>
                """;
        DefaultActionDispatcher dispatcher = new DefaultActionDispatcher(
                new DefaultActionDefinitionParser(),
                (type, doType) -> {
                    throw new AssertionError("Async actions must not resolve an executor");
                },
                published::add);

        dispatcher.dispatch(invocation(xml));

        assertEquals(1, published.size());
        assertEquals(ActionType.JOB, published.get(0).type());
        assertEquals(ActionDoType.SCRIPT, published.get(0).definition().doType());
        assertEquals("export-file", published.get(0).definition().actionId());
        assertEquals("script", published.get(0).definition().nodes().get(0).name());
    }

    private BusinessAction actionAddingTo(List<String> operations) {
        return new BusinessAction() {
            @Override
            public ActionType type() {
                return ActionType.JOB;
            }

            @Override
            public ActionDoType doType() {
                return ActionDoType.SQL;
            }

            @Override
            public void execute(ActionInvocation invocation, ActionDefinition definition) {
                operations.add("execute:" + definition.actionId());
            }
        };
    }

    private ActionInvocation invocation(String xml) {
        ActionContext context = new ActionContext(
                "ORDER-100",
                "order-approval",
                "PROCESS-1",
                "TASK-1",
                "manager-approval",
                Map.of("orderStatus", "APPROVING"),
                Map.of("channel", "source"));
        return new ActionInvocation(ActionEvent.COMPLETE, context, xml);
    }
}
