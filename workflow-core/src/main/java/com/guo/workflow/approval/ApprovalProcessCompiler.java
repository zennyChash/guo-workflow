package com.guo.workflow.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guo.workflow.contract.ActionTriggerScope;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.dom4j.io.SAXReader;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles design-time guo approval extensions into standard Flowable BPMN.
 */
@Component
public class ApprovalProcessCompiler {

    private static final String APPROVAL_NAMESPACE =
            "http://guo.workflow/schema/approval";
    private static final String BPMN_NAMESPACE =
            "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String FLOWABLE_NAMESPACE =
            "http://flowable.org/bpmn";

    private final ObjectMapper objectMapper;

    public ApprovalProcessCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompiledApprovalProcess compile(String sourceXml) {
        if (sourceXml == null || sourceXml.isBlank()) {
            throw new FlowableActionException(
                    "Approval process source XML must not be blank");
        }
        try {
            Document document = createReader().read(new StringReader(sourceXml));
            Element definitions = document.getRootElement();
            if (definitions.getNamespaceForPrefix("flowable") == null) {
                definitions.addNamespace("flowable", FLOWABLE_NAMESPACE);
            }
            List<Element> processes = descendants(definitions, "process");
            if (processes.size() != 1) {
                throw new FlowableActionException(
                        "Approval process source must contain exactly one process");
            }
            Element process = processes.get(0);
            String processKey = requiredAttribute(process, "id");
            String processName = process.attributeValue("name");

            List<ApprovalNodeSpec> nodes = new ArrayList<>();
            List<CompiledActionConfig> actionConfigs = new ArrayList<>();
            for (Element userTask : descendants(process, "userTask")) {
                List<Element> approvals = descendants(userTask, "approval").stream()
                        .filter(this::isApprovalElement)
                        .toList();
                if (approvals.isEmpty()) {
                    continue;
                }
                if (approvals.size() != 1) {
                    throw new FlowableActionException(
                            "User task must configure only one guo:approval: "
                                    + userTask.attributeValue("id"));
                }
                Element approval = approvals.get(0);
                ApprovalNodeSpec spec = parseNode(process, userTask, approval);
                nodes.add(spec);
                applyApprovalSemantics(userTask, spec);
                injectTaskListeners(userTask);
                approval.getParent().remove(approval);
                actionConfigs.addAll(compileNotificationActions(spec));
            }

            String semanticJson = semanticJson(processKey, processName, nodes);
            return new CompiledApprovalProcess(
                    processKey,
                    processName,
                    sourceXml,
                    document.asXML(),
                    semanticJson,
                    nodes,
                    actionConfigs);
        } catch (FlowableActionException exception) {
            throw exception;
        } catch (DocumentException | RuntimeException exception) {
            throw new FlowableActionException(
                    "Failed to compile approval process XML",
                    exception);
        }
    }

    private ApprovalNodeSpec parseNode(
            Element process,
            Element userTask,
            Element approval) {
        String activityId = requiredAttribute(userTask, "id");
        String nodeName = valueOrDefault(
                userTask.attributeValue("name"),
                activityId);
        ApprovalMode mode = ApprovalMode.fromValue(approval.attributeValue("mode"));
        String approvers = blankToNull(approval.attributeValue("approvers"));
        if (approvers == null) {
            approvers = approval.attributeValue(
                    QName.get("assignee", FLOWABLE_NAMESPACE));
        }
        if (approvers == null) {
            throw new FlowableActionException(
                    "guo:approval approvers is required: " + activityId);
        }

        boolean sequential = parseBoolean(
                approval.attributeValue("sequential"),
                false);
        CompletionPolicy completionPolicy = CompletionPolicy.fromValue(
                approval.attributeValue("completionPolicy"));
        BigDecimal threshold = parseThreshold(
                completionPolicy,
                approval.attributeValue("threshold"));
        boolean commentRequired = parseBoolean(
                approval.attributeValue("commentRequired"),
                false);
        String rejectPolicy = blankToNull(approval.attributeValue("rejectPolicy"));
        List<ApprovalReturnTarget> returnTargets =
                parseReturnTargets(process, activityId, approval);
        List<NotificationSpec> notifications = parseNotifications(approval);

        return new ApprovalNodeSpec(
                activityId,
                nodeName,
                mode,
                approvers,
                sequential,
                completionPolicy,
                threshold,
                commentRequired,
                rejectPolicy,
                returnTargets,
                notifications);
    }

    private void applyApprovalSemantics(Element userTask, ApprovalNodeSpec spec) {
        removeChildren(userTask, "multiInstanceLoopCharacteristics");
        var candidateUsers = userTask.attribute(
                QName.get("candidateUsers", FLOWABLE_NAMESPACE));
        if (candidateUsers != null) {
            userTask.remove(candidateUsers);
        }

        switch (spec.mode()) {
            case SINGLE -> userTask.addAttribute(
                    QName.get("assignee", "flowable", FLOWABLE_NAMESPACE),
                    spec.approvers());
            case OR_SIGN -> userTask.addAttribute(
                    QName.get("candidateUsers", "flowable", FLOWABLE_NAMESPACE),
                    spec.approvers());
            case COUNTERSIGN -> applyCountersign(userTask, spec);
        }
    }

