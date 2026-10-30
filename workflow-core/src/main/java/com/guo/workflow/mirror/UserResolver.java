package com.guo.workflow.mirror;

/**
 * Optional host extension that resolves a Flowable user id to a display name.
 */
@FunctionalInterface
public interface UserResolver {

    String resolve(String userId);
}
