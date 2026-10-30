package com.guo.workflow.mirror;

import java.time.Instant;

public record ProcessDefinitionCatalog(
        String processDefinitionId,
        String tenantId,
        String processKey,
        int version,
        String processName,
        String deploymentId,
        String sourceXml,
        String semanticJson,
        String status,
        Instant publishedTime,
        Instant createTime,
        Instant updateTime) {
}
