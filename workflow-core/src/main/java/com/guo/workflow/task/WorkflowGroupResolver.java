package com.guo.workflow.task;

import java.util.List;

/**
 * Host integration point that expands a user into workflow candidate groups.
 */
public interface WorkflowGroupResolver {

    List<String> groupIds(String operatorId);
}
