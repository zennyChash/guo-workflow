package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.datasource.BusinessDataSourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Shared named-data-source and prepared-statement handling for SQL actions.
 */
abstract class AbstractSqlAction implements BusinessAction {

    private static final Logger log = LoggerFactory.getLogger(AbstractSqlAction.class);

    private final BusinessDataSourceResolver dataSourceResolver;

    protected AbstractSqlAction(BusinessDataSourceResolver dataSourceResolver) {
        this.dataSourceResolver = dataSourceResolver;
    }

    @Override
    public final ActionDoType doType() {
        return ActionDoType.SQL;
    }

    @Override
    public final void execute(ActionInvocation invocation, ActionDefinition definition) {
        try {
            validateDefinition(definition);
            DataSource dataSource = dataSourceResolver.resolve(definition.dataSource());
            ActionNode sqlNode = requiredNode(definition, "sql");
            SqlTemplateBinder.BoundSql boundSql = SqlTemplateBinder.bind(
                    sqlNode.text(),
                    invocation.context());

            try (Connection connection = dataSource.getConnection()) {
                executeSql(
                        invocation,
                        definition,
                        connection,
                        boundSql,
                        maxRows(definition, sqlNode));
            }
        } catch (FlowableActionException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error(
                    "SQL action failed type={}, actionId={}",
                    type(),
                    definition.actionId(),
                    exception);
            throw new FlowableActionException(
                    "SQL action failed: " + definition.actionId(),
                    exception);
        }
    }

    protected abstract void executeSql(
            ActionInvocation invocation,
            ActionDefinition definition,
            Connection connection,
            SqlTemplateBinder.BoundSql boundSql,
            int maxRows) throws SQLException;

    protected void validateDefinition(ActionDefinition definition) {
        if (definition.dataSource() == null || definition.dataSource().isBlank()) {
            throw new FlowableActionException("SQL action must configure a dataSource");
        }
    }

    protected PreparedStatement prepare(
            Connection connection,
            SqlTemplateBinder.BoundSql boundSql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(boundSql.sql());
        for (int index = 0; index < boundSql.parameters().size(); index++) {
            statement.setObject(index + 1, boundSql.parameters().get(index));
        }
        return statement;
    }

    protected String requiredNodeText(ActionDefinition definition, String name) {
        return requiredNode(definition, name).text();
    }

    private ActionNode requiredNode(ActionDefinition definition, String name) {
        return definition.nodes().stream()
                .filter(node -> name.equals(node.name()))
                .findFirst()
                .orElseThrow(() -> new FlowableActionException(
                        "SQL action " + definition.actionId()
                                + " is missing <" + name + ">"));
    }

    private int maxRows(ActionDefinition definition, ActionNode sqlNode) {
        String configured = sqlNode.attributes().get("maxRows");
        if (configured == null) {
            configured = sqlNode.attributes().get("max-rows");
        }
        if (configured == null) {
            configured = definition.attributes().get("maxRows");
        }
        if (configured == null) {
            configured = definition.attributes().get("max-rows");
        }
        if (configured == null || configured.isBlank()) {
            return 0;
        }
        try {
            int maxRows = Integer.parseInt(configured);
            if (maxRows < 0) {
                throw new FlowableActionException("maxRows must not be negative");
            }
            return maxRows;
        } catch (NumberFormatException exception) {
            throw new FlowableActionException("Invalid maxRows value: " + configured, exception);
        }
    }
}
