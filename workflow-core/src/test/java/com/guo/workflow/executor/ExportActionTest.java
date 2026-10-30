package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.dispatcher.DefaultActionDefinitionParser;
import com.guo.workflow.export.FileExportRequest;
import com.guo.workflow.export.FileExportGateway;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportActionTest {

    private final DefaultActionDefinitionParser parser =
            new DefaultActionDefinitionParser();

    @Test
    void delegatesToExportModuleAndPassesWorkflowContext() {
        RecordingGateway gateway = new RecordingGateway(null);
        ExportAction action = new ExportAction(gateway);

        action.execute(invocation(), definition("""
                <actions>
                  <action id="export-orders"
                          event="complete"
                          type="job"
                          doType="export"
                          sync="false">
                    <target fileType="CSV"
                            expTaskId="task_order_export_csv" />
                  </action>
                </actions>
                """));

        FileExportRequest request = gateway.request();
        assertEquals("task_order_export_csv", request.expTaskId());
        assertEquals("CSV", request.fileType());
        assertEquals("export-orders", request.actionId());
        assertEquals("order-approval", request.processDefinitionKey());
        assertEquals("manager-approval", request.activityId());
        assertEquals("ORDER-100", request.context().businessKey());
        assertEquals("PROCESS-1", request.processInstanceId());
        assertEquals("TASK-1", request.workflowTaskId());
        assertEquals("tenant-1", request.tenantId());
        assertEquals("tenant-1", request.context().attributes().get("tenantId"));
        assertEquals("complete", request.event());
        assertEquals(
                "exportTicket_manager_approval_export_orders",
                request.resultVariableName());
        assertTrue(request.uniqueBizKey().startsWith("WF_EXPORT_V1_"));
        assertEquals(
                "ticket-123",
                request.context().variables().get(request.resultVariableName()));
        assertEquals(
                request.uniqueBizKey(),
                request.context().variables().get(
                        "exportUniqueBizKey_manager_approval_export_orders"));
        assertFalse(request.uniqueBizKey().isBlank());
        assertEquals(
                "task_order_export_csv",
                request.targetAttributes().get("expTaskId"));
    }

    @Test
    void rejectsMissingExportModule() {
        ExportAction action = new ExportAction((FileExportGateway) null);

        assertThrows(
                FlowableActionException.class,
                () -> action.execute(invocation(), validDefinition()));
    }

    @Test
    void wrapsExportModuleFailure() {
        IOException failure = new IOException("disk unavailable");
        ExportAction action = new ExportAction(new RecordingGateway(failure));

        FlowableActionException exception = assertThrows(
                FlowableActionException.class,
                () -> action.execute(invocation(), validDefinition()));

        assertSame(failure, exception.getCause());
    }

    @Test
    void rejectsBlankExportTicket() {
        ExportAction action = new ExportAction(
                new RecordingGateway(null, " "));

        assertThrows(
                FlowableActionException.class,
                () -> action.execute(invocation(), validDefinition()));
    }

    @Test
    void uniqueBizKeyIsStableForRetryAndChangesForNewTaskInstance() {
        RecordingGateway first = new RecordingGateway(null);
        new ExportAction(first).execute(invocation("TASK-1"), validDefinition());

        RecordingGateway retry = new RecordingGateway(null);
        new ExportAction(retry).execute(invocation("TASK-1"), validDefinition());

        RecordingGateway reentry = new RecordingGateway(null);
        new ExportAction(reentry).execute(invocation("TASK-2"), validDefinition());

        assertEquals(first.request().uniqueBizKey(), retry.request().uniqueBizKey());
        assertNotEquals(
                first.request().uniqueBizKey(),
                reentry.request().uniqueBizKey());
    }

    @Test
    void requiresExactlyOneTargetWithTaskIdAndFileType() {
        ExportAction action = new ExportAction((FileExportGateway) null);

        assertThrows(
                FlowableActionException.class,
                () -> action.execute(invocation(), definition(actionXml(""))));
        assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation(),
                        definition(actionXml(
                                "<target fileType=\"csv\" />"))));
        assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation(),
                        definition(actionXml(
                                "<target expTaskId=\"task_order_export_csv\" />"))));
        assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation(),
                        definition(actionXml(
                                "<target fileType=\"csv\" expTaskId=\"task_order_export_csv\" />"
                                        + "<target fileType=\"csv\" expTaskId=\"task_order_export_csv\" />"))));
    }

    private ActionDefinition validDefinition() {
        return definition(actionXml(
                "<target fileType=\"csv\" expTaskId=\"task_order_export_csv\" />"));
    }

    private String actionXml(String targetXml) {
        return """
                <actions>
                  <action event="complete" type="job" doType="export" sync="false">
                    %s
                  </action>
                </actions>
                """.formatted(targetXml);
    }

    private ActionDefinition definition(String xml) {
        return parser.parse(xml).get(0);
    }

    private ActionInvocation invocation() {
        return invocation("TASK-1");
    }

    private ActionInvocation invocation(String workflowTaskId) {
        ActionContext context = new ActionContext(
                "ORDER-100",
                "order-approval",
                "PROCESS-1",
                workflowTaskId,
                "manager-approval",
                Map.of("customerName", "Acme"),
                Map.of("tenantId", "tenant-1"));
        return new ActionInvocation(ActionEvent.COMPLETE, context, "");
    }

    private static final class RecordingGateway implements FileExportGateway {

        private final Exception failure;
        private final String response;
        private FileExportRequest request;

        private RecordingGateway(Exception failure) {
            this(failure, "ticket-123");
        }

        private RecordingGateway(Exception failure, String response) {
            this.failure = failure;
            this.response = response;
        }

        @Override
        public String export(FileExportRequest request) throws Exception {
            this.request = request;
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        private FileExportRequest request() {
            return request;
        }
    }
}
