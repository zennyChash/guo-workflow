package com.guo.workflow.executor;

import com.guo.workflow.business.BusinessOperationGateway;
import com.guo.workflow.business.BusinessOperationRequest;
import com.guo.workflow.business.BusinessOperationResult;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.mirror.ProcessBusinessLink;
import com.guo.workflow.mirror.ProcessBusinessLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * Parses a whitelist of action parameters and invokes the business operation gateway.
 */
@Component
public class BusinessOperationAction implements BusinessAction {

    private static final Logger log =
            LoggerFactory.getLogger(BusinessOperationAction.class);

    private final BusinessOperationGateway operationGateway;
    private final ProcessBusinessLinkRepository businessLinkRepository;

    public BusinessOperationAction(
            BusinessOperationGateway operationGateway,
            ProcessBusinessLinkRepository businessLinkRepository) {
        this.operationGateway = operationGateway;
        this.businessLinkRepository = businessLinkRepository;
    }

    @Override
    public ActionType type() {
        return ActionType.JOB;
    }

    @Override
    public ActionDoType doType() {
        return ActionDoType.BUSINESS;
    }

    @Override
    public void execute(ActionInvocation invocation, ActionDefinition definition) {
        ActionNode business = requireBusinessNode(definition);
        String actionKey = requiredAttribute(business, "actionKey");
        int version = positiveIntAttribute(business, "version");
        Map<String, Object> parameters = resolveParameters(business, invocation);

        String tenantId = resolveTenantId(invocation);
        String uniqueOperationKey = uniqueOperationKey(
                invocation,
                definition,
                actionKey,
                version,
                tenantId);
        BusinessOperationRequest request = new BusinessOperationRequest(
                uniqueOperationKey,
                actionKey,
                version,
                tenantId,
                invocation.context().processDefinitionKey(),
                invocation.context().businessKey(),
                invocation.context().processInstanceId(),
                invocation.context().taskId(),
                invocation.context().activityId(),
                definition.actionId(),
                invocation.event().value(),
                parameters);

        try {
            BusinessOperationResult result = operationGateway.invoke(request);
            if (result == null) {
                throw new FlowableActionException(
                        "Business operation returned no result: " + actionKey);
            }
            if (!result.success()) {
                throw new FlowableActionException(
                        "Business operation rejected: "
                                + actionKey
                                + (result.message() == null ? "" : " - " + result.message()));
            }

            writeResultVariables(invocation, definition, uniqueOperationKey, result);
            persistBusinessLink(invocation, uniqueOperationKey, result);
            log.info(
                    "Business operation completed actionKey={}, version={}, businessKey={}, businessId={}",
                    actionKey,
                    version,
                    invocation.context().businessKey(),
                    result.businessId());
        } catch (FlowableActionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FlowableActionException(
                    "Business operation failed: " + actionKey,
                    exception);
        }
    }

