package com.guo.workflow.approval;

import java.util.List;

/**
 * Non-deploying result of compiling and validating a design-time process.
 */
public record ApprovalProcessPreview(
        String processKey,
        String processName,
        String bpmnXml,
        String semanticJson,
        List<ApprovalNodeSpec> nodes,
        List<CompiledActionConfig> actionConfigs) {

    public ApprovalProcessPreview {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        actionConfigs = actionConfigs == null
                ? List.of()
                : List.copyOf(actionConfigs);
    }
}
