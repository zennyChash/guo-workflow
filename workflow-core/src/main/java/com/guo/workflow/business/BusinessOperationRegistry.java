package com.guo.workflow.business;

/**
 * Resolves business operation adapters by action key and contract version.
 */
public interface BusinessOperationRegistry {

    BusinessOperationHandler resolve(String actionKey, int version);
}
