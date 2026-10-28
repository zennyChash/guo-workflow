package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Spring-backed implementation that resolves actions through an immutable type map.
 */
@Component
public class DefaultBusinessActionRegistry implements BusinessActionRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultBusinessActionRegistry.class);

    private final Map<String, BusinessAction> actions;

    public DefaultBusinessActionRegistry(ApplicationContext applicationContext) {
        this.actions = collectActions(applicationContext);
        log.info(
                "Registered {} business action(s): {}",
                actions.size(),
                actions.keySet());
    }

    @Override
    public BusinessAction resolve(ActionType type, ActionDoType doType) {
        if (type == null) {
            throw new FlowableActionException("Business action type must not be null");
        }
        if (doType == null) {
            throw new FlowableActionException("Business action doType must not be null");
        }

        String key = routeKey(type, doType);
        BusinessAction action = actions.get(key);
        if (action == null) {
            throw new FlowableActionException(
                    "No business action registered for route: " + key);
        }
        return action;
    }

    private Map<String, BusinessAction> collectActions(ApplicationContext applicationContext) {
        Objects.requireNonNull(applicationContext, "applicationContext must not be null");

        Map<String, BusinessAction> discovered = new LinkedHashMap<>();
        applicationContext.getBeansOfType(BusinessAction.class)
                .forEach((beanName, action) -> register(discovered, beanName, action));
        return Collections.unmodifiableMap(discovered);
    }

    private void register(
            Map<String, BusinessAction> discovered,
            String beanName,
            BusinessAction action) {
        ActionType type = action.type();
        if (type == null) {
            throw new IllegalStateException(
                    "Business action bean " + beanName + " returned a null type");
        }
        ActionDoType doType = action.doType();
        if (doType == null) {
            throw new IllegalStateException(
                    "Business action bean " + beanName + " returned a null doType");
        }

        String key = routeKey(type, doType);
        BusinessAction existing = discovered.putIfAbsent(key, action);
        if (existing != null) {
            throw new IllegalStateException(
                    "Duplicate business actions for route " + key
                            + ": " + beanName + " conflicts with another bean");
        }
        log.debug("Mapped business action bean={} to route={}", beanName, key);
    }

    private String routeKey(ActionType type, ActionDoType doType) {
        return type.value() + ":" + doType.value();
    }
}
