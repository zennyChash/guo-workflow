package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.BusinessAction;
import com.guo.workflow.contract.InstructionMsg;
import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.async.InstructionPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Default lifecycle dispatcher. Synchronous actions run before instructions are published.
 */
@Component
public class DefaultActionDispatcher implements ActionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DefaultActionDispatcher.class);

    private final ActionDefinitionParser parser;
    private final BusinessActionRegistry registry;
    private final InstructionPublisher publisher;

    public DefaultActionDispatcher(
            ActionDefinitionParser parser,
            BusinessActionRegistry registry,
            InstructionPublisher publisher) {
        this.parser = parser;
        this.registry = registry;
        this.publisher = publisher;
    }

    @Override
    public void dispatch(ActionInvocation invocation) {
        requireInvocation(invocation);

        List<ActionDefinition> definitions;
        try {
            definitions = parser.parse(invocation.configurationXml());
        } catch (FlowableActionException exception) {
            log.warn(
                    "Action XML rejected for event={}, businessKey={}: {}",
                    invocation.event(),
                    invocation.context().businessKey(),
                    exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            log.error(
                    "Unexpected error while parsing action XML for event={}, businessKey={}",
                    invocation.event(),
                    invocation.context().businessKey(),
                    exception);
            throw new FlowableActionException("Failed to parse action configuration", exception);
        }

        List<ActionDefinition> lifecycleActions = definitions.stream()
                .filter(definition -> definition.event() == invocation.event())
                .toList();
        log.info(
                "Dispatching {} action(s) for event={}, businessKey={}, processInstanceId={}",
                lifecycleActions.size(),
                invocation.event(),
                invocation.context().businessKey(),
                invocation.context().processInstanceId());

        for (ActionDefinition definition : lifecycleActions) {
            if (definition.synchronous()) {
                executeSynchronous(invocation, definition);
            }
        }
        for (ActionDefinition definition : lifecycleActions) {
            if (!definition.synchronous()) {
                publishInstruction(invocation, definition);
            }
        }
    }

    private void executeSynchronous(ActionInvocation invocation, ActionDefinition definition) {
        log.info(
                "Executing synchronous action actionId={}, type={}, businessKey={}",
                definition.actionId(),
                definition.type(),
                invocation.context().businessKey());

        try {
            BusinessAction action = registry.resolve(definition.type(), definition.doType());
            action.execute(invocation, definition);
            log.info(
                    "Synchronous action completed actionId={}, type={}, businessKey={}",
                    definition.actionId(),
                    definition.type(),
                    invocation.context().businessKey());
        } catch (FlowableActionException exception) {
            log.warn(
                    "Synchronous action rejected actionId={}, type={}, businessKey={}, reason={}",
                    definition.actionId(),
                    definition.type(),
                    invocation.context().businessKey(),
                    exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            log.error(
                    "Synchronous action failed actionId={}, type={}, businessKey={}",
                    definition.actionId(),
                    definition.type(),
                    invocation.context().businessKey(),
                    exception);
            throw new FlowableActionException(
                    "Synchronous action failed: " + definition.actionId(),
                    exception);
        }
    }

    private void publishInstruction(ActionInvocation invocation, ActionDefinition definition) {
        String taskIdentity = String.join(
                "|",
                invocation.context().businessKey(),
                invocation.context().processInstanceId(),
                invocation.context().taskId() == null
                        ? ""
                        : invocation.context().taskId(),
                invocation.context().activityId(),
                invocation.event().value(),
                definition.type().value(),
                definition.actionId());
        String taskId = UUID.nameUUIDFromBytes(
                taskIdentity.getBytes(StandardCharsets.UTF_8)).toString();
        InstructionMsg instruction = new InstructionMsg(
                taskId,
                invocation.context().businessKey(),
                invocation.event(),
                definition.type(),
                invocation,
                definition);

        try {
            log.info(
                    "Publishing instruction taskId={}, event={}, type={}, businessKey={}",
                    instruction.taskId(),
                    instruction.event(),
                    instruction.type(),
                    instruction.businessKey());
            publisher.publish(instruction);
            log.info(
                    "Instruction published taskId={}, event={}, type={}, businessKey={}",
                    instruction.taskId(),
                    instruction.event(),
                    instruction.type(),
                    instruction.businessKey());
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to publish instruction taskId={}, event={}, type={}, businessKey={}",
                    instruction.taskId(),
                    instruction.event(),
                    instruction.type(),
                    instruction.businessKey(),
                    exception);
            throw new FlowableActionException(
                    "Failed to publish instruction: " + instruction.taskId(),
                    exception);
        }
    }

    private void requireInvocation(ActionInvocation invocation) {
        if (invocation == null) {
            throw new FlowableActionException("Action invocation must not be null");
        }
        if (invocation.event() == null) {
            throw new FlowableActionException("Action invocation event must not be null");
        }
        if (invocation.context() == null) {
            throw new FlowableActionException("Action invocation context must not be null");
        }
        if (invocation.configurationXml() == null || invocation.configurationXml().isBlank()) {
            throw new FlowableActionException("Action invocation XML must not be blank");
        }
    }
}
