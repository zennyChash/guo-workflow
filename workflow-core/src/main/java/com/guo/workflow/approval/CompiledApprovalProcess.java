package com.guo.workflow.approval;

import java.util.List;

/**
 * Result of compiling a design-time guo process definition.
 */
public record CompiledApprovalProcess(
        String processKey,
        String processName,
        String sourceXml,
        String bpmnXml,
        String semanticJson,
        List<ApprovalNodeSpec> nodes,
        List<CompiledActionConfig> actionConfigs) {

    public CompiledApprovalProcess {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        actionConfigs = actionConfigs == null
                ? List.of()
                : List.copyOf(actionConfigs);
    }
}
