package com.guo.workflow.dispatcher;

import com.guo.workflow.contract.ActionDefinition;
import com.guo.workflow.contract.ActionDoType;
import com.guo.workflow.contract.ActionEvent;
import com.guo.workflow.contract.ActionNode;
import com.guo.workflow.contract.ActionType;
import com.guo.workflow.contract.Phase;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultActionDefinitionParserTest {

    private final DefaultActionDefinitionParser parser = new DefaultActionDefinitionParser();

    @Test
    void parsesActionMetadataAndNestedNodes() {
        String xml = """
                <actions>
                  <action id="validate-credit"
                          event="complete"
                          type="validate"
                          doType="sql"
                          sync="true"
                          phase="before-commit"
                          datasource="biz_db">
                    <rule type="sql">
                      <sql><![CDATA[SELECT available_amount FROM t_credit]]></sql>
                      <expect>result >= 100</expect>
                      <error-msg>Insufficient credit</error-msg>
                    </rule>
                  </action>
                  <action id="notify-sales"
                          event="complete"
                          type="job"
                          doType="message"
                          sync="false">
                    <message channel="dingtalk" to="#{businessKey}">
                      <title>Order approved</title>
                      <content>Order #{businessKey} approved</content>
                    </message>
                  </action>
                </actions>
                """;

        List<ActionDefinition> definitions = parser.parse(xml);

        assertEquals(2, definitions.size());

        ActionDefinition validation = definitions.get(0);
        assertEquals("validate-credit", validation.actionId());
        assertEquals(ActionEvent.COMPLETE, validation.event());
        assertEquals(ActionType.VALIDATE, validation.type());
        assertEquals(ActionDoType.SQL, validation.doType());
        assertTrue(validation.synchronous());
        assertEquals(Phase.BEFORE_COMMIT, validation.phase());
        assertEquals("biz_db", validation.dataSource());
        assertEquals(1, validation.nodes().size());

        ActionNode rule = validation.nodes().get(0);
        assertEquals("rule", rule.name());
        assertEquals("sql", rule.attributes().get("type"));
        assertFalse(rule.children().isEmpty());
        assertTrue(rule.children().get(0).text().startsWith("SELECT available_amount"));

        ActionDefinition messageJob = definitions.get(1);
        assertEquals(ActionType.JOB, messageJob.type());
        assertEquals(ActionDoType.MESSAGE, messageJob.doType());
        assertFalse(messageJob.synchronous());
        assertEquals(Phase.AFTER_COMMIT, messageJob.phase());
        assertEquals("message", messageJob.nodes().get(0).name());
        assertEquals("dingtalk", messageJob.nodes().get(0).attributes().get("channel"));
        assertEquals("Order approved", messageJob.nodes().get(0).children().get(0).text());
    }

    @Test
    void generatesStableIdWhenActionIdIsMissing() {
        String xml = """
                <actions>
                  <action event="delete"
                          type="job"
                          doType="sql"
                          sync="true"
                          phase="before-commit">
                    <sql>UPDATE t_order SET status = 'DRAFT'</sql>
                  </action>
                </actions>
                """;

        ActionDefinition definition = parser.parse(xml).get(0);
        String firstId = definition.actionId();
        String secondId = parser.parse(xml).get(0).actionId();

        assertEquals(ActionEvent.DELETE, definition.event());
        assertEquals(firstId, secondId);
        assertFalse(firstId.isBlank());
    }

    @Test
    void exposesFlowablePhysicalEvents() {
        assertEquals(
                List.of("create", "assignment", "complete", "delete", "take"),
                Arrays.stream(ActionEvent.values())
                        .map(ActionEvent::value)
                        .toList());
    }

    @Test
    void rejectsUnsupportedActionType() {
        String xml = """
                <actions>
                  <action event="complete"
                          type="unknown"
                          doType="sql"
                          sync="true"/>
                </actions>
                """;

        assertThrows(FlowableActionException.class, () -> parser.parse(xml));
    }

    @Test
    void rejectsBlankXml() {
        assertThrows(FlowableActionException.class, () -> parser.parse(" "));
    }

    @Test
    void rejectsMessageValidationAction() {
        String xml = """
                <actions>
                  <action event="complete"
                          type="validate"
                          doType="message"
                          sync="true"/>
                </actions>
                """;

        assertThrows(FlowableActionException.class, () -> parser.parse(xml));
    }

    @Test
    void rejectsAsynchronousValidationAction() {
        String xml = """
                <actions>
                  <action event="complete"
                          type="validate"
                          doType="script"
                          sync="false"/>
                </actions>
                """;

        assertThrows(FlowableActionException.class, () -> parser.parse(xml));
    }

    @Test
    void rejectsAfterCommitValidationAction() {
        String xml = """
                <actions>
                  <action event="complete"
                          type="validate"
                          doType="sql"
                          sync="true"
                          phase="after-commit"/>
                </actions>
                """;

        assertThrows(FlowableActionException.class, () -> parser.parse(xml));
    }

    @Test
    void forcesAsynchronousJobIntoAfterCommitPhase() {
        String xml = """
                <actions>
                  <action event="complete"
                          type="job"
                          doType="export"
                          sync="false"
                          phase="before-commit"/>
                </actions>
                """;

        ActionDefinition definition = parser.parse(xml).get(0);

        assertEquals(ActionType.JOB, definition.type());
        assertEquals(ActionDoType.EXPORT, definition.doType());
        assertFalse(definition.synchronous());
        assertEquals(Phase.AFTER_COMMIT, definition.phase());
    }

    @Test
    void keepsConfiguredAfterCommitPhaseForSynchronousJob() {
        String xml = """
                <actions>
                  <action event="complete"
                          type="job"
                          doType="sql"
                          sync="true"
                          phase="after-commit"/>
                </actions>
                """;

        ActionDefinition definition = parser.parse(xml).get(0);

        assertTrue(definition.synchronous());
        assertEquals(Phase.AFTER_COMMIT, definition.phase());
    }

    @Test
    void rejectsJobWithoutDoType() {
        String xml = """
                <actions>
                  <action event="complete"
                          type="job"
                          sync="true"/>
                </actions>
                """;

        assertThrows(FlowableActionException.class, () -> parser.parse(xml));
    }
}
