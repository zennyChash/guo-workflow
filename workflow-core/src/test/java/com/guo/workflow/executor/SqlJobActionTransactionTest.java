package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.dispatcher.DefaultActionDefinitionParser;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlJobActionTransactionTest {

    @Test
    void restoresAutoCommitAfterSuccessfulCommit() throws Exception {
        Fixture fixture = fixture();
        when(fixture.statement().executeUpdate()).thenReturn(1);

        fixture.action().execute(fixture.invocation(), fixture.definition());

        verify(fixture.connection()).setAutoCommit(false);
        verify(fixture.connection()).commit();
        verify(fixture.connection()).setAutoCommit(true);
    }

    @Test
    void restoresAutoCommitAfterRollback() throws Exception {
        Fixture fixture = fixture();
        when(fixture.statement().executeUpdate())
                .thenThrow(new SQLException("update failed"));

        assertThrows(
                FlowableActionException.class,
                () -> fixture.action().execute(
                        fixture.invocation(),
                        fixture.definition()));

        verify(fixture.connection()).setAutoCommit(false);
        verify(fixture.connection()).rollback();
        verify(fixture.connection()).setAutoCommit(true);
    }

    private Fixture fixture() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        SqlJobAction action = new SqlJobAction(name -> dataSource);
        ActionDefinition definition = new DefaultActionDefinitionParser().parse("""
                <actions>
                  <action event="complete"
                          type="job"
                          doType="sql"
                          sync="true"
                          datasource="biz_db">
                    <sql>UPDATE t_order SET status = 'APPROVED' WHERE id = 1</sql>
                  </action>
                </actions>
                """).get(0);
        ActionContext context = new ActionContext(
                "ORDER-1",
                "order",
                "PROCESS-1",
                "TASK-1",
                "activity-1",
                Map.of(),
                Map.of());
        ActionInvocation invocation = new ActionInvocation(
                ActionEvent.COMPLETE,
                context,
                "<actions/>");
        return new Fixture(action, connection, statement, definition, invocation);
    }

    private record Fixture(
            SqlJobAction action,
            Connection connection,
            PreparedStatement statement,
            ActionDefinition definition,
            ActionInvocation invocation) {
    }
}
