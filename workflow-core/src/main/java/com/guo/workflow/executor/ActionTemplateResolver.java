package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.exception.FlowableActionException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves action placeholders from process variables, core fields and attributes.
 */
final class ActionTemplateResolver {

    private static final Pattern PARAMETER_PATTERN =
            Pattern.compile("#\\{\\s*([A-Za-z_$][A-Za-z0-9_$.]*)\\s*}");
    private static final String ATTRIBUTE_PREFIX = "$attrs.";

    private ActionTemplateResolver() {
    }

    static Matcher parameterMatcher(String template) {
        return PARAMETER_PATTERN.matcher(template);
    }

    static String render(String template, ActionContext context, String parameterType) {
        Matcher matcher = parameterMatcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = resolveParameter(matcher.group(1), context, parameterType);
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static Object resolveParameter(
            String name,
            ActionContext context,
            String parameterType) {
        if (name.startsWith(ATTRIBUTE_PREFIX)) {
            String attributeName = name.substring(ATTRIBUTE_PREFIX.length());
            if (!context.attributes().containsKey(attributeName)) {
                throw new FlowableActionException(
                        parameterType + " attribute parameter not found: " + name);
            }
            return context.attributes().get(attributeName);
        }
        if (context.variables().containsKey(name)) {
            return context.variables().get(name);
        }
        return switch (name) {
            case "businessKey" -> context.businessKey();
            case "processDefinitionKey" -> context.processDefinitionKey();
            case "processInstanceId" -> context.processInstanceId();
            case "taskId" -> context.taskId();
            case "activityId" -> context.activityId();
            default -> throw new FlowableActionException(
                    parameterType + " variable parameter not found: " + name);
        };
    }
}
