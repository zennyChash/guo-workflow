package com.guo.workflow.business;

/**
 * Host-provided adapter for one versioned business operation.
 */
public interface BusinessOperationHandler {

    String actionKey();

    int version();

    BusinessOperationResult execute(BusinessOperationRequest request) throws Exception;
}
