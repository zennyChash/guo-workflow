package com.guo.workflow.executor;

import com.guo.workflow.business.BusinessOperationHandler;
import com.guo.workflow.business.BusinessOperationRequest;
import com.guo.workflow.business.BusinessOperationResult;
import com.guo.workflow.business.DefaultBusinessOperationRegistry;
import com.guo.workflow.business.LocalBusinessOperationGateway;
import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.dispatcher.DefaultActionDefinitionParser;
import com.guo.workflow.mirror.ProcessBusinessLink;
import com.guo.workflow.mirror.ProcessBusinessLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BusinessOperationActionTest {

    private final DefaultActionDefinitionParser parser =
            new DefaultActionDefinitionParser();

    @Test
    void invokesVersionedHandlerAndPersistsResult() {
        RecordingHandler handler = new RecordingHandler();
        ProcessBusinessLinkRepository linkRepository =
                mock(ProcessBusinessLinkRepository.class);
        BusinessOperationAction action = action(handler, linkRepository);
        ActionInvocation invocation = invocation("TASK-1");

        action.execute(invocation, definition("""
                <actions>
                  <action id="create-draft"
                          event="complete"
                          type="job"
                          doType="business"
                          sync="false">
                    <business actionKey="purchase.createDraft" version="1">
                      <param name="applicationId" value="#{businessKey}" />
                      <param name="warehouseId"
                             value="#{targetWarehouseId}"
                             type="long" />
                      <param name="amount"
                             value="#{amount}"
                             type="decimal" />
                      <param name="description"
                             value="申请 #{businessKey}" />
                    </business>
                  </action>
                </actions>
                """));

        assertEquals("purchase.createDraft", handler.request.actionKey());
        assertEquals(1, handler.request.version());
        assertEquals("ORDER-100", handler.request.parameters().get("applicationId"));
        assertEquals(1001L, handler.request.parameters().get("warehouseId"));
        assertEquals(
                new BigDecimal("1200.50"),
                handler.request.parameters().get("amount"));
        assertEquals(
                "申请 ORDER-100",
                handler.request.parameters().get("description"));
        assertTrue(handler.request.uniqueOperationKey()
                .startsWith("WF_OPERATION_V1_"));
        assertEquals(
                "DRAFT-1001",
                invocation.context().variables().get(
                        "businessOperationResultId_manager_approval_create_draft"));
        assertEquals(
                "PURCHASE_DRAFT",
                invocation.context().variables().get(
                        "businessOperationResultType_manager_approval_create_draft"));

        ArgumentCaptor<ProcessBusinessLink> captor =
                ArgumentCaptor.forClass(ProcessBusinessLink.class);
        verify(linkRepository).upsert(captor.capture());
        ProcessBusinessLink link = captor.getValue();
        assertEquals("DRAFT-1001", link.businessId());
        assertEquals("PURCHASE_DRAFT", link.businessType());
        assertEquals("SUCCESS", link.businessSyncStatus());
    }

    @Test
    void uniqueOperationKeyIsStableForRetryAndChangesForNewTask() {
        RecordingHandler handler = new RecordingHandler();
        BusinessOperationAction action = action(
                handler,
                mock(ProcessBusinessLinkRepository.class));
        ActionDefinition definition = definition(businessXml());

        action.execute(invocation("TASK-1"), definition);
        String first = handler.request.uniqueOperationKey();
        action.execute(invocation("TASK-1"), definition);
        String retry = handler.request.uniqueOperationKey();
        action.execute(invocation("TASK-2"), definition);
        String reentry = handler.request.uniqueOperationKey();

        assertEquals(first, retry);
        assertNotEquals(first, reentry);
    }

    @Test
    void rejectsMissingHandler() {
        BusinessOperationAction action = action(
                null,
                mock(ProcessBusinessLinkRepository.class));

        FlowableActionException exception = assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation("TASK-1"),
                        definition(businessXml())));

        assertTrue(exception.getMessage().contains("not found"));
    }

    @Test
    void rejectsUnsuccessfulBusinessResult() {
        RecordingHandler handler = new RecordingHandler(
                BusinessOperationResult.failure("business rejected"));
        BusinessOperationAction action = action(
                handler,
                mock(ProcessBusinessLinkRepository.class));

        assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation("TASK-1"),
                        definition(businessXml())));
    }

    @Test
    void rejectsMissingRequiredParameter() {
        ProcessBusinessLinkRepository linkRepository =
                mock(ProcessBusinessLinkRepository.class);
        BusinessOperationAction action = action(new RecordingHandler(), linkRepository);

        assertThrows(
                FlowableActionException.class,
                () -> action.execute(
                        invocation("TASK-1"),
                        definition("""
                                <actions>
                                  <action id="create-draft"
                                          event="complete"
                                          type="job"
                                          doType="business"
                                          sync="false">
                                    <business actionKey="purchase.createDraft" version="1">
                                      <param name="missing" value="#{notFound}" />
                                    </business>
                                  </action>
                                </actions>
                                """)));
    }

    @Test
    void rejectsDuplicateHandlerRoute() {
        assertThrows(
                FlowableActionException.class,
                () -> new DefaultBusinessOperationRegistry(List.of(
                        new RecordingHandler(),
                        new RecordingHandler())));
    }

    private BusinessOperationAction action(
            RecordingHandler handler,
            ProcessBusinessLinkRepository linkRepository) {
        List<BusinessOperationHandler> handlers = handler == null
                ? List.of()
                : List.of(handler);
        return new BusinessOperationAction(
                new LocalBusinessOperationGateway(
                        new DefaultBusinessOperationRegistry(handlers)),
                linkRepository);
    }

    private String businessXml() {
        return """
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
                """;
    }

    private ActionDefinition definition(String xml) {
        return parser.parse(xml).get(0);
    }

    private ActionInvocation invocation(String taskId) {
        ActionContext context = new ActionContext(
                "ORDER-100",
                "purchase-approval",
                "PROCESS-1",
                taskId,
                "manager-approval",
                Map.of(
                        "targetWarehouseId", "1001",
                        "amount", new BigDecimal("1200.50")),
                Map.of("tenantId", "tenant-1"));
        return new ActionInvocation(ActionEvent.COMPLETE, context, "");
    }

    private static final class RecordingHandler implements BusinessOperationHandler {

        private final BusinessOperationResult result;
        private BusinessOperationRequest request;

        private RecordingHandler() {
            this(BusinessOperationResult.success(
                    "ERP",
                    "DRAFT-1001",
                    "PURCHASE_DRAFT",
                    "采购草稿",
                    "/purchase-drafts/DRAFT-1001",
                    "created",
                    Map.of("created", true)));
        }

        private RecordingHandler(BusinessOperationResult result) {
            this.result = result;
        }

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
            this.request = request;
            return result;
        }
    }
}
