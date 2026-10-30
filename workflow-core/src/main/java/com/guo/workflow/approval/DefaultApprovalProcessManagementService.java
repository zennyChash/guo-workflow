package com.guo.workflow.approval;

import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.mirror.ProcessDefinitionCatalog;
import com.guo.workflow.mirror.ProcessDefinitionCatalogRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default approval definition management facade.
 */
@Service
public class DefaultApprovalProcessManagementService
        implements ApprovalProcessManagementService {

    private final ApprovalProcessCompiler compiler;
    private final ApprovalProcessPublisher publisher;
    private final ProcessDefinitionCatalogRepository definitionRepository;

    public DefaultApprovalProcessManagementService(
            ApprovalProcessCompiler compiler,
            ApprovalProcessPublisher publisher,
            ProcessDefinitionCatalogRepository definitionRepository) {
        this.compiler = compiler;
        this.publisher = publisher;
        this.definitionRepository = definitionRepository;
    }

    @Override
    public ApprovalProcessPreview preview(String sourceXml) {
        CompiledApprovalProcess compiled = compiler.compile(sourceXml);
        return new ApprovalProcessPreview(
                compiled.processKey(),
                compiled.processName(),
                compiled.bpmnXml(),
                compiled.semanticJson(),
                compiled.nodes(),
                compiled.actionConfigs());
    }

    @Override
    public PublishedApprovalProcess publish(String sourceXml, String tenantId) {
        return publisher.publish(sourceXml, tenantId);
    }

    @Override
    public List<ProcessDefinitionCatalog> listVersions(
            String tenantId,
            String processKey) {
        String normalizedTenant = tenantId == null || tenantId.isBlank()
                ? null
                : tenantId.trim();
        if (processKey == null || processKey.isBlank()) {
            throw new FlowableActionException("Process key must not be blank");
        }
        return definitionRepository.findByKey(
                normalizedTenant,
                processKey.trim());
    }
}
