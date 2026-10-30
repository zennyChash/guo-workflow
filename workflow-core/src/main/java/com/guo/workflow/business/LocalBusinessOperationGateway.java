package com.guo.workflow.business;

import org.springframework.stereotype.Component;

/**
 * In-process implementation used while the engine is embedded in the ERP.
 */
@Component
public class LocalBusinessOperationGateway implements BusinessOperationGateway {

    private final BusinessOperationRegistry registry;

    public LocalBusinessOperationGateway(BusinessOperationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public BusinessOperationResult invoke(BusinessOperationRequest request) throws Exception {
        return registry.resolve(request.actionKey(), request.version()).execute(request);
    }
}
