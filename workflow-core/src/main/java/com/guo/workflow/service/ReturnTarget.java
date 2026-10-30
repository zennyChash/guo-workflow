package com.guo.workflow.service;

/**
 * A return target explicitly enabled by the current approval node.
 */
public record ReturnTarget(
        String activityId,
        String name) {
}
