package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.datasource.BusinessDataSourceResolver;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Executes a read-only SQL validation query.
 */
@Component
public class SqlValidateAction extends AbstractSqlAction {

    public SqlValidateAction(BusinessDataSourceResolver dataSourceResolver) {
        super(dataSourceResolver);
    }

    @Override
    public ActionType type() {
        return ActionType.VALIDATE;
    }

    @Override
    protected void executeSql(
            ActionInvocation invocation,
            ActionDefinition definition,
            Connection connection,
            SqlTemplateBinder.BoundSql boundSql,
            int maxRows) throws SQLException {
        connection.setReadOnly(true);
        try (PreparedStatement statement = prepare(connection, boundSql)) {
            statement.setMaxRows(maxRows > 0 ? maxRows : 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                Object result = resultSet.next() ? resultSet.getObject(1) : null;
                if (!passes(result)) {
                    String errorMessage = requiredNodeText(definition, "error-msg");
                    if (errorMessage == null || errorMessage.isBlank()) {
                        throw new FlowableActionException(
                                "SQL validation " + definition.actionId()
                                        + " is missing <error-msg>");
                    }
                    throw new FlowableActionException(errorMessage);
                }
            }
        }
    }

    private boolean passes(Object result) {
        if (result instanceof Boolean booleanResult) {
            return booleanResult;
        }
        if (result instanceof Number numberResult) {
            return numberResult.doubleValue() != 0.0d;
        }
        return false;
    }
}
