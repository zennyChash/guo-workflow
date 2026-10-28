package com.guo.workflow.executor;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionInvocation;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.BusinessAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Development message action used to verify the asynchronous execution chain.
 */
@Component
public class MessageAction implements BusinessAction {

    private static final Logger log = LoggerFactory.getLogger(MessageAction.class);

    @Override
    public ActionType type() {
        return ActionType.JOB;
    }

    @Override
    public ActionDoType doType() {
        return ActionDoType.MESSAGE;
    }

    @Override
    public void execute(ActionInvocation invocation, ActionDefinition definition) {
        String target = nodeText(definition, "target", "unknown");
        String template = nodeText(definition, "template", "（无消息内容）");
        log.info("【模拟发送消息】目标: {}, 内容: {}", target, template);
    }

    private String nodeText(ActionDefinition definition, String name, String defaultValue) {
        return definition.nodes().stream()
                .filter(node -> name.equals(node.name()))
                .map(ActionNode::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(defaultValue);
    }
}
