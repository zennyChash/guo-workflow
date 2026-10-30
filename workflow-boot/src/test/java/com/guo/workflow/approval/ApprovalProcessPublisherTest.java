package com.guo.workflow.approval;

import com.guo.workflow.mirror.ProcessDefinitionCatalog;
import com.guo.workflow.service.WorkflowQueryService;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class ApprovalProcessPublisherTest {

    @Autowired
    private ApprovalProcessPublisher publisher;
    @Autowired
    private WorkflowQueryService queryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RepositoryService repositoryService;

    @Test
    void deploysBpmnAndPersistsGeneratedActionConfiguration() throws Exception {
        PublishedApprovalProcess published = publisher.publish("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:guo="http://guo.workflow/schema/approval"
                             targetNamespace="http://guo.workflow/test">
                  <process id="publishedApproval" name="发布测试审批" isExecutable="true">
                    <startEvent id="start" />
                    <sequenceFlow sourceRef="start" targetRef="finance" />
                    <userTask id="finance" name="财务审批">
                      <extensionElements>
                        <guo:approval mode="COUNTERSIGN"
                                      completionPolicy="ALL"
                                      approvers="${financeApprovers}">
                          <guo:notification trigger="assignment"
                                            channel="dingtalk"
                                            to="#{currentApprover}">
                            <guo:content>待办 #{businessKey}</guo:content>
                          </guo:notification>
                        </guo:approval>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow sourceRef="finance" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """, "tenant-1");

        assertNotNull(published.processDefinitionId());
        assertEquals("tenant-1", published.tenantId());
        List<ProcessDefinitionCatalog> definitions =
                queryService.findProcessDefinitions("tenant-1", "publishedApproval");
        assertEquals(1, definitions.size());
        assertEquals("PUBLISHED", definitions.get(0).status());
        assertTrue(definitions.get(0).sourceXml().contains("<guo:approval"));
        assertTrue(definitions.get(0).semanticJson().contains("COUNTERSIGN"));
        String deployedBpmn;
        try (var stream = repositoryService.getResourceAsStream(
                published.deploymentId(),
                "publishedApproval.bpmn20.xml")) {
            deployedBpmn = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(deployedBpmn.contains("<guo:approval"));

        String actionXml = jdbcTemplate.queryForObject("""
                SELECT action_xml
                FROM t_node_action_config
                WHERE process_definition_id = ?
                  AND activity_id = 'finance'
                  AND trigger_scope = 'TASK'
                  AND event = 'assignment'
                """, String.class, published.processDefinitionId());
        assertNotNull(actionXml);
        assertTrue(actionXml.contains("dingtalk"));
        assertTrue(actionXml.contains("待办 #{businessKey}"));
    }
}
