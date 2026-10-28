package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.dispatcher.DefaultActionDefinitionParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptActionTest {

    private final DefaultActionDefinitionParser parser = new DefaultActionDefinitionParser();

    @Test
    void groovyJobUsesVariablesAndDefensiveAttributes() {
        ActionContext context = context();
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="job"
                        doType="script"
                        sync="true">
                  <script>
                    $attrs.tenantId = 'mutated-by-script'
                    return price * quantity
                  </script>
                </action>
                """);

        new ScriptJobAction().execute(invocation(context, definition), definition);

        assertEquals(240, context.variables().get("scriptResult"));
        assertEquals("tenant-1", context.attributes().get("tenantId"));
    }

    @Test
    void javascriptJobExecutesWithJsEngine() {
        ActionContext context = context();
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="job"
                        doType="script"
                        sync="true">
                  <script lang="js">$attrs.tenantId + ':' + price</script>
                </action>
                """);

        new ScriptJobAction().execute(invocation(context, definition), definition);

        assertEquals("tenant-1:120", context.variables().get("scriptResult"));
    }

    @Test
    void configuredResultVariableOverridesDefault() {
        ActionContext context = context();
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="job"
                        doType="script"
                        sync="true">
                  <script>return 'manager-2'</script>
                  <result var="nextApprover"/>
                </action>
                """);

        new ScriptJobAction().execute(invocation(context, definition), definition);

        assertEquals("manager-2", context.variables().get("nextApprover"));
        assertFalse(context.variables().containsKey("scriptResult"));
    }

    @Test
    void validationReturnsTrueWithoutException() {
        ActionContext context = context();
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="validate"
                        doType="script"
                        sync="true"
                        phase="before-commit">
                  <script>return price >= 100</script>
                  <result var="nextApprover"/>
                  <error-msg>价格不能低于 100</error-msg>
                </action>
                """);

        new ScriptValidateAction().execute(invocation(context, definition), definition);
        assertFalse(context.variables().containsKey("nextApprover"));
    }

    @Test
    void validationFailureUsesConfiguredMessage() {
        ActionContext context = context();
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="validate"
                        doType="script"
                        sync="true"
                        phase="before-commit">
                  <script>return price &lt; 100</script>
                  <error-msg>价格超出允许范围</error-msg>
                </action>
                """);

        FlowableActionException exception = assertThrows(
                FlowableActionException.class,
                () -> new ScriptValidateAction().execute(
                        invocation(context, definition),
                        definition));

        assertEquals("价格超出允许范围", exception.getMessage());
    }

    @Test
    void validationRequiresErrorMessageEvenWhenScriptIsTrue() {
        ActionContext context = context();
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="validate"
                        doType="script"
                        sync="true"
                        phase="before-commit">
                  <script>return true</script>
                </action>
                """);

        assertThrows(
                FlowableActionException.class,
                () -> new ScriptValidateAction().execute(
                        invocation(context, definition),
                        definition));
    }

    @Test
    void scriptFailureIsWrapped() {
        ActionContext context = context();
        ActionDefinition definition = definition("""
                <action event="complete"
                        type="job"
                        doType="script"
                        sync="true">
                  <script>this is not valid groovy</script>
                </action>
                """);

        FlowableActionException exception = assertThrows(
                FlowableActionException.class,
                () -> new ScriptJobAction().execute(
                        invocation(context, definition),
                        definition));

        assertTrue(exception.getCause() instanceof Exception);
    }

    private ActionDefinition definition(String actionXml) {
        return parser.parse("<actions>" + actionXml + "</actions>").get(0);
    }

    private ActionContext context() {
        return new ActionContext(
                "ORDER-100",
                "order-approval",
                "PROCESS-1",
                "TASK-1",
                "manager-approval",
                Map.of("price", 120, "quantity", 2),
                Map.of("tenantId", "tenant-1"));
    }

    private ActionInvocation invocation(
            ActionContext context,
            ActionDefinition definition) {
        return new ActionInvocation(
                ActionEvent.COMPLETE,
                context,
                "<actions/>");
    }
}
