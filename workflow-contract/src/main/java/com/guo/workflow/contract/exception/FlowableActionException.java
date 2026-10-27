package com.guo.workflow.contract.exception;

/**
 * Business rejection raised by synchronous actions.
 */
public class FlowableActionException extends RuntimeException {

    public FlowableActionException(String message) {
        super(message);
    }

    public FlowableActionException(String message, Throwable cause) {
        super(message, cause);
    }
}

