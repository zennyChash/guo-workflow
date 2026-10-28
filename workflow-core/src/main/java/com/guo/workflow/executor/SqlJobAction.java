package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.datasource.BusinessDataSourceResolver;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Executes a business SQL job in an independent manual transaction.
 */
@Component
public class SqlJobAction extends AbstractSqlAction {

    public SqlJobAction(BusinessDataSourceResolver dataSourceResolver) {
        super(dataSourceResolver);
    }

    @Override
    public ActionType type() {
        return ActionType.JOB;
    }

    @Override
    protected void executeSql(
            ActionInvocation invocation,
            ActionDefinition definition,
            Connection connection,
            SqlTemplateBinder.BoundSql boundSql,
            int maxRows) throws SQLException {
        Optional<String> resultVariable = ActionResultWriter.targetVariable(definition);
        Object result = null;
        connection.setAutoCommit(false);
        Throwable failure = null;
        try {
            try (PreparedStatement statement = prepare(connection, boundSql)) {
                if (resultVariable.isPresent()) {
                    statement.setMaxRows(maxRows > 0 ? maxRows : 1);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        result = resultSet.next() ? resultSet.getObject(1) : null;
                    }
                } else {
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            failure = exception;
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            throw exception;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreException) {
                if (failure != null) {
                    failure.addSuppressed(restoreException);
                } else {
                    throw restoreException;
                }
            }
        }

        if (resultVariable.isPresent()) {
            ActionResultWriter.write(
                    invocation.context(),
                    resultVariable.get(),
                    result);
        }
    }
}
