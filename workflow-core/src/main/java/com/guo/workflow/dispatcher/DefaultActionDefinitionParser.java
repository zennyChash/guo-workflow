package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.Phase;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dom4j implementation of the node-bound action XML parser.
 */
@Component
public class DefaultActionDefinitionParser implements ActionDefinitionParser {

    private static final String ACTION_ELEMENT = "action";
    private static final String ACTIONS_ELEMENT = "actions";

    @Override
    public List<ActionDefinition> parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new FlowableActionException("Action configuration XML must not be blank");
        }

        try {
            Document document = createReader().read(new StringReader(xml));
            Element root = document.getRootElement();
            if (!ACTIONS_ELEMENT.equals(root.getName())) {
                throw new FlowableActionException(
                        "Action configuration root element must be <actions>");
            }

            List<ActionDefinition> definitions = new ArrayList<>();
            for (Element actionElement : root.elements(ACTION_ELEMENT)) {
                definitions.add(toDefinition(actionElement));
            }
            return List.copyOf(definitions);
        } catch (FlowableActionException exception) {
            throw exception;
        } catch (DocumentException | RuntimeException exception) {
            throw new FlowableActionException("Failed to parse action configuration XML", exception);
        }
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
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return reader;
        } catch (SAXException exception) {
            throw new IllegalStateException("Failed to configure secure XML parser", exception);
        }
    }

    private ActionDefinition toDefinition(Element actionElement) {
        parseEvent(requiredAttribute(actionElement, "event"));
        ActionType type = parseType(requiredAttribute(actionElement, "type"));
        ActionDoType doType = parseDoType(requiredAttribute(actionElement, "doType"));
        boolean synchronous = parseBoolean(actionElement.attributeValue("sync"), true);
        Phase phase = resolvePhase(
                type,
                doType,
                synchronous,
                actionElement.attributeValue("phase"));

        List<ActionNode> nodes = actionElement.elements().stream()
                .map(this::toNode)
                .toList();

        return new ActionDefinition(
                type,
                doType,
                synchronous,
                phase,
                blankToNull(actionElement.attributeValue("datasource")),
                attributesOf(actionElement),
                nodes);
    }

    private ActionNode toNode(Element element) {
        List<ActionNode> children = element.elements().stream()
                .map(this::toNode)
                .toList();
        return new ActionNode(
                element.getName(),
                element.getText().strip(),
                attributesOf(element),
                children);
    }

    private Map<String, String> attributesOf(Element element) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Attribute attribute : element.attributes()) {
            attributes.put(attribute.getName(), attribute.getValue());
        }
        return attributes;
    }

    private ActionEvent parseEvent(String value) {
        return ActionEvent.fromValue(value)
                .orElseThrow(() -> new FlowableActionException(
                        "Unsupported action event: " + value));
    }

    private ActionType parseType(String value) {
        return ActionType.fromValue(value)
                .orElseThrow(() -> new FlowableActionException(
                        "Unsupported action type: " + value));
    }

    private ActionDoType parseDoType(String value) {
        return ActionDoType.fromValue(value)
                .orElseThrow(() -> new FlowableActionException(
                        "Unsupported action doType: " + value));
    }

    private Phase resolvePhase(
            ActionType type,
            ActionDoType doType,
            boolean synchronous,
            String configuredPhase) {
        if (type == ActionType.VALIDATE) {
            if (doType != ActionDoType.SQL
                    && doType != ActionDoType.SCRIPT
                    && doType != ActionDoType.STORED_PROC) {
                throw new FlowableActionException(
                        "Validation actions only support SQL, SCRIPT or STORED_PROC doType: "
                                + doType.value());
            }
            if (!synchronous) {
                throw new FlowableActionException(
                        "Validation actions must set sync=true");
            }

            Phase phase = parsePhase(configuredPhase, Phase.BEFORE_COMMIT);
            if (phase != Phase.BEFORE_COMMIT) {
                throw new FlowableActionException(
                        "Validation actions must run in before-commit phase");
            }
            return Phase.BEFORE_COMMIT;
        }

        Phase phase = parsePhase(
                configuredPhase,
                synchronous ? Phase.BEFORE_COMMIT : Phase.AFTER_COMMIT);
        return synchronous ? phase : Phase.AFTER_COMMIT;
    }

    private Phase parsePhase(String value, Phase defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Phase.fromValue(value)
                .orElseThrow(() -> new FlowableActionException(
                        "Unsupported action phase: " + value));
    }

    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new FlowableActionException(
                    "Action sync attribute must be true or false: " + value);
        };
    }

    private String requiredAttribute(Element element, String name) {
        String value = blankToNull(element.attributeValue(name));
        if (value == null) {
            throw new FlowableActionException(
                    "Action element is missing required attribute: " + name);
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