    private Map<String, Object> resolveParameters(
            ActionNode business,
            ActionInvocation invocation) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (ActionNode node : business.children()) {
            if (!"param".equals(node.name())) {
                throw new FlowableActionException(
                        "Unsupported business child element: " + node.name());
            }
            String name = requiredAttribute(node, "name");
            if (parameters.containsKey(name)) {
                throw new FlowableActionException(
                        "Duplicate business operation parameter: " + name);
            }
            String valueTemplate = requiredAttribute(node, "value");
            boolean required = optionalBoolean(node, "required", true);
            Object value;
            try {
                value = resolveParameterValue(valueTemplate, invocation);
            } catch (FlowableActionException exception) {
                if (!required) {
                    continue;
                }
                throw exception;
            }
            if (value == null) {
                if (!required) {
                    continue;
                }
                throw new FlowableActionException(
                        "Required business operation parameter is null: " + name);
            }
            parameters.put(name, convertType(name, value, node.attributes().get("type")));
        }
        return parameters;
    }

    private Object resolveParameterValue(
            String valueTemplate,
            ActionInvocation invocation) {
        Matcher matcher = ActionTemplateResolver.parameterMatcher(valueTemplate.trim());
        if (matcher.matches()) {
            return ActionTemplateResolver.resolveParameter(
                    matcher.group(1),
                    invocation.context(),
                    "Business operation");
        }
        return ActionTemplateResolver.render(
                valueTemplate,
                invocation.context(),
                "Business operation");
    }

    private Object convertType(String name, Object value, String configuredType) {
        if (configuredType == null || configuredType.isBlank()) {
            return value;
        }
        String text = String.valueOf(value);
        try {
            return switch (configuredType.trim().toLowerCase(Locale.ROOT)) {
                case "string" -> text;
                case "int", "integer" -> value instanceof Number number
                        ? number.intValue()
                        : Integer.valueOf(text);
                case "long" -> value instanceof Number number
                        ? number.longValue()
                        : Long.valueOf(text);
                case "decimal", "bigdecimal" -> value instanceof BigDecimal decimal
                        ? decimal
                        : new BigDecimal(text);
                case "boolean", "bool" -> value instanceof Boolean bool
                        ? bool
                        : parseBooleanType(name, text);
                default -> throw new FlowableActionException(
                        "Unsupported business parameter type: " + configuredType);
            };
        } catch (NumberFormatException exception) {
            throw new FlowableActionException(
                    "Business parameter type conversion failed: " + name,
                    exception);
        }
    }

    private boolean parseBooleanType(String name, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new FlowableActionException(
                "Business boolean parameter must be true or false: " + name);
    }

    private void writeResultVariables(
            ActionInvocation invocation,
            ActionDefinition definition,
            String uniqueOperationKey,
            BusinessOperationResult result) {
        String suffix = variableSuffix(invocation, definition);
        invocation.context().variables().put(
                "businessOperationKey_" + suffix,
                uniqueOperationKey);
        putIfPresent(invocation, "businessOperationResultId_" + suffix, result.businessId());
        putIfPresent(invocation, "businessOperationResultType_" + suffix, result.businessType());
        putIfPresent(invocation, "businessOperationResultTitle_" + suffix, result.businessTitle());
        putIfPresent(invocation, "businessOperationResultUrl_" + suffix, result.businessUrl());
        if (!result.resultData().isEmpty()) {
            invocation.context().variables().put(
                    "businessOperationResultData_" + suffix,
                    result.resultData());
        }
    }

    private void persistBusinessLink(
            ActionInvocation invocation,
            String uniqueOperationKey,
            BusinessOperationResult result) {
        if ((result.businessId() == null || result.businessId().isBlank())
                && (result.businessType() == null || result.businessType().isBlank())) {
            return;
        }
        String linkId = UUID.nameUUIDFromBytes(
                uniqueOperationKey.getBytes(StandardCharsets.UTF_8)).toString();
        Instant now = Instant.now();
        businessLinkRepository.upsert(new ProcessBusinessLink(
                linkId,
                invocation.context().processInstanceId(),
                resolveTenantId(invocation),
                result.systemCode(),
                result.businessType(),
                result.businessId(),
                invocation.context().businessKey(),
                result.businessTitle(),
                "RESULT",
                "SUCCESS",
                result.businessUrl(),
                now,
                now));
    }

    private void putIfPresent(
            ActionInvocation invocation,
            String variableName,
            String value) {
        if (value != null && !value.isBlank()) {
            invocation.context().variables().put(variableName, value);
        }
    }

    private String uniqueOperationKey(
            ActionInvocation invocation,
            ActionDefinition definition,
            String actionKey,
            int version,
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
                actionKey,
                String.valueOf(version));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return "WF_OPERATION_V1_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String variableSuffix(
            ActionInvocation invocation,
            ActionDefinition definition) {
        return variablePart(invocation.context().activityId())
                + "_"
                + variablePart(definition.actionId());
    }

    private String variablePart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_]", "_");
    }

    private ActionNode requireBusinessNode(ActionDefinition definition) {
        List<ActionNode> nodes = definition.nodes().stream()
                .filter(node -> "business".equals(node.name()))
                .toList();
        if (nodes.size() != 1) {
            throw new FlowableActionException(
                    "Business action must configure exactly one <business> node");
        }
        return nodes.get(0);
    }

    private String requiredAttribute(ActionNode node, String name) {
        String value = node.attributes().get(name);
        if (value == null || value.isBlank()) {
            throw new FlowableActionException(
                    "Business element is missing required attribute: " + name);
        }
        return value.trim();
    }

    private int positiveIntAttribute(ActionNode node, String name) {
        try {
            int value = Integer.parseInt(requiredAttribute(node, name));
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new FlowableActionException(
                    "Business element attribute must be a positive integer: " + name);
        }
    }

    private boolean optionalBoolean(ActionNode node, String name, boolean defaultValue) {
        String value = node.attributes().get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new FlowableActionException(
                    "Business element attribute must be true or false: " + name);
        };
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

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
