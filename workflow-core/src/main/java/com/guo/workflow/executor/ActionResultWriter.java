package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionContext;
import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.exception.FlowableActionException;

import java.util.Optional;

/**
 * Resolves and writes the optional {@code <result var="..."/>} target variable.
 */
final class ActionResultWriter {

    private static final String RESULT_NODE = "result";
    private static final String VARIABLE_ATTRIBUTE = "var";

    private ActionResultWriter() {
    }

    static Optional<String> targetVariable(ActionDefinition definition) {
        return definition.nodes().stream()
                .filter(node -> RESULT_NODE.equals(node.name()))
                .findFirst()
                .map(ActionResultWriter::requiredVariableName);
    }

    static void write(
            ActionContext context,
            String variableName,
            Object result) {
        context.variables().put(variableName, result);
    }

    private static String requiredVariableName(ActionNode resultNode) {
        String variableName = resultNode.attributes().get(VARIABLE_ATTRIBUTE);
        if (variableName == null || variableName.isBlank()) {
            throw new FlowableActionException(
                    "Result node must configure a non-blank var attribute");
        }
        return variableName.trim();
    }
}
