package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.datasource.BusinessDataSourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Executes a configured database stored procedure in an independent transaction.
 */
@Component
public class StoredProcJobAction implements BusinessAction {

    private static final Logger log = LoggerFactory.getLogger(StoredProcJobAction.class);
    private static final Pattern PROCEDURE_NAME_PATTERN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_$#]*(?:\\.[A-Za-z_][A-Za-z0-9_$#]*)*");
    private static final Map<String, Integer> JDBC_TYPES = createJdbcTypes();

    private final BusinessDataSourceResolver dataSourceResolver;

    public StoredProcJobAction(BusinessDataSourceResolver dataSourceResolver) {
        this.dataSourceResolver = dataSourceResolver;
    }

    @Override
    public ActionType type() {
        return ActionType.JOB;
    }

    @Override
    public ActionDoType doType() {
        return ActionDoType.STORED_PROC;
    }

    @Override
    public void execute(ActionInvocation invocation, ActionDefinition definition) {
        try {
            validateDataSource(definition);
            ActionNode proc = requiredNode(definition, "proc");
            String procedureName = requiredAttribute(proc, "name");
            if (!PROCEDURE_NAME_PATTERN.matcher(procedureName).matches()) {
                throw new FlowableActionException(
                        "Invalid stored procedure name: " + procedureName);
            }

            List<ProcedureParameter> parameters = parseParameters(proc);
            boolean resultSetEnabled = parseBooleanAttribute(proc, "resultSet", false);
            DataSource dataSource = dataSourceResolver.resolve(definition.dataSource());
            executeProcedure(
                    invocation,
                    definition,
                    dataSource,
                    proc,
                    procedureName,
                    parameters,
                    resultSetEnabled);
        } catch (FlowableActionException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error(
                    "Stored procedure action failed actionId={}",
                    definition.actionId(),
                    exception);
            throw new FlowableActionException(
                    "Stored procedure action failed: " + definition.actionId(),
                    exception);
        }
    }

    private void executeProcedure(
            ActionInvocation invocation,
            ActionDefinition definition,
            DataSource dataSource,
            ActionNode proc,
            String procedureName,
            List<ProcedureParameter> parameters,
            boolean resultSetEnabled) throws SQLException {
        String callSql = buildCall(procedureName, parameters.size());
        Object resultSetValue = null;
        Map<String, Object> outputValues = new LinkedHashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            Throwable failure = null;
            try (CallableStatement statement = connection.prepareCall(callSql)) {
                bindParameters(statement, parameters, invocation.context());
                statement.execute();

                if (resultSetEnabled) {
                    resultSetValue = readCurrentResultSet(statement);
                }
                drainRemainingResults(statement);
                readOutputParameters(statement, parameters, outputValues);
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
        }

        if (resultSetEnabled) {
            String resultVar = firstNonBlank(
                    proc.attributes().get("resultVar"),
                    defaultResultVariable(invocation));
            ActionResultWriter.write(
                    invocation.context(),
                    resultVar,
                    resultSetValue);
        }
        outputValues.forEach((name, value) ->
                ActionResultWriter.write(invocation.context(), name, value));
    }

    private void bindParameters(
            CallableStatement statement,
            List<ProcedureParameter> parameters,
            ActionContext context) throws SQLException {
        for (ProcedureParameter parameter : parameters) {
            if (parameter.mode() == ParameterMode.IN) {
                Object value = SqlTemplateBinder.resolveExpression(
                        parameter.valueExpression(),
                        context);
                if (value == null) {
                    statement.setNull(parameter.position(), parameter.jdbcType());
                } else {
                    statement.setObject(
                            parameter.position(),
                            value,
                            parameter.jdbcType());
                }
            } else {
                statement.registerOutParameter(
                        parameter.position(),
                        parameter.jdbcType());
            }
        }
    }

    private void readOutputParameters(
            CallableStatement statement,
            List<ProcedureParameter> parameters,
            Map<String, Object> outputValues) throws SQLException {
        for (ProcedureParameter parameter : parameters) {
            if (parameter.mode() != ParameterMode.OUT) {
                continue;
            }
            Object value;
            if (parameter.jdbcType() == Types.REF_CURSOR) {
                Object cursor = statement.getObject(parameter.position());
                if (cursor == null) {
                    value = List.of();
                } else if (cursor instanceof ResultSet resultSet) {
                    try (resultSet) {
                        value = toList(resultSet);
                    }
                } else {
                    throw new FlowableActionException(
                            "Cursor output parameter did not return a ResultSet: "
                                    + parameter.resultVar());
                }
            } else {
                value = statement.getObject(parameter.position());
            }
            outputValues.put(parameter.resultVar(), value);
        }
    }

