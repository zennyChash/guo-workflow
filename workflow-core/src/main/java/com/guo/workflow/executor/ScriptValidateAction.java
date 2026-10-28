package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.springframework.stereotype.Component;

/**
 * Executes pre-commit script validation.
 */
@Component
public class ScriptValidateAction extends AbstractScriptAction {

    @Override
    public ActionType type() {
        return ActionType.VALIDATE;
    }

    @Override
    protected void validateConfiguration(ActionDefinition definition) {
        errorMessage(definition);
    }

    @Override
    protected void processResult(
            ActionInvocation invocation,
            ActionDefinition definition,
            Object result) {
        if (Boolean.TRUE.equals(result)) {
            return;
        }
        throw new FlowableActionException(errorMessage(definition));
    }

    private String errorMessage(ActionDefinition definition) {
        return definition.nodes().stream()
                .filter(node -> "error-msg".equals(node.name()))
                .map(ActionNode::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElseThrow(() -> new FlowableActionException(
                        "Validation script " + definition.actionId()
                                + " is missing <error-msg>"));
    }
}
