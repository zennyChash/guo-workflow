package com.guo.workflow.business;

/**
 * Transport boundary used by the workflow engine to invoke business operations.
 */
public interface BusinessOperationGateway {

    BusinessOperationResult invoke(BusinessOperationRequest request) throws Exception;
}
