package com.guo.workflow.approval;

import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.mirror.ProcessDefinitionCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class ApprovalProcessManagementIntegrationTest {

    @Autowired
    private ApprovalProcessManagementService managementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void previewsWithoutDeployingAndListsPublishedVersions() {
        String sourceXml = source("管理测试审批", "资料完整");
        ApprovalProcessPreview preview = managementService.preview(sourceXml);
        assertEquals("managedApproval", preview.processKey());
        assertTrue(preview.bpmnXml().contains("multiInstanceLoopCharacteristics"));
        assertFalse(preview.bpmnXml().contains("guo:approval"));
        assertEquals(1, preview.actionConfigs().size());
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM t_process_definition_catalog "
                                + "WHERE process_key = 'managedApproval'",
                        Integer.class));

        PublishedApprovalProcess first = managementService.publish(
                sourceXml,
                "tenant-managed");
        PublishedApprovalProcess second = managementService.publish(
                source("管理测试审批 V2", "补充资料"),
                "tenant-managed");

        List<ProcessDefinitionCatalog> versions =
                managementService.listVersions(
                        "tenant-managed",
                        "managedApproval");
        assertEquals(2, versions.size());
        assertEquals(second.version(), versions.get(0).version());
        assertEquals(first.version(), versions.get(1).version());
        assertTrue(versions.get(0).sourceXml().contains("管理测试审批 V2"));
        assertTrue(versions.get(0).semanticJson().contains("补充资料"));
    }

    @Test
    void invalidPreviewDoesNotCreateDefinition() {
        assertThrows(
                FlowableActionException.class,
                () -> managementService.preview("""
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                     xmlns:guo="http://guo.workflow/schema/approval">
                          <process id="invalidPreview" isExecutable="true">
                            <userTask id="task">
                              <extensionElements>
                                <guo:approval mode="SINGLE" />
                              </extensionElements>
                            </userTask>
                          </process>
                        </definitions>
                        """));
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM t_process_definition_catalog "
                                + "WHERE process_key = 'invalidPreview'",
                        Integer.class));
    }

    private String source(String processName, String notificationContent) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:guo="http://guo.workflow/schema/approval"
                             targetNamespace="http://guo.workflow/test">
                  <process id="managedApproval" name="%s" isExecutable="true">
                    <startEvent id="start" />
                    <sequenceFlow sourceRef="start" targetRef="finance" />
                    <userTask id="finance" name="财务会签">
                      <extensionElements>
                        <guo:approval mode="COUNTERSIGN"
                                      completionPolicy="ALL"
                                      approvers="${financeApprovers}">
                          <guo:notification trigger="assignment"
                                            channel="dingtalk"
                                            to="#{currentApprover}">
                            <guo:content>%s</guo:content>
                          </guo:notification>
                        </guo:approval>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow sourceRef="finance" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """.formatted(processName, notificationContent);
    }
}
