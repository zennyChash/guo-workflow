package com.guo.workflow.contract;

/**
 * One lifecycle invocation and its node-bound XML configuration.
 */
public record ActionInvocation(
        ActionEvent event,
        ActionContext context,
        String configurationXml) {
}