    private void applyCountersign(Element userTask, ApprovalNodeSpec spec) {
        if (spec.completionPolicy() == CompletionPolicy.VETO) {
            throw new FlowableActionException(
                    "VETO completion requires runtime vote handling and is not supported by static compilation yet: "
                            + spec.activityId());
        }

        userTask.addAttribute(
                QName.get("assignee", "flowable", FLOWABLE_NAMESPACE),
                "${currentApprover}");
        Element multiInstance = userTask.addElement(
                QName.get("multiInstanceLoopCharacteristics", "", BPMN_NAMESPACE));
        multiInstance.addAttribute("isSequential", String.valueOf(spec.sequential()));
        multiInstance.addAttribute(
                QName.get("collection", "flowable", FLOWABLE_NAMESPACE),
                spec.approvers());
        multiInstance.addAttribute(
                QName.get("elementVariable", "flowable", FLOWABLE_NAMESPACE),
                "currentApprover");

        String completionCondition = switch (spec.completionPolicy()) {
            case ALL -> null;
            case ANY -> "${nrOfCompletedInstances >= 1}";
            case PERCENTAGE ->
                    "${nrOfCompletedInstances / nrOfInstances >= "
                            + spec.threshold().stripTrailingZeros().toPlainString()
                            + "}";
            case VETO -> null;
        };
        if (completionCondition != null) {
            multiInstance.addElement(
                            QName.get("completionCondition", "", BPMN_NAMESPACE))
                    .addText(completionCondition);
        }
    }

    private void injectTaskListeners(Element userTask) {
        Element extensionElements = firstChild(userTask, "extensionElements");
        if (extensionElements == null) {
            extensionElements = userTask.addElement("extensionElements");
        }
        addTaskListenerIfMissing(extensionElements, "assignment");
        addTaskListenerIfMissing(extensionElements, "complete");
        addTaskListenerIfMissing(extensionElements, "delete");
    }

    private void addTaskListenerIfMissing(Element extensionElements, String event) {
        for (Element listener : extensionElements.elements()) {
            if ("taskListener".equals(listener.getName())
                    && event.equals(listener.attributeValue("event"))
                    && "${actionTaskListener}".equals(
                            listener.attributeValue("delegateExpression"))) {
                return;
            }
        }
        Element listener = extensionElements.addElement(
                QName.get("taskListener", "flowable", FLOWABLE_NAMESPACE));
        listener.addAttribute("event", event);
        listener.addAttribute("delegateExpression", "${actionTaskListener}");
    }

    private List<CompiledActionConfig> compileNotificationActions(
            ApprovalNodeSpec node) {
        Map<ActionGroup, List<NotificationSpec>> groups = new LinkedHashMap<>();
        for (NotificationSpec notification : node.notifications()) {
            ActionTriggerScope scope = notification.trigger().scope();
            String event = notification.trigger().event();
            if (notification.trigger() == NotificationTrigger.NODE_COMPLETE
                    && node.mode() != ApprovalMode.COUNTERSIGN) {
                scope = ActionTriggerScope.TASK;
                event = "complete";
            }
            ActionGroup group = new ActionGroup(
                    scope,
                    event);
            groups.computeIfAbsent(group, ignored -> new ArrayList<>())
                    .add(notification);
        }

        List<CompiledActionConfig> configs = new ArrayList<>();
        for (Map.Entry<ActionGroup, List<NotificationSpec>> entry : groups.entrySet()) {
            configs.add(new CompiledActionConfig(
                    node.activityId(),
                    entry.getKey().scope(),
                    entry.getKey().event(),
                    notificationActionXml(node, entry.getKey(), entry.getValue())));
        }
        return configs;
    }

    private String notificationActionXml(
            ApprovalNodeSpec node,
            ActionGroup group,
            List<NotificationSpec> notifications) {
        Document document = DocumentHelper.createDocument();
        Element actions = document.addElement("actions");
        int index = 1;
        for (NotificationSpec notification : notifications) {
            Element action = actions.addElement("action");
            action.addAttribute(
                    "id",
                    "guo-notify-"
                            + safeId(node.activityId())
                            + "-"
                            + group.event()
                            + "-"
                            + index++);
            action.addAttribute("event", group.event());
            action.addAttribute("type", "job");
            action.addAttribute("doType", "message");
            action.addAttribute("sync", "false");

            Element message = action.addElement("message");
            message.addAttribute("channel", notification.channel());
            message.addAttribute("to", notification.recipient());
            message.addAttribute(
                    "format",
                    valueOrDefault(notification.format(), "text"));
            if (notification.title() != null) {
                message.addElement("title").addText(notification.title());
            }
            message.addElement("content").addText(notification.content());
        }
        return document.asXML();
    }

