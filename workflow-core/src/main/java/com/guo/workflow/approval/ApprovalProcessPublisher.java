package com.guo.workflow.approval;

import com.guo.workflow.contract.exception.FlowableActionException;
import com.guo.workflow.mirror.ProcessDefinitionCatalog;
import com.guo.workflow.mirror.ProcessDefinitionCatalogRepository;
import com.guo.workflow.repository.ActionConfigRepository;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Deploys compiled approval BPMN and stores its source, semantics and actions.
 */
@Service
public class ApprovalProcessPublisher {

    private final ApprovalProcessCompiler compiler;
    private final RepositoryService repositoryService;
    private final ActionConfigRepository actionConfigRepository;
    private final ProcessDefinitionCatalogRepository definitionRepository;

    public ApprovalProcessPublisher(
            ApprovalProcessCompiler compiler,
            RepositoryService repositoryService,
            ActionConfigRepository actionConfigRepository,
            ProcessDefinitionCatalogRepository definitionRepository) {
        this.compiler = compiler;
        this.repositoryService = repositoryService;
        this.actionConfigRepository = actionConfigRepository;
        this.definitionRepository = definitionRepository;
    }

    @Transactional
    public PublishedApprovalProcess publish(String sourceXml, String tenantId) {
        CompiledApprovalProcess compiled = compiler.compile(sourceXml);
        String normalizedTenant = tenantId == null || tenantId.isBlank()
                ? null
                : tenantId.trim();

        DeploymentBuilder builder = repositoryService.createDeployment()
                .name(compiled.processKey() + "-approval")
                .addString(
                        compiled.processKey() + ".bpmn20.xml",
                        compiled.bpmnXml());
        if (normalizedTenant != null) {
            builder.tenantId(normalizedTenant);
        }
        Deployment deployment = builder.deploy();
        ProcessDefinition definition = repositoryService
                .createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
        if (definition == null) {
            throw new FlowableActionException(
                    "Deployed approval process definition was not found: "
                            + compiled.processKey());
        }

        definitionRepository.upsert(new ProcessDefinitionCatalog(
                definition.getId(),
                normalizedTenant,
                definition.getKey(),
                definition.getVersion(),
                definition.getName(),
                deployment.getId(),
                compiled.sourceXml(),
                compiled.semanticJson(),
                "PUBLISHED",
                Instant.now(),
                null,
                null));

        for (CompiledActionConfig action : compiled.actionConfigs()) {
            actionConfigRepository.upsertGenerated(
                    definition.getKey(),
                    definition.getId(),
                    action.activityId(),
                    action.scope(),
                    action.event(),
                    action.actionXml());
        }

        return new PublishedApprovalProcess(
                normalizedTenant,
                definition.getKey(),
                definition.getId(),
                deployment.getId(),
                definition.getVersion());
    }
}