    private List<Map<String, Object>> readCurrentResultSet(
            CallableStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.getResultSet()) {
            return resultSet == null ? List.of() : toList(resultSet);
        }
    }

    private void drainRemainingResults(CallableStatement statement) throws SQLException {
        boolean hasResultSet = statement.getMoreResults();
        while (hasResultSet || statement.getUpdateCount() != -1) {
            if (hasResultSet) {
                try (ResultSet ignored = statement.getResultSet()) {
                    // Drain every result so drivers can expose registered OUT parameters.
                }
            }
            hasResultSet = statement.getMoreResults();
        }
    }

    private List<Map<String, Object>> toList(ResultSet resultSet) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= columnCount; index++) {
                row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
            }
            rows.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(rows);
    }

    private List<ProcedureParameter> parseParameters(ActionNode proc) {
        List<ProcedureParameter> parameters = new ArrayList<>();
        int position = 1;
        for (ActionNode child : proc.children()) {
            if ("in".equals(child.name())) {
                String type = requiredAttribute(child, "type");
                parameters.add(new ProcedureParameter(
                        position++,
                        ParameterMode.IN,
                        jdbcType(type),
                        requiredAttribute(child, "value"),
                        null));
            } else if ("out".equals(child.name())) {
                String type = requiredAttribute(child, "type");
                parameters.add(new ProcedureParameter(
                        position++,
                        ParameterMode.OUT,
                        jdbcType(type),
                        null,
                        requiredAttribute(child, "resultVar")));
            }
        }
        return List.copyOf(parameters);
    }

    private String buildCall(String procedureName, int parameterCount) {
        String placeholders = String.join(
                ", ",
                Collections.nCopies(parameterCount, "?"));
        return "{call " + procedureName + "(" + placeholders + ")}";
    }

    private int jdbcType(String typeName) {
        Integer jdbcType = JDBC_TYPES.get(typeName.trim().toUpperCase(Locale.ROOT));
        if (jdbcType == null) {
            throw new FlowableActionException(
                    "Unsupported stored procedure parameter type: " + typeName);
        }
        return jdbcType;
    }

    private boolean parseBooleanAttribute(ActionNode node, String name, boolean defaultValue) {
        String value = node.attributes().get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new FlowableActionException(
                    "Attribute " + name + " must be true or false: " + value);
        };
    }

    private String defaultResultVariable(ActionInvocation invocation) {
        return firstNonBlank(
                invocation.context().activityId(),
                invocation.context().taskId(),
                "storedProc") + "_proResult";
    }

    private ActionNode requiredNode(ActionDefinition definition, String name) {
        return definition.nodes().stream()
                .filter(node -> name.equals(node.name()))
                .findFirst()
                .orElseThrow(() -> new FlowableActionException(
                        "Stored procedure action " + definition.actionId()
                                + " is missing <" + name + ">"));
    }

    private String requiredAttribute(ActionNode node, String name) {
        String value = node.attributes().get(name);
        if (value == null || value.isBlank()) {
            throw new FlowableActionException(
                    "Stored procedure node is missing attribute: " + name);
        }
        return value.trim();
    }

    private void validateDataSource(ActionDefinition definition) {
        if (definition.dataSource() == null || definition.dataSource().isBlank()) {
            throw new FlowableActionException(
                    "Stored procedure action must configure a dataSource");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Map<String, Integer> createJdbcTypes() {
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("VARCHAR", Types.VARCHAR);
        types.put("CHAR", Types.CHAR);
        types.put("LONGVARCHAR", Types.LONGVARCHAR);
        types.put("INTEGER", Types.INTEGER);
        types.put("INT", Types.INTEGER);
        types.put("SMALLINT", Types.SMALLINT);
        types.put("TINYINT", Types.TINYINT);
        types.put("BIGINT", Types.BIGINT);
        types.put("DECIMAL", Types.DECIMAL);
        types.put("NUMERIC", Types.NUMERIC);
        types.put("DOUBLE", Types.DOUBLE);
        types.put("FLOAT", Types.FLOAT);
        types.put("BOOLEAN", Types.BOOLEAN);
        types.put("DATE", Types.DATE);
        types.put("TIMESTAMP", Types.TIMESTAMP);
        types.put("BLOB", Types.BLOB);
        types.put("CLOB", Types.CLOB);
        types.put("CURSOR", Types.REF_CURSOR);
        types.put("REF_CURSOR", Types.REF_CURSOR);
        return Collections.unmodifiableMap(types);
    }

    private enum ParameterMode {
        IN,
        OUT
    }

    private record ProcedureParameter(
            int position,
            ParameterMode mode,
            int jdbcType,
            String valueExpression,
            String resultVar) {
    }
}
