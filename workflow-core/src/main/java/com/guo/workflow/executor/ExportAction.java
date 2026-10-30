package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.export.FileExportRequest;
import com.guo.workflow.export.FileExportGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Delegates a file export action to the dedicated export module.
 */
@Component
public class ExportAction implements BusinessAction {

    private static final Logger log = LoggerFactory.getLogger(ExportAction.class);

    private final FileExportGateway exportGateway;

    @Autowired
    public ExportAction(ObjectProvider<FileExportGateway> exportGatewayProvider) {
        this(exportGatewayProvider.getIfAvailable());
    }

    public ExportAction(FileExportGateway exportGateway) {
        this.exportGateway = exportGateway;
    }

    @Override
    public ActionType type() {
        return ActionType.JOB;
    }

    @Override
    public ActionDoType doType() {
        return ActionDoType.EXPORT;
    }

    @Override
    public void execute(ActionInvocation invocation, ActionDefinition definition) {
        ActionNode target = requireTarget(definition);
        String expTaskId = requiredAttribute(target, "expTaskId");
        String fileType = requiredAttribute(target, "fileType");

        if (exportGateway == null) {
            throw new FlowableActionException(
                    "File export module is not configured");
        }

        String resultVariableName = ticketVariableName(invocation, definition);
        String uniqueBizKeyVariableName =
                uniqueBizKeyVariableName(invocation, definition);
        String tenantId = resolveTenantId(invocation);
        String uniqueBizKey = uniqueBizKey(
                invocation,
                definition,
                expTaskId,
                fileType,
                tenantId);
        FileExportRequest request = new FileExportRequest(
                uniqueBizKey,
                expTaskId,
                fileType,
                definition.actionId(),
                invocation.context().processDefinitionKey(),
                invocation.context().activityId(),
                invocation.context().businessKey(),
                invocation.context().processInstanceId(),
                invocation.context().taskId(),
                tenantId,
                invocation.event().value(),
                resultVariableName,
                target.attributes(),
                invocation.context());
        try {
            String exportTicket = exportGateway.export(request);
            if (exportTicket == null || exportTicket.isBlank()) {
                throw new FlowableActionException(
                        "Export module returned a blank export ticket for expTaskId: "
                                + expTaskId);
            }
            invocation.context().variables().put(
                    resultVariableName,
                    exportTicket.trim());
            invocation.context().variables().put(
                    uniqueBizKeyVariableName,
                    uniqueBizKey);
            log.info(
                    "File export completed expTaskId={}, fileType={}, actionId={}, businessKey={}, ticketVariable={}",
                    expTaskId,
                    fileType,
                    definition.actionId(),
                    invocation.context().businessKey(),
                    resultVariableName);
        } catch (FlowableActionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FlowableActionException(
                    "File export failed for expTaskId: " + expTaskId,
                    exception);
        }
    }

    private String uniqueBizKey(
            ActionInvocation invocation,
            ActionDefinition definition,
            String expTaskId,
            String fileType,
            String tenantId) {
        String identity = String.join(
                "|",
                tenantId,
                valueOrEmpty(invocation.context().processDefinitionKey()),
                valueOrEmpty(invocation.context().businessKey()),
                valueOrEmpty(invocation.context().processInstanceId()),
                valueOrEmpty(invocation.context().taskId()),
                valueOrEmpty(invocation.context().activityId()),
                invocation.event().value(),
                definition.actionId(),
                expTaskId,
                fileType);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return "WF_EXPORT_V1_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String ticketVariableName(
            ActionInvocation invocation,
            ActionDefinition definition) {
        return "exportTicket_" + variableSuffix(invocation, definition);
    }

    private String uniqueBizKeyVariableName(
            ActionInvocation invocation,
            ActionDefinition definition) {
        return "exportUniqueBizKey_" + variableSuffix(invocation, definition);
    }

    private String variableSuffix(
            ActionInvocation invocation,
            ActionDefinition definition) {
        return variablePart(invocation.context().activityId())
                + "_"
                + variablePart(definition.actionId());
    }

    private String resolveTenantId(ActionInvocation invocation) {
        Object tenantId = invocation.context().attributes().get("tenantId");
        if (tenantId == null || tenantId.toString().isBlank()) {
            tenantId = invocation.context().attributes().get("tenant_id");
        }
        if (tenantId == null || tenantId.toString().isBlank()) {
            tenantId = invocation.context().variables().get("tenantId");
        }
        if (tenantId == null || tenantId.toString().isBlank()) {
            tenantId = invocation.context().variables().get("tenant_id");
        }
        return tenantId == null || tenantId.toString().isBlank()
                ? "default"
                : tenantId.toString().trim();
    }

    private String variablePart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_]", "_");
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private ActionNode requireTarget(ActionDefinition definition) {
        List<ActionNode> targets = definition.nodes().stream()
                .filter(node -> "target".equals(node.name()))
                .toList();
        if (targets.size() != 1) {
            throw new FlowableActionException(
                    "Export action must configure exactly one <target> node");
        }
        return targets.get(0);
    }

    private String requiredAttribute(ActionNode node, String name) {
        String value = node.attributes().get(name);
        if (value == null || value.isBlank()) {
            throw new FlowableActionException(
                    "Export target is missing required attribute: " + name);
        }
        return value.trim();
    }

}
