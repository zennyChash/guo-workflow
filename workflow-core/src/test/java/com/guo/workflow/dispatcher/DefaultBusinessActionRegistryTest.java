package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultBusinessActionRegistryTest {

    @Test
    void collectsBusinessActionsFromApplicationContextByTypeAndDoType() {
        EmptyAction sqlAction = new EmptyAction(ActionType.JOB, ActionDoType.SQL);
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("sqlAction", sqlAction);
        context.refresh();

        DefaultBusinessActionRegistry registry = new DefaultBusinessActionRegistry(context);

        assertSame(sqlAction, registry.resolve(ActionType.JOB, ActionDoType.SQL));
    }

    @Test
    void routesSameTypeToDifferentDoTypesIndependently() {
        EmptyAction sqlAction = new EmptyAction(ActionType.JOB, ActionDoType.SQL);
        EmptyAction messageAction = new EmptyAction(ActionType.JOB, ActionDoType.MESSAGE);
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("sqlAction", sqlAction);
        context.getBeanFactory().registerSingleton("messageAction", messageAction);
        context.refresh();

        DefaultBusinessActionRegistry registry = new DefaultBusinessActionRegistry(context);

        assertSame(sqlAction, registry.resolve(ActionType.JOB, ActionDoType.SQL));
        assertSame(messageAction, registry.resolve(ActionType.JOB, ActionDoType.MESSAGE));
    }

    @Test
    void rejectsDuplicateActionTypes() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton(
                "firstSqlAction",
                new EmptyAction(ActionType.JOB, ActionDoType.SQL));
        context.getBeanFactory().registerSingleton(
                "secondSqlAction",
                new EmptyAction(ActionType.JOB, ActionDoType.SQL));
        context.refresh();

        assertThrows(
                IllegalStateException.class,
                () -> new DefaultBusinessActionRegistry(context));
    }

    @Test
    void rejectsUnregisteredActionType() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();
        DefaultBusinessActionRegistry registry = new DefaultBusinessActionRegistry(context);

        assertThrows(
                FlowableActionException.class,
                () -> registry.resolve(ActionType.JOB, ActionDoType.MESSAGE));
    }
}
