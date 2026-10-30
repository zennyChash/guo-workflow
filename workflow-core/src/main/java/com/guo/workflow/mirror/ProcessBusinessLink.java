package com.guo.workflow.mirror;

import java.time.Instant;

public record ProcessBusinessLink(
        String linkId,
        String processInstanceId,
        String tenantId,
        String systemCode,
        String businessType,
        String businessId,
        String businessKey,
        String businessTitle,
        String relationType,
        String businessSyncStatus,
        String businessUrl,
        Instant createTime,
        Instant updateTime) {
}
