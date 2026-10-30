package com.guo.workflow.business;

import com.guo.workflow.contract.exception.FlowableActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring bean registry for business operation adapters.
 */
@Component
public class DefaultBusinessOperationRegistry implements BusinessOperationRegistry {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultBusinessOperationRegistry.class);

    private final Map<String, BusinessOperationHandler> handlers;

    public DefaultBusinessOperationRegistry(List<BusinessOperationHandler> handlers) {
        Map<String, BusinessOperationHandler> indexed = new LinkedHashMap<>();
        for (BusinessOperationHandler handler : handlers) {
            String actionKey = handler.actionKey();
            if (actionKey == null || actionKey.isBlank()) {
                throw new FlowableActionException(
                        "Business operation handler actionKey must not be blank");
            }
            if (handler.version() < 1) {
                throw new FlowableActionException(
                        "Business operation handler version must be positive: " + actionKey);
            }
            String route = route(actionKey, handler.version());
            BusinessOperationHandler existing = indexed.putIfAbsent(route, handler);
            if (existing != null) {
                throw new FlowableActionException(
                        "Duplicate business operation handler: " + route);
            }
        }
        this.handlers = Map.copyOf(indexed);
        log.info("Registered {} business operation handler(s): {}",
                this.handlers.size(), this.handlers.keySet());
    }

    @Override
    public BusinessOperationHandler resolve(String actionKey, int version) {
        BusinessOperationHandler handler = handlers.get(route(actionKey, version));
        if (handler == null) {
            throw new FlowableActionException(
                    "Business operation handler not found: "
                            + actionKey
                            + ":"
                            + version);
        }
        return handler;
    }

    private static String route(String actionKey, int version) {
        return actionKey + ":" + version;
    }
}
