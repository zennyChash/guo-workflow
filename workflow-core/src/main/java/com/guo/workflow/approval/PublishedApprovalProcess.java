package com.guo.workflow.approval;

/**
 * Identifiers of a deployed and registered approval definition.
 */
public record PublishedApprovalProcess(
        String tenantId,
        String processKey,
        String processDefinitionId,
        String deploymentId,
        int version) {
}
