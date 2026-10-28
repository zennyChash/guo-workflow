package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.exception.FlowableActionException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts XML {@code #{name}} placeholders into JDBC positional parameters.
 */
final class SqlTemplateBinder {

    private static final Pattern PARAMETER_PATTERN =
            Pattern.compile("#\\{\\s*([A-Za-z_$][A-Za-z0-9_$.]*)\\s*}");
    private static final String ATTRIBUTE_PREFIX = "$attrs.";

    private SqlTemplateBinder() {
    }

    static BoundSql bind(String sqlTemplate, ActionContext context) {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            throw new FlowableActionException("SQL action has blank SQL");
        }

        Matcher matcher = PARAMETER_PATTERN.matcher(sqlTemplate);
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        while (matcher.find()) {
            String parameterName = matcher.group(1);
            parameters.add(resolveParameter(parameterName, context));
            matcher.appendReplacement(sql, Matcher.quoteReplacement("?"));
        }
        matcher.appendTail(sql);
        return new BoundSql(
                sql.toString(),
                Collections.unmodifiableList(new ArrayList<>(parameters)));
    }

    static Object resolveExpression(String expression, ActionContext context) {
        if (expression == null) {
            throw new FlowableActionException("Parameter expression must not be null");
        }
        Matcher matcher = PARAMETER_PATTERN.matcher(expression);
        if (!matcher.matches()) {
            throw new FlowableActionException(
                    "Parameter expression must use #{name}: " + expression);
        }
        return resolveParameter(matcher.group(1), context);
    }

    private static Object resolveParameter(String name, ActionContext context) {
        if (name.startsWith(ATTRIBUTE_PREFIX)) {
            String attributeName = name.substring(ATTRIBUTE_PREFIX.length());
            if (!context.attributes().containsKey(attributeName)) {
                throw new FlowableActionException(
                        "SQL attribute parameter not found: " + name);
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
                    "SQL variable parameter not found: " + name);
        };
    }

    record BoundSql(String sql, List<Object> parameters) {
    }
}
