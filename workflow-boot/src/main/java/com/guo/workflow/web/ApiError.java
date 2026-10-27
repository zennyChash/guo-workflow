package com.guo.workflow.web;

/**
 * Stable error envelope consumed by the frontend.
 */
public record ApiError(String code, String msg) {
}

