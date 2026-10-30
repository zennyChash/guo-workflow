package com.guo.workflow.mirror;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.event.FlowableActivityEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLinkInfo;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Captures Flowable lifecycle events without writing the mirror in the main transaction.
 */
@Component
public class FlowableProcessMirrorListener implements FlowableEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(FlowableProcessMirrorListener.class);
    private static final Set<FlowableEventType> EVENT_TYPES = Set.of(
            FlowableEngineEventType.PROCESS_STARTED,
            FlowableEngineEventType.PROCESS_COMPLETED,
            FlowableEngineEventType.PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT,
            FlowableEngineEventType.PROCESS_COMPLETED_WITH_ERROR_END_EVENT,
            FlowableEngineEventType.PROCESS_COMPLETED_WITH_ESCALATION_END_EVENT,
            FlowableEngineEventType.PROCESS_CANCELLED,
            FlowableEngineEventType.TASK_CREATED,
            FlowableEngineEventType.TASK_ASSIGNED,
            FlowableEngineEventType.TASK_COMPLETED,
            FlowableEngineEventType.ENTITY_DELETED,
            FlowableEngineEventType.MULTI_INSTANCE_ACTIVITY_COMPLETED,
            FlowableEngineEventType.MULTI_INSTANCE_ACTIVITY_COMPLETED_WITH_CONDITION);

    private final ObjectProvider<RepositoryService> repositoryServiceProvider;
    private final ObjectProvider<RuntimeService> runtimeServiceProvider;
    private final ApplicationEventPublisher eventPublisher;

    public FlowableProcessMirrorListener(
            ObjectProvider<RepositoryService> repositoryServiceProvider,
            ObjectProvider<RuntimeService> runtimeServiceProvider,
            ApplicationEventPublisher eventPublisher) {
        this.repositoryServiceProvider = repositoryServiceProvider;
        this.runtimeServiceProvider = runtimeServiceProvider;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        try {
            ProcessMirrorEvent mirrorEvent = toMirrorEvent(event);
            if (mirrorEvent != null) {
                eventPublisher.publishEvent(mirrorEvent);
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to capture Flowable mirror event type={}",
                    event.getType(),
                    exception);
        }
    }

    private ProcessMirrorEvent toMirrorEvent(FlowableEvent event) {
        FlowableEngineEvent engineEvent = (FlowableEngineEvent) event;
        if (event instanceof FlowableEntityEvent entityEvent
                && entityEvent.getEntity() instanceof TaskEntity task) {
            return taskEvent(event.getType(), engineEvent, task);
        }
        if (event.getType() == FlowableEngineEventType.ENTITY_DELETED) {
            return null;
        }
        if (event instanceof FlowableActivityEvent activityEvent) {
            return nodeEvent(event.getType(), engineEvent, activityEvent);
        }
        return processEvent(event.getType(), engineEvent);
    }

    private ProcessMirrorEvent taskEvent(
            FlowableEventType type,
            FlowableEngineEvent event,
            TaskEntity task) {
        MirrorEventType mirrorType = switch ((FlowableEngineEventType) type) {
            case TASK_CREATED -> MirrorEventType.TASK_CREATED;
            case TASK_ASSIGNED -> MirrorEventType.TASK_ASSIGNED;
            case TASK_COMPLETED -> MirrorEventType.TASK_COMPLETED;
            case ENTITY_DELETED -> task.isCanceled()
                    || !"completed".equalsIgnoreCase(task.getState())
                    ? MirrorEventType.TASK_DELETED
                    : null;
            default -> null;
        };
        if (mirrorType == null) {
            return null;
        }

        ProcessInfo process = processInfo(event, task.getProcessInstanceId());
        Map<String, Object> variables = new HashMap<>(task.getVariables());
        String nodeName = resolveNodeName(task.getTaskDefinitionKey(), variables);
        String opinion = resolveOpinion(mirrorType, variables);
        List<IdentityLinkInfo> identityLinks = task.getIdentityLinks() == null
                ? List.of()
                : List.copyOf(task.getIdentityLinks());
        String candidateUsers = joinDistinct(identityLinks.stream()
                .map(IdentityLinkInfo::getUserId)
                .filter(Objects::nonNull)
                .toList());
        String candidateGroups = joinDistinct(identityLinks.stream()
                .map(IdentityLinkInfo::getGroupId)
                .filter(Objects::nonNull)
                .toList());

        return new ProcessMirrorEvent(
                UUID.randomUUID().toString(),
                mirrorType,
                process.processInstanceId(),
                process.rootProcessInstanceId(),
                process.parentProcessInstanceId(),
                process.processDefinitionId(),
                process.processDefinitionKey(),
                process.processDefinitionVersion(),
                process.processName(),
                process.businessKey(),
                process.tenantId(),
                "RUNNING",
                task.getId(),
                task.getTaskDefinitionKey(),
                nodeName,
                task.getName(),
                task.getAssignee(),
                task.getOwner(),
                candidateUsers,
                candidateGroups,
                opinion,
                process.startUserId(),
                process.startTime(),
                instant(task.getCreateTime()),
                instant(task.getClaimTime()),
                instant(task.getDueDate()),
                Instant.now());
    }

    private ProcessMirrorEvent nodeEvent(
            FlowableEventType type,
            FlowableEngineEvent event,
            FlowableActivityEvent activityEvent) {
        FlowableEngineEventType engineType = (FlowableEngineEventType) type;
        if (engineType != FlowableEngineEventType.MULTI_INSTANCE_ACTIVITY_COMPLETED
                && engineType
                != FlowableEngineEventType.MULTI_INSTANCE_ACTIVITY_COMPLETED_WITH_CONDITION) {
            return null;
        }
        ProcessInfo process = processInfo(event, event.getProcessInstanceId());
        return new ProcessMirrorEvent(
                UUID.randomUUID().toString(),
                MirrorEventType.NODE_COMPLETED,
                process.processInstanceId(),
                process.rootProcessInstanceId(),
                process.parentProcessInstanceId(),
                process.processDefinitionId(),
                process.processDefinitionKey(),
                process.processDefinitionVersion(),
                process.processName(),
                process.businessKey(),
                process.tenantId(),
                "RUNNING",
                null,
                activityEvent.getActivityId(),
                activityEvent.getActivityName(),
                null,
                null,
                null,
                null,
                null,
                null,
                process.startUserId(),
                process.startTime(),
                null,
                null,
                null,
                Instant.now());
    }

    private ProcessMirrorEvent processEvent(
            FlowableEventType type,
            FlowableEngineEvent event) {
        FlowableEngineEventType engineType = (FlowableEngineEventType) type;
        MirrorEventType mirrorType = switch (engineType) {
            case PROCESS_STARTED -> MirrorEventType.PROCESS_STARTED;
            case PROCESS_COMPLETED -> MirrorEventType.PROCESS_COMPLETED;
            case PROCESS_CANCELLED -> MirrorEventType.PROCESS_CANCELLED;
            case PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT,
                    PROCESS_COMPLETED_WITH_ERROR_END_EVENT ->
                    MirrorEventType.PROCESS_TERMINATED;
            case PROCESS_COMPLETED_WITH_ESCALATION_END_EVENT ->
                    MirrorEventType.PROCESS_COMPLETED;
            default -> null;
        };
        if (mirrorType == null) {
            return null;
        }
        ProcessInfo process = processInfo(event, event.getProcessInstanceId());
        return new ProcessMirrorEvent(
                UUID.randomUUID().toString(),
                mirrorType,
                process.processInstanceId(),
                process.rootProcessInstanceId(),
                process.parentProcessInstanceId(),
                process.processDefinitionId(),
                process.processDefinitionKey(),
                process.processDefinitionVersion(),
                process.processName(),
                process.businessKey(),
                process.tenantId(),
                switch (mirrorType) {
                    case PROCESS_STARTED -> "RUNNING";
                    case PROCESS_CANCELLED -> "CANCELLED";
                    case PROCESS_TERMINATED -> "TERMINATED";
                    default -> "COMPLETED";
                },
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                process.startUserId(),
                process.startTime(),
                null,
                null,
                null,
                Instant.now());
    }

    private ProcessInfo processInfo(FlowableEngineEvent event, String processInstanceId) {
        String resolvedProcessInstanceId = processInstanceId == null
                ? event.getProcessInstanceId()
                : processInstanceId;
        String processDefinitionId = event.getProcessDefinitionId();
        RepositoryService repositoryService = repositoryServiceProvider.getIfAvailable();
        ProcessDefinition definition = processDefinitionId == null || repositoryService == null
                ? null
                : repositoryService.getProcessDefinition(processDefinitionId);
        RuntimeService runtimeService = runtimeServiceProvider.getIfAvailable();
        ProcessInstance processInstance = resolvedProcessInstanceId == null
                || runtimeService == null
                ? null
                : runtimeService.createProcessInstanceQuery()
                        .processInstanceId(resolvedProcessInstanceId)
                        .singleResult();
        ExecutionEntity execution = event instanceof FlowableEntityEvent entityEvent
                && entityEvent.getEntity() instanceof ExecutionEntity entity
                ? entity
                : processInstance instanceof ExecutionEntity entity
                        ? entity
                        : null;

        String businessKey = processInstance == null
                ? execution == null ? null : execution.getProcessInstanceBusinessKey()
                : processInstance.getBusinessKey();
        String tenantId = processInstance == null
                ? execution == null ? null : execution.getTenantId()
                : processInstance.getTenantId();
        String startUserId = processInstance == null
                ? execution == null ? null : execution.getStartUserId()
                : processInstance.getStartUserId();
        Instant startTime = processInstance == null
                ? execution == null ? null : instant(execution.getStartTime())
                : instant(processInstance.getStartTime());
        String rootProcessInstanceId = execution == null
                ? resolvedProcessInstanceId
                : execution.getRootProcessInstanceId();
        String parentProcessInstanceId = execution == null
                || execution.getSuperExecution() == null
                ? null
                : execution.getSuperExecution().getProcessInstanceId();

        return new ProcessInfo(
                resolvedProcessInstanceId,
                rootProcessInstanceId,
                parentProcessInstanceId,
                processDefinitionId,
                definition == null ? null : definition.getKey(),
                definition == null ? null : definition.getVersion(),
                definition == null ? null : definition.getName(),
                blankToNull(businessKey),
                blankToNull(tenantId),
                blankToNull(startUserId),
                startTime);
    }

    private String resolveNodeName(String activityId, Map<String, Object> variables) {
        String[] keys = {
                "nodeName_" + activityId,
                "nodeName",
                "activityName_" + activityId,
                "activityName"
        };
        for (String key : keys) {
            Object value = variables.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return activityId;
    }

    private String resolveOpinion(
            MirrorEventType eventType,
            Map<String, Object> variables) {
        if (eventType != MirrorEventType.TASK_COMPLETED
                && eventType != MirrorEventType.TASK_DELETED) {
            return null;
        }
        for (String key : List.of(
                "approvalComment", "approvalOpinion", "comment", "opinion")) {
            Object value = variables.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "无审批意见";
    }

    private String joinDistinct(List<String> values) {
        String joined = values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        return joined.isBlank() ? null : joined;
    }

    private Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public boolean isFailOnException() {
        return false;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }

    @Override
    public Collection<? extends FlowableEventType> getTypes() {
        return EVENT_TYPES;
    }

    private record ProcessInfo(
            String processInstanceId,
            String rootProcessInstanceId,
            String parentProcessInstanceId,
            String processDefinitionId,
            String processDefinitionKey,
            Integer processDefinitionVersion,
            String processName,
            String businessKey,
            String tenantId,
            String startUserId,
            Instant startTime) {
    }
}
