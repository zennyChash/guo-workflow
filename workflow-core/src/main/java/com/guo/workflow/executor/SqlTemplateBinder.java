package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.exception.FlowableActionException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Converts XML {@code #{name}} placeholders into JDBC positional parameters.
 */
final class SqlTemplateBinder {

    private SqlTemplateBinder() {
    }

    static BoundSql bind(String sqlTemplate, ActionContext context) {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            throw new FlowableActionException("SQL action has blank SQL");
        }

        Matcher matcher = ActionTemplateResolver.parameterMatcher(sqlTemplate);
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        while (matcher.find()) {
            String parameterName = matcher.group(1);
            parameters.add(ActionTemplateResolver.resolveParameter(
                    parameterName,
                    context,
                    "SQL"));
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
        Matcher matcher = ActionTemplateResolver.parameterMatcher(expression);
        if (!matcher.matches()) {
            throw new FlowableActionException(
                    "Parameter expression must use #{name}: " + expression);
        }
        return ActionTemplateResolver.resolveParameter(
                matcher.group(1),
                context,
                "SQL");
    }

    record BoundSql(String sql, List<Object> parameters) {
    }
}
