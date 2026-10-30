package com.guo.workflow.service.impl;

import com.guo.workflow.mirror.ProcessActivityLog;
import com.guo.workflow.mirror.ProcessBusinessLink;
import com.guo.workflow.mirror.ProcessBusinessLinkRepository;
import com.guo.workflow.mirror.ProcessDefinitionCatalog;
import com.guo.workflow.mirror.ProcessDefinitionCatalogRepository;
import com.guo.workflow.mirror.ProcessInstanceMirror;
import com.guo.workflow.mirror.ProcessMirrorRepository;
import com.guo.workflow.mirror.ProcessTaskMirror;
import com.guo.workflow.service.WorkflowQueryService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Embedded implementation backed by the workflow read-model tables.
 */
@Service
public class JdbcWorkflowQueryService implements WorkflowQueryService {

    private final ProcessMirrorRepository mirrorRepository;
    private final ProcessBusinessLinkRepository businessLinkRepository;
    private final ProcessDefinitionCatalogRepository definitionRepository;

    public JdbcWorkflowQueryService(
            ProcessMirrorRepository mirrorRepository,
            ProcessBusinessLinkRepository businessLinkRepository,
            ProcessDefinitionCatalogRepository definitionRepository) {
        this.mirrorRepository = mirrorRepository;
        this.businessLinkRepository = businessLinkRepository;
        this.definitionRepository = definitionRepository;
    }

    @Override
    public Optional<ProcessInstanceMirror> findProcess(
            String tenantId,
            String processInstanceId) {
        return mirrorRepository.findInstance(tenantId, processInstanceId);
    }

    @Override
    public List<ProcessInstanceMirror> findProcessesByBusinessKey(
            String tenantId,
            String businessKey) {
        return mirrorRepository.findInstancesByBusinessKey(tenantId, businessKey);
    }

    @Override
    public List<ProcessTaskMirror> findActiveTasks(
            String tenantId,
            String processInstanceId) {
        return mirrorRepository.findActiveTasks(tenantId, processInstanceId);
    }

    @Override
    public List<ProcessTaskMirror> findTasksByBusinessKey(
            String tenantId,
            String businessKey) {
        return mirrorRepository.findTasksByBusinessKey(tenantId, businessKey);
    }

    @Override
    public List<ProcessActivityLog> findActivity(
            String tenantId,
            String processInstanceId) {
        return mirrorRepository.findActivity(tenantId, processInstanceId);
    }

    @Override
    public List<ProcessBusinessLink> findBusinessLinks(
            String tenantId,
            String processInstanceId) {
        return businessLinkRepository.findByProcess(tenantId, processInstanceId);
    }

    @Override
    public List<ProcessDefinitionCatalog> findProcessDefinitions(
            String tenantId,
            String processKey) {
        return definitionRepository.findByKey(tenantId, processKey);
    }
}
