package com.guo.workflow.business;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard result returned by a business operation adapter.
 */
public record BusinessOperationResult(
        boolean success,
        String systemCode,
        String businessId,
        String businessType,
        String businessTitle,
        String businessUrl,
        String message,
        Map<String, Object> resultData) {

    public BusinessOperationResult {
        resultData = resultData == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(resultData));
    }

    public static BusinessOperationResult success(
            String systemCode,
            String businessId,
            String businessType,
            String businessTitle,
            String businessUrl,
            String message,
            Map<String, Object> resultData) {
        return new BusinessOperationResult(
                true,
                systemCode,
                businessId,
                businessType,
                businessTitle,
                businessUrl,
                message,
                resultData);
    }

    public static BusinessOperationResult failure(String message) {
        return new BusinessOperationResult(
                false,
                null,
                null,
                null,
                null,
                null,
                message,
                Map.of());
    }
}
