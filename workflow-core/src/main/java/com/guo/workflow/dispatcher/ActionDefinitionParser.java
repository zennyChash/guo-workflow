package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionDefinition;

import java.util.List;

/**
 * Parses a node-bound action XML document.
 */
public interface ActionDefinitionParser {

    List<ActionDefinition> parse(String xml);
}

