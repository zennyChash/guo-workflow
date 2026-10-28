package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.HashMap;
import java.util.Locale;

/**
 * Shared JSR-223 execution for Groovy and JavaScript actions.
 */
abstract class AbstractScriptAction implements BusinessAction {

    private static final Logger log = LoggerFactory.getLogger(AbstractScriptAction.class);
    private static final String DEFAULT_SCRIPT_LANGUAGE = "groovy";

    @Override
    public final ActionDoType doType() {
        return ActionDoType.SCRIPT;
    }

    @Override
    public final void execute(ActionInvocation invocation, ActionDefinition definition) {
        try {
            validateConfiguration(definition);
            executeScript(invocation, definition);
        } catch (FlowableActionException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error(
                    "Script action failed type={}, actionId={}",
                    type(),
                    definition.actionId(),
                    exception);
            throw new FlowableActionException(
                    "Script action failed: " + definition.actionId(),
                    exception);
        }
    }

    protected abstract void processResult(
            ActionInvocation invocation,
            ActionDefinition definition,
            Object result);

    protected void validateConfiguration(ActionDefinition definition) {
        // Extension point for type-specific validation.
    }

    private void executeScript(
            ActionInvocation invocation,
            ActionDefinition definition) throws ScriptException {
        ActionNode scriptNode = requiredNode(definition, "script");
        String script = scriptNode.text();
        if (script == null || script.isBlank()) {
            throw new FlowableActionException(
                    "Script action " + definition.actionId() + " has blank <script>");
        }

        String language = scriptNode.attributes().getOrDefault(
                "lang",
                DEFAULT_SCRIPT_LANGUAGE);
        if (language == null || language.isBlank()) {
            language = DEFAULT_SCRIPT_LANGUAGE;
        }
        String engineName = switch (language.toLowerCase(Locale.ROOT)) {
            case "groovy" -> "groovy";
            case "js", "javascript" -> "nashorn";
            default -> throw new FlowableActionException(
                    "Unsupported script language: " + language);
        };

        ScriptEngine engine = new ScriptEngineManager().getEngineByName(engineName);
        if (engine == null) {
            throw new FlowableActionException(
                    "Script engine is not available: " + engineName);
        }

        Bindings bindings = engine.createBindings();
        bindings.putAll(invocation.context().variables());
        bindings.put("$attrs", new HashMap<>(invocation.context().attributes()));
        putCoreField(bindings, "businessKey", invocation.context().businessKey());
        putCoreField(bindings, "processInstanceId", invocation.context().processInstanceId());
        putCoreField(bindings, "taskId", invocation.context().taskId());
        putCoreField(bindings, "activityId", invocation.context().activityId());

        Object result = engine.eval(script, bindings);
        processResult(invocation, definition, result);
    }

    private ActionNode requiredNode(ActionDefinition definition, String name) {
        return definition.nodes().stream()
                .filter(node -> name.equals(node.name()))
                .findFirst()
                .orElseThrow(() -> new FlowableActionException(
                        "Script action " + definition.actionId()
                                + " is missing <" + name + ">"));
    }

    private void putCoreField(Bindings bindings, String name, Object value) {
        if (value != null) {
            bindings.put(name, value);
        }
    }
}
