package com.guo.workflow.web;

import com.guo.workflow.contract.exception.FlowableActionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class FlowableActionExceptionHandler {

    @ExceptionHandler(FlowableActionException.class)
    public ResponseEntity<ApiError> handleFlowableActionException(FlowableActionException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("FLOWABLE_ACTION_REJECTED", exception.getMessage()));
    }
}

