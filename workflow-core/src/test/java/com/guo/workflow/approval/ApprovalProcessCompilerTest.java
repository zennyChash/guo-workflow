package com.guo.workflow.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guo.workflow.contract.ActionTriggerScope;
import com.guo.workflow.contract.exception.FlowableActionException;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalProcessCompilerTest {

    private final ApprovalProcessCompiler compiler =
            new ApprovalProcessCompiler(new ObjectMapper());

    @Test
    void compilesCountersignAndNotificationsIntoStandardBpmn() {
        CompiledApprovalProcess compiled = compiler.compile(sourceXml("""
                <guo:approval mode="COUNTERSIGN"
                              completionPolicy="ALL"
                              sequential="false"
                              approvers="${financeApprovers}"
                              commentRequired="true"
                              rejectPolicy="TO_INITIATOR">
                  <guo:return to="departmentApproval" />
                  <guo:notification trigger="assignment"
                                    channel="dingtalk"
                                    to="#{currentApprover}">
                    <guo:title>待办提醒</guo:title>
                    <guo:content>业务 #{businessKey} 待审批</guo:content>
                  </guo:notification>
                  <guo:notification trigger="submitted"
                                    channel="dingtalk"
                                    to="#{initiatorId}">
                    <guo:content>#{currentApprover} 已提交</guo:content>
                  </guo:notification>
                  <guo:notification trigger="node-completed"
                                    channel="dingtalk"
                                    to="#{initiatorId}">
                    <guo:content>业务 #{businessKey} 会签完成</guo:content>
                  </guo:notification>
                </guo:approval>
                """));

        assertEquals("purchaseApproval", compiled.processKey());
        assertEquals(1, compiled.nodes().size());
        assertEquals(ApprovalMode.COUNTERSIGN, compiled.nodes().get(0).mode());
        assertEquals(
                "departmentApproval",
                compiled.nodes().get(0).allowedReturnTargets().get(0).activityId());
        assertTrue(compiled.bpmnXml().contains("multiInstanceLoopCharacteristics"));
        assertTrue(compiled.bpmnXml().contains("${actionTaskListener}"));
        assertTrue(compiled.bpmnXml().contains("event=\"delete\""));
        assertFalse(compiled.bpmnXml().contains("guo:approval"));
        assertFalse(compiled.bpmnXml().contains("guo:notification"));

        assertEquals(3, compiled.actionConfigs().size());
        assertTrue(compiled.actionConfigs().stream()
                .anyMatch(action -> action.scope() == ActionTriggerScope.TASK
                        && "assignment".equals(action.event())));
        assertTrue(compiled.actionConfigs().stream()
                .anyMatch(action -> action.scope() == ActionTriggerScope.TASK
                        && "complete".equals(action.event())));
        assertTrue(compiled.actionConfigs().stream()
                .anyMatch(action -> action.scope() == ActionTriggerScope.NODE
                        && "complete".equals(action.event())));

        BpmnModel model = new BpmnXMLConverter().convertToBpmnModel(
                () -> new ByteArrayInputStream(
                        compiled.bpmnXml().getBytes(StandardCharsets.UTF_8)),
                true,
                false);
        assertNotNull(model.getProcessById("purchaseApproval"));
        UserTask task = (UserTask) model.getMainProcess()
                .getFlowElement("financeApproval");
        MultiInstanceLoopCharacteristics loop = task.getLoopCharacteristics();
        assertNotNull(loop);
        assertEquals("${financeApprovers}", loop.getInputDataItem());
        assertEquals("currentApprover", loop.getElementVariable());
    }

    @Test
    void compilesPercentageConditionAndOrSignCandidates() {
        CompiledApprovalProcess percentage = compiler.compile(sourceXml(
                countersignApproval("PERCENTAGE", "0.67")));
        BpmnModel percentageModel = new BpmnXMLConverter().convertToBpmnModel(
                () -> new ByteArrayInputStream(
                        percentage.bpmnXml().getBytes(StandardCharsets.UTF_8)),
                true,
                false);
        UserTask percentageTask = (UserTask) percentageModel.getMainProcess()
                .getFlowElement("financeApproval");
        assertTrue(percentageTask.getLoopCharacteristics()
                .getCompletionCondition()
                .contains("0.67"));

        CompiledApprovalProcess orSign = compiler.compile(sourceXml("""
                <guo:approval mode="OR_SIGN"
                              approvers="${salesApprovers}" />
                """));
        BpmnModel orSignModel = new BpmnXMLConverter().convertToBpmnModel(
                () -> new ByteArrayInputStream(
                        orSign.bpmnXml().getBytes(StandardCharsets.UTF_8)),
                true,
                false);
        UserTask orSignTask = (UserTask) orSignModel.getMainProcess()
                .getFlowElement("financeApproval");
        assertEquals(
                java.util.List.of("${salesApprovers}"),
                orSignTask.getCandidateUsers());
        assertTrue(orSign.actionConfigs().isEmpty());
    }

    @Test
    void rejectsMissingApproversAndRuntimeOnlyVeto() {
        assertThrows(
                FlowableActionException.class,
                () -> compiler.compile(sourceXml(
                        "<guo:approval mode=\"SINGLE\" />")));

        assertThrows(
                FlowableActionException.class,
                () -> compiler.compile(sourceXml(
                        countersignApproval("VETO", null))));

        assertThrows(
                FlowableActionException.class,
                () -> compiler.compile(sourceXml("""
                        <guo:approval mode="SINGLE" approvers="${manager}">
                          <guo:return to="missingActivity" />
                        </guo:approval>
                        """)));
    }

    private String countersignApproval(String policy, String threshold) {
        String thresholdAttribute = threshold == null
                ? ""
                : " threshold=\"" + threshold + "\"";
        return "<guo:approval mode=\"COUNTERSIGN\" "
                + "completionPolicy=\""
                + policy
                + "\""
                + thresholdAttribute
                + " approvers=\"${financeApprovers}\" />";
    }

    private String sourceXml(String approvalXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:guo="http://guo.workflow/schema/approval"
                             targetNamespace="http://guo.workflow/test">
                  <process id="purchaseApproval" name="采购审批" isExecutable="true">
                    <startEvent id="start" />
                    <sequenceFlow sourceRef="start" targetRef="departmentApproval" />
                    <userTask id="departmentApproval" name="部门审批" />
                    <sequenceFlow sourceRef="departmentApproval" targetRef="financeApproval" />
                    <userTask id="financeApproval" name="财务审批">
                      <extensionElements>
                        %s
                      </extensionElements>
                    </userTask>
                    <sequenceFlow sourceRef="financeApproval" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """.formatted(approvalXml);
    }
}