    private List<NotificationSpec> parseNotifications(Element approval) {
        List<NotificationSpec> notifications = new ArrayList<>();
        for (Element element : approval.elements()) {
            if (!"notification".equals(element.getName())) {
                continue;
            }
            NotificationTrigger trigger = NotificationTrigger.fromValue(
                    element.attributeValue("trigger"));
            String channel = requiredAttribute(element, "channel");
            String recipient = requiredAttribute(element, "to");
            String content = childText(element, "content");
            if (content == null) {
                throw new FlowableActionException(
                        "guo:notification content is required for trigger: " + trigger);
            }
            notifications.add(new NotificationSpec(
                    trigger,
                    channel,
                    recipient,
                    valueOrDefault(element.attributeValue("format"), "text"),
                    childText(element, "title"),
                    content));
        }
        return notifications;
    }

    private List<ApprovalReturnTarget> parseReturnTargets(
            Element process,
            String sourceActivityId,
            Element approval) {
        Set<String> targetIds = new LinkedHashSet<>();
        for (Element returnElement : approval.elements()) {
            if (!"return".equals(returnElement.getName())) {
                continue;
            }
            String configuredTargets = blankToNull(returnElement.attributeValue("to"));
            if (configuredTargets == null) {
                configuredTargets = blankToNull(returnElement.attributeValue("targets"));
            }
            if (configuredTargets == null) {
                throw new FlowableActionException(
                        "guo:return must configure at least one target: "
                                + sourceActivityId);
            }
            for (String targetId : configuredTargets.split(",")) {
                String normalized = targetId.trim();
                if (!normalized.isEmpty()) {
                    targetIds.add(normalized);
                }
            }
        }

        List<ApprovalReturnTarget> targets = new ArrayList<>();
        for (String targetId : targetIds) {
            if (targetId.equals(sourceActivityId)) {
                throw new FlowableActionException(
                        "Approval node cannot return to itself: " + sourceActivityId);
            }
            Element targetTask = descendants(process, "userTask").stream()
                    .filter(task -> targetId.equals(task.attributeValue("id")))
                    .findFirst()
                    .orElseThrow(() -> new FlowableActionException(
                            "Approval return target does not exist: " + targetId));
            targets.add(new ApprovalReturnTarget(
                    targetId,
                    valueOrDefault(targetTask.attributeValue("name"), targetId)));
        }
        return targets;
    }

    private String semanticJson(
            String processKey,
            String processName,
            List<ApprovalNodeSpec> nodes) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "processKey", processKey,
                    "processName", processName == null ? processKey : processName,
                    "nodes", nodes));
        } catch (JsonProcessingException exception) {
            throw new FlowableActionException(
                    "Failed to serialize approval semantics",
                    exception);
        }
    }

    private BigDecimal parseThreshold(
            CompletionPolicy policy,
            String value) {
        if (policy != CompletionPolicy.PERCENTAGE) {
            return null;
        }
        if (value == null || value.isBlank()) {
            throw new FlowableActionException(
                    "PERCENTAGE completion policy requires threshold");
        }
        try {
            BigDecimal threshold = new BigDecimal(value.trim());
            if (threshold.signum() <= 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
                throw new NumberFormatException();
            }
            return threshold;
        } catch (NumberFormatException exception) {
            throw new FlowableActionException(
                    "Approval threshold must be greater than 0 and at most 1: "
                            + value);
        }
    }

    private List<Element> descendants(Element parent, String localName) {
        List<Element> elements = new ArrayList<>();
        collectDescendants(parent, localName, elements);
        return elements;
    }

    private void collectDescendants(
            Element parent,
            String localName,
            List<Element> result) {
        for (Element element : parent.elements()) {
            if (localName.equals(element.getName())) {
                result.add(element);
            }
            collectDescendants(element, localName, result);
        }
    }

    private List<Element> childrenByLocalName(Element parent, String localName) {
        return parent.elements().stream()
                .filter(element -> localName.equals(element.getName()))
                .toList();
    }

    private boolean isApprovalElement(Element element) {
        return APPROVAL_NAMESPACE.equals(element.getNamespaceURI());
    }

    private Element firstChild(Element parent, String localName) {
        return parent.elements().stream()
                .filter(element -> localName.equals(element.getName()))
                .findFirst()
                .orElse(null);
    }

    private String childText(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        return child == null || child.getText().isBlank()
                ? null
                : child.getText().strip();
    }

    private void removeChildren(Element parent, String localName) {
        List<Element> matches = childrenByLocalName(parent, localName);
        for (Element element : matches) {
            parent.remove(element);
        }
    }

    private String requiredAttribute(Element element, String name) {
        String value = blankToNull(element.attributeValue(name));
        if (value == null) {
            throw new FlowableActionException(
                    "BPMN element is missing required attribute: " + name);
        }
        return value;
    }

    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new FlowableActionException(
                    "Approval boolean attribute must be true or false: " + value);
        };
    }

    private String safeId(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SAXReader createReader() {
        SAXReader reader = new SAXReader();
        try {
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            reader.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            reader.setEntityResolver((publicId, systemId) ->
                    new InputSource(new StringReader("")));
            return reader;
        } catch (SAXException exception) {
            throw new IllegalStateException(
                    "Failed to configure secure approval XML parser",
                    exception);
        }
    }

    private record ActionGroup(ActionTriggerScope scope, String event) {
    }
}
