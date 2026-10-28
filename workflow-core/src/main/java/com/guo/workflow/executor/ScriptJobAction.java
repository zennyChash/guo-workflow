package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionType;
import org.springframework.stereotype.Component;

/**
 * Executes synchronous or asynchronous business scripts.
 */
@Component
public class ScriptJobAction extends AbstractScriptAction {

    @Override
    public ActionType type() {
        return ActionType.JOB;
    }

    @Override
    protected void processResult(
            ActionInvocation invocation,
            ActionDefinition definition,
            Object result) {
        String variableName = ActionResultWriter.targetVariable(definition)
                .orElse("scriptResult");
        ActionResultWriter.write(
                invocation.context(),
                variableName,
                result);
    }
}
