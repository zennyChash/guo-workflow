package com.guo.workflow.async;

import com.guo.workflow.contract.InstructionMsg;

/**
 * Publishes asynchronous instructions to the action log, Spring events or an MQ adapter.
 */
public interface InstructionPublisher {

    void publish(InstructionMsg message);
}

