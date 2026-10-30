package com.guo.workflow.service;

import com.guo.workflow.mirror.ProcessActivityLog;
import com.guo.workflow.mirror.ProcessBusinessLink;
import com.guo.workflow.mirror.ProcessDefinitionCatalog;
import com.guo.workflow.mirror.ProcessInstanceMirror;
import com.guo.workflow.mirror.ProcessTaskMirror;

import java.util.List;
import java.util.Optional;

/**
 * Stable read API for common process operations views.
 */
public interface WorkflowQueryService {

    Optional<ProcessInstanceMirror> findProcess(
            String tenantId,
            String processInstanceId);

    List<ProcessInstanceMirror> findProcessesByBusinessKey(
            String tenantId,
            String businessKey);

    List<ProcessTaskMirror> findActiveTasks(
            String tenantId,
            String processInstanceId);

    List<ProcessTaskMirror> findTasksByBusinessKey(
            String tenantId,
            String businessKey);

    List<ProcessActivityLog> findActivity(
            String tenantId,
            String processInstanceId);

    List<ProcessBusinessLink> findBusinessLinks(
            String tenantId,
            String processInstanceId);

    List<ProcessDefinitionCatalog> findProcessDefinitions(
            String tenantId,
            String processKey);
}
