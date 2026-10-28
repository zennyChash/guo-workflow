package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.dispatcher.DefaultActionDefinitionParser;
import com.guo.workflow.datasource.BusinessDataSourceResolver;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlActionTest {

    private final DefaultActionDefinitionParser parser = new DefaultActionDefinitionParser();
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private BusinessDataSourceResolver resolver;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:sql-action-"
                        + java.util.UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        jdbcTemplate = new JdbcTemplate(dataSource);
        resolver = name -> {
            assertEquals("biz_db", name);
            return dataSource;
        };
        jdbcTemplate.execute("""
                CREATE TABLE t_order (
                    id INT PRIMARY KEY,
                    status VARCHAR(200) NOT NULL
                )
                """);
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (?, ?)", 1, "DRAFT");
    }

    @Test
    void jobCommitsParameterizedUpdate() {
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="job"
                        doType="sql"
                        sync="true"
                        datasource="biz_db">
                  <sql>UPDATE t_order SET status = #{newStatus} WHERE id = #{orderId}</sql>
                </action>
                """);
        ActionContext context = context(Map.of("newStatus", "APPROVED", "orderId", 1));

        new SqlJobAction(resolver).execute(invocation(context), definition);

        assertEquals(
                "APPROVED",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM t_order WHERE id = 1",
                        String.class));
    }

    @Test
    void jobBindsSqlInjectionAsData() {
        String maliciousStatus = "APPROVED'; DROP TABLE t_order; --";
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="job"
                        doType="sql"
                        sync="true"
                        datasource="biz_db">
                  <sql>UPDATE t_order SET status = #{newStatus} WHERE id = #{orderId}</sql>
                </action>
                """);
        ActionContext context = context(
                Map.of("newStatus", maliciousStatus, "orderId", 1));

        new SqlJobAction(resolver).execute(invocation(context), definition);

        assertEquals(
                maliciousStatus,
                jdbcTemplate.queryForObject(
                        "SELECT status FROM t_order WHERE id = 1",
                        String.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM t_order",
                        Integer.class));
    }

    @Test
    void jobRollsBackOnFailure() {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (?, ?)", 2, "DRAFT");
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="job"
                        doType="sql"
                        sync="true"
                        datasource="biz_db">
                  <sql>
                    UPDATE t_order
                    SET id = CASE WHEN id = 1 THEN 2 ELSE id END
                    WHERE id IN (1, 2)
                  </sql>
                </action>
                """);
        ActionContext context = context(Map.of());

        assertThrows(
                FlowableActionException.class,
                () -> new SqlJobAction(resolver).execute(invocation(context), definition));

        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM t_order WHERE id = 1",
                        Integer.class));
        assertEquals(
                "DRAFT",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM t_order WHERE id = 1",
                        String.class));
    }

    @Test
    void jobWritesQueryResultToConfiguredVariable() {
        createTaskRuleTable();
        jdbcTemplate.update(
                "INSERT INTO t_task_rule (dept_id, next_approver) VALUES (?, ?)",
                10,
                "manager-2");
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="job"
                        doType="sql"
                        sync="true"
                        datasource="biz_db">
                  <sql>
                    SELECT next_approver
                    FROM t_task_rule
                    WHERE dept_id = #{deptId}
                  </sql>
                  <result var="nextApprover"/>
                </action>
                """);
        ActionContext context = context(Map.of("deptId", 10));

        new SqlJobAction(resolver).execute(invocation(context), definition);

        assertEquals("manager-2", context.variables().get("nextApprover"));
    }

    @Test
    void jobWritesNullWhenResultQueryHasNoRows() {
        createTaskRuleTable();
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="job"
                        doType="sql"
                        sync="true"
                        datasource="biz_db">
                  <sql>SELECT next_approver FROM t_task_rule WHERE dept_id = #{deptId}</sql>
                  <result var="nextApprover"/>
                </action>
                """);
        ActionContext context = context(Map.of("deptId", 999));

        new SqlJobAction(resolver).execute(invocation(context), definition);

        assertTrue(context.variables().containsKey("nextApprover"));
        assertNull(context.variables().get("nextApprover"));
    }

    @Test
    void validatePassesForNonZeroNumber() {
        createCreditTable(new BigDecimal("100.00"));
        ActionDefinition definition = validateDefinition();
        ActionContext context = context(Map.of("customerId", 1));

        new SqlValidateAction(resolver).execute(invocation(context), definition);
        assertFalse(context.variables().containsKey("ignoredByValidate"));
    }

    @Test
    void validateFailsForZeroAndUsesConfiguredMessage() {
        createCreditTable(BigDecimal.ZERO);
        ActionDefinition definition = validateDefinition();
        ActionContext context = context(Map.of("customerId", 1));

        FlowableActionException exception = assertThrows(
                FlowableActionException.class,
                () -> new SqlValidateAction(resolver).execute(
                        invocation(context),
                        definition));

        assertEquals("信保可用额度不足", exception.getMessage());
    }

    @Test
    void validateSupportsBooleanAndNullResults() {
        ActionDefinition trueResult = validateSql("SELECT TRUE");
        new SqlValidateAction(resolver).execute(
                invocation(context(Map.of())),
                trueResult);

        FlowableActionException falseResult = assertThrows(
                FlowableActionException.class,
                () -> new SqlValidateAction(resolver).execute(
                        invocation(context(Map.of())),
                        validateSql("SELECT FALSE")));
        assertEquals("SQL validation failed", falseResult.getMessage());

        FlowableActionException nullResult = assertThrows(
                FlowableActionException.class,
                () -> new SqlValidateAction(resolver).execute(
                        invocation(context(Map.of())),
                        validateSql("SELECT NULL")));
        assertEquals("SQL validation failed", nullResult.getMessage());
    }

    @Test
    void rejectsMissingDataSourceAndMissingParameterBeforeExecution() {
        ActionDefinition missingDataSource = definition("""
                <action event="complete"
                        type="job"
                        doType="sql"
                        sync="true">
                  <sql>UPDATE t_order SET status = #{newStatus}</sql>
                </action>
                """);
        ActionContext context = context(Map.of("newStatus", "APPROVED"));

        assertThrows(
                FlowableActionException.class,
                () -> new SqlJobAction(resolver).execute(
                        invocation(context),
                        missingDataSource));

        ActionDefinition missingParameter = definition("""
                <action event="complete"
                        type="job"
                        doType="sql"
                        sync="true"
                        datasource="biz_db">
                  <sql>UPDATE t_order SET status = #{missingValue}</sql>
                </action>
                """);

        assertThrows(
                FlowableActionException.class,
                () -> new SqlJobAction(resolver).execute(
                        invocation(context(Map.of())),
                        missingParameter));
    }

    private void createCreditTable(BigDecimal amount) {
        jdbcTemplate.execute("""
                CREATE TABLE t_credit (
                    customer_id INT PRIMARY KEY,
                    available_amount DECIMAL(18, 2)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO t_credit (customer_id, available_amount) VALUES (?, ?)",
                1,
                amount);
    }

    private void createTaskRuleTable() {
        jdbcTemplate.execute("""
                CREATE TABLE t_task_rule (
                    dept_id INT PRIMARY KEY,
                    next_approver VARCHAR(64)
                )
                """);
    }

    private ActionDefinition validateDefinition() {
        return validateSql("""
                SELECT available_amount
                FROM t_credit
                WHERE customer_id = #{customerId}
                """, "信保可用额度不足");
    }

    private ActionDefinition validateSql(String sql) {
        return validateSql(sql, "SQL validation failed");
    }

    private ActionDefinition validateSql(String sql, String errorMessage) {
        return definition("""
                <action event="complete"
                        type="validate"
                        doType="sql"
                        sync="true"
                        phase="before-commit"
                        datasource="biz_db">
                  <sql>%s</sql>
                  <result var="ignoredByValidate"/>
                  <error-msg>%s</error-msg>
                </action>
                """.formatted(sql, errorMessage));
    }

    private ActionDefinition definition(String actionXml) {
        return parser.parse("<actions>" + actionXml + "</actions>").get(0);
    }

    private ActionContext context(Map<String, Object> variables) {
        return new ActionContext(
                "ORDER-100",
                "order-approval",
                "PROCESS-1",
                "TASK-1",
                "manager-approval",
                new HashMap<>(variables),
                Map.of("tenantId", "tenant-1"));
    }

    private ActionInvocation invocation(ActionContext context) {
        return new ActionInvocation(ActionEvent.COMPLETE, context, "<actions/>");
    }
}
