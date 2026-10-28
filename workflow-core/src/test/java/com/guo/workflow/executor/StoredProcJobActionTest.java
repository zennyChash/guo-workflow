package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.dispatcher.DefaultActionDefinitionParser;
import com.guo.workflow.datasource.BusinessDataSourceResolver;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoredProcJobActionTest {

    private final DefaultActionDefinitionParser parser = new DefaultActionDefinitionParser();

    @Test
    void bindsInputsAndOutputsInXmlOrderAndCommits() throws Exception {
        Fixture fixture = fixture("""
                <action event="complete"
                        type="job"
                        doType="stored_proc"
                        sync="true"
                        datasource="biz_db">
                  <proc name="p_process_order">
                    <in value="#{businessKey}" type="VARCHAR"/>
                    <in value="#{orderAmount}" type="DECIMAL"/>
                    <out resultVar="statusCode" type="INTEGER"/>
                    <out resultVar="errorMessage" type="VARCHAR"/>
                  </proc>
                </action>
                """);
        when(fixture.statement().execute()).thenReturn(false);
        when(fixture.statement().getObject(3)).thenReturn(1);
        when(fixture.statement().getObject(4)).thenReturn("OK");

        fixture.action().execute(fixture.invocation(), fixture.definition());

        verify(fixture.connection()).prepareCall(
                "{call p_process_order(?, ?, ?, ?)}");
        InOrder order = inOrder(fixture.connection(), fixture.statement());
        order.verify(fixture.connection()).setAutoCommit(false);
        order.verify(fixture.statement()).setObject(1, "ORDER-100", Types.VARCHAR);
        order.verify(fixture.statement()).setObject(
                2,
                new BigDecimal("120.50"),
                Types.DECIMAL);
        order.verify(fixture.statement()).registerOutParameter(3, Types.INTEGER);
        order.verify(fixture.statement()).registerOutParameter(4, Types.VARCHAR);
        order.verify(fixture.statement()).execute();
        order.verify(fixture.statement()).getObject(3);
        order.verify(fixture.statement()).getObject(4);
        order.verify(fixture.connection()).commit();
        order.verify(fixture.connection()).setAutoCommit(true);

        assertEquals(1, fixture.invocation().context().variables().get("statusCode"));
        assertEquals("OK", fixture.invocation().context().variables().get("errorMessage"));
    }

    @Test
    void storesProcedureResultSetUnderDefaultActivityVariable() throws Exception {
        Fixture fixture = fixture("""
                <action event="complete"
                        type="job"
                        doType="stored_proc"
                        sync="true"
                        datasource="biz_db">
                  <proc name="p_process_order" resultSet="true">
                    <in value="#{businessKey}" type="VARCHAR"/>
                  </proc>
                </action>
                """);
        ResultSet resultSet = resultSet(
                List.of("id", "name"),
                List.of(List.of(1, "A"), List.of(2, "B")));
        when(fixture.statement().execute()).thenReturn(true);
        when(fixture.statement().getResultSet()).thenReturn(resultSet);

        fixture.action().execute(fixture.invocation(), fixture.definition());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>)
                fixture.invocation().context().variables()
                        .get("manager-approval_proResult");
        assertEquals(
                List.of(
                        Map.of("id", 1, "name", "A"),
                        Map.of("id", 2, "name", "B")),
                rows);
        verify(fixture.connection()).commit();
    }

    @Test
    void storesResultSetAndOutputParametersTogether() throws Exception {
        Fixture fixture = fixture("""
                <action event="complete"
                        type="job"
                        doType="stored_proc"
                        sync="true"
                        datasource="biz_db">
                  <proc name="p_process_order"
                        resultSet="true"
                        resultVar="orderDetails">
                    <in value="#{businessKey}" type="VARCHAR"/>
                    <out resultVar="statusCode" type="INTEGER"/>
                    <out resultVar="errorMessage" type="VARCHAR"/>
                  </proc>
                </action>
                """);
        ResultSet resultSet = resultSet(
                List.of("order_id"),
                List.of(List.of("ORDER-100")));
        when(fixture.statement().execute()).thenReturn(true);
        when(fixture.statement().getResultSet()).thenReturn(resultSet);
        when(fixture.statement().getObject(2)).thenReturn(0);
        when(fixture.statement().getObject(3)).thenReturn("OK");

        fixture.action().execute(fixture.invocation(), fixture.definition());

        assertEquals(
                List.of(Map.of("order_id", "ORDER-100")),
                fixture.invocation().context().variables().get("orderDetails"));
        assertEquals(
                0,
                fixture.invocation().context().variables().get("statusCode"));
        assertEquals(
                "OK",
                fixture.invocation().context().variables().get("errorMessage"));
    }

    @Test
    void readsScalarAndCursorOutputsForOracleStyleProcedure() throws Exception {
        Fixture fixture = fixture("""
                <action event="complete"
                        type="job"
                        doType="stored_proc"
                        sync="true"
                        datasource="biz_db">
                  <proc name="pkg_order.get_info">
                    <in value="#{businessKey}" type="VARCHAR"/>
                    <out resultVar="info" type="VARCHAR"/>
                    <out resultVar="details" type="CURSOR"/>
                  </proc>
                </action>
                """);
        ResultSet cursor = resultSet(
                List.of("order_id", "status"),
                List.of(List.of("ORDER-100", "APPROVED")));
        when(fixture.statement().execute()).thenReturn(false);
        when(fixture.statement().getObject(2)).thenReturn("order info");
        when(fixture.statement().getObject(3)).thenReturn(cursor);

        fixture.action().execute(fixture.invocation(), fixture.definition());

        assertEquals(
                "order info",
                fixture.invocation().context().variables().get("info"));
        assertEquals(
                List.of(Map.of("order_id", "ORDER-100", "status", "APPROVED")),
                fixture.invocation().context().variables().get("details"));
        verify(cursor).close();
    }

    @Test
    void rollsBackAndRestoresAutoCommitOnFailure() throws Exception {
        Fixture fixture = fixture("""
                <action event="complete"
                        type="job"
                        doType="stored_proc"
                        sync="true"
                        datasource="biz_db">
                  <proc name="p_process_order">
                    <in value="#{businessKey}" type="VARCHAR"/>
                  </proc>
                </action>
                """);
        when(fixture.statement().execute())
                .thenThrow(new SQLException("procedure failed"));

        assertThrows(
                FlowableActionException.class,
                () -> fixture.action().execute(
                        fixture.invocation(),
                        fixture.definition()));

        verify(fixture.connection()).rollback();
        verify(fixture.connection()).setAutoCommit(true);
    }

    @Test
    void rejectsMissingDataSourceBeforeCallingResolver() {
        Fixture fixture = fixture("""
                <action event="complete"
                        type="job"
                        doType="stored_proc"
                        sync="true">
                  <proc name="p_process_order">
                    <in value="#{businessKey}" type="VARCHAR"/>
                  </proc>
                </action>
                """);

        assertThrows(
                FlowableActionException.class,
                () -> fixture.action().execute(
                        fixture.invocation(),
                        fixture.definition()));
        verifyNoInteractions(fixture.dataSource());
    }

    private Fixture fixture(String actionXml) {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        CallableStatement statement = mock(CallableStatement.class);
        try {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareCall(anyString())).thenReturn(statement);
            when(statement.getMoreResults()).thenReturn(false);
            when(statement.getUpdateCount()).thenReturn(-1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }

        StoredProcJobAction action = new StoredProcJobAction(name -> {
            assertEquals("biz_db", name);
            return dataSource;
        });
        ActionDefinition definition = parser.parse(
                "<actions>" + actionXml + "</actions>").get(0);
        ActionContext context = new ActionContext(
                "ORDER-100",
                "order-approval",
                "PROCESS-1",
                "TASK-1",
                "manager-approval",
                Map.of("orderAmount", new BigDecimal("120.50")),
                Map.of());
        ActionInvocation invocation = new ActionInvocation(
                ActionEvent.COMPLETE,
                context,
                "<actions/>");
        return new Fixture(
                action,
                dataSource,
                connection,
                statement,
                definition,
                invocation);
    }

    private ResultSet resultSet(
            List<String> columns,
            List<List<Object>> rows) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            when(metadata.getColumnLabel(index + 1)).thenReturn(columns.get(index));
        }

        AtomicInteger rowIndex = new AtomicInteger(-1);
        when(resultSet.next()).thenAnswer(ignored ->
                rowIndex.incrementAndGet() < rows.size());
        when(resultSet.getObject(anyInt())).thenAnswer(invocation -> {
            int columnIndex = invocation.getArgument(0, Integer.class) - 1;
            return rows.get(rowIndex.get()).get(columnIndex);
        });
        return resultSet;
    }

    private record Fixture(
            StoredProcJobAction action,
            DataSource dataSource,
            Connection connection,
            CallableStatement statement,
            ActionDefinition definition,
            ActionInvocation invocation) {
    }
}
