package com.guo.workflow.async;

import com.guo.workflow.contract.InstructionMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional development publisher that writes instructions to the application log.
 */
public class LogInstructionPublisher implements InstructionPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogInstructionPublisher.class);

    @Override
    public void publish(InstructionMsg message) {
        log.info("Instruction dispatched: {}", message);
    }
}
