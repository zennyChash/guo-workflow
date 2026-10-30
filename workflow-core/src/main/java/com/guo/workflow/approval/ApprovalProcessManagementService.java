package com.guo.workflow.approval;

import com.guo.workflow.mirror.ProcessDefinitionCatalog;

import java.util.List;

/**
 * Validates, publishes and lists versioned approval process definitions.
 */
public interface ApprovalProcessManagementService {

    ApprovalProcessPreview preview(String sourceXml);

    PublishedApprovalProcess publish(String sourceXml, String tenantId);

    List<ProcessDefinitionCatalog> listVersions(
            String tenantId,
            String processKey);
}
