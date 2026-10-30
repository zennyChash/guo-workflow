package com.guo.workflow.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Writes one captured Flowable lifecycle event into the mirror model.
 */
@Service
public class ProcessMirrorService {

    private static final Logger log = LoggerFactory.getLogger(ProcessMirrorService.class);

    private final ProcessMirrorRepository repository;
    private final ObjectProvider<UserResolver> userResolverProvider;

    public ProcessMirrorService(
            ProcessMirrorRepository repository,
            ObjectProvider<UserResolver> userResolverProvider) {
        this.repository = repository;
        this.userResolverProvider = userResolverProvider;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(ProcessMirrorEvent event) {
        switch (event.eventType()) {
            case PROCESS_STARTED -> {
                repository.upsertInstance(instance(event, "RUNNING", null));
                repository.appendActivity(activity(event, null, null));
            }
            case TASK_DELETED -> {
                if (repository.isTaskCompleted(event.taskId())) {
                    return;
                }
                repository.upsertInstance(instance(event, "RUNNING", null));
                repository.upsertTask(task(event));
                repository.appendActivity(activity(event, event.assigneeId(), null));
                repository.refreshInstanceSummary(event.processInstanceId(), event.eventTime());
            }
            case TASK_CREATED, TASK_ASSIGNED, TASK_COMPLETED -> {
                repository.upsertInstance(instance(event, "RUNNING", null));
                repository.upsertTask(task(event));
                repository.appendActivity(activity(event, event.assigneeId(), null));
                repository.refreshInstanceSummary(event.processInstanceId(), event.eventTime());
            }
            case NODE_COMPLETED -> repository.appendActivity(activity(event, null, null));
            case PROCESS_COMPLETED, PROCESS_CANCELLED, PROCESS_TERMINATED -> {
                String status = switch (event.eventType()) {
                    case PROCESS_COMPLETED -> "COMPLETED";
                    case PROCESS_CANCELLED -> "CANCELLED";
                    default -> "TERMINATED";
                };
                repository.upsertInstance(instance(event, status, event.eventTime()));
                repository.completeInstance(event.processInstanceId(), status, event.eventTime());
                repository.appendActivity(activity(event, null, null));
            }
        }
    }

    private ProcessInstanceMirror instance(
            ProcessMirrorEvent event,
            String status,
            java.time.Instant endTime) {
        return new ProcessInstanceMirror(
                event.processInstanceId(),
                event.rootProcessInstanceId(),
                event.parentProcessInstanceId(),
                event.processDefinitionId(),
                event.processDefinitionKey(),
                event.processDefinitionVersion(),
                event.processName(),
                event.businessKey(),
                event.tenantId(),
                status,
                0,
                null,
                null,
                event.startUserId(),
                resolveUser(event.startUserId()),
                event.startTime(),
                endTime,
                event.eventTime());
    }

    private ProcessTaskMirror task(ProcessMirrorEvent event) {
        String taskStatus = switch (event.eventType()) {
            case TASK_COMPLETED -> "COMPLETED";
            case TASK_DELETED -> "DELETED";
            default -> "PENDING";
        };
        java.time.Instant completeTime = event.eventType() == MirrorEventType.TASK_COMPLETED
                ? event.eventTime()
                : null;
        return new ProcessTaskMirror(
                event.taskId(),
                event.processInstanceId(),
                event.processDefinitionKey(),
                event.businessKey(),
                event.tenantId(),
                event.activityId(),
                nodeName(event),
                event.taskName(),
                taskStatus,
                "NORMAL",
                event.assigneeId(),
                resolveUser(event.assigneeId()),
                event.ownerId(),
                resolveUser(event.ownerId()),
                event.candidateUserIds(),
                resolveUsers(event.candidateUserIds()),
                event.candidateGroupIds(),
                event.candidateGroupIds(),
                null,
                null,
                null,
                null,
                null,
                event.taskCreateTime(),
                event.claimTime(),
                event.dueTime(),
                completeTime,
                durationMs(event.taskCreateTime(), completeTime),
                event.eventTime());
    }

    private ProcessActivityLog activity(
            ProcessMirrorEvent event,
            String actorId,
            String relatedTaskId) {
        return new ProcessActivityLog(
                event.eventId(),
                event.eventType().scope(),
                event.eventType().historyEvent(),
                event.processInstanceId(),
                event.processDefinitionKey(),
                event.processName(),
                event.businessKey(),
                event.tenantId(),
                event.taskId(),
                event.activityId(),
                nodeName(event),
                actorId,
                resolveUser(actorId),
                null,
                null,
                null,
                null,
                event.opinion(),
                event.eventTime(),
                event.taskCreateTime(),
                durationMs(event.taskCreateTime(), event.eventTime()),
                relatedTaskId);
    }

    private String nodeName(ProcessMirrorEvent event) {
        return event.nodeName() == null || event.nodeName().isBlank()
                ? event.activityId()
                : event.nodeName();
    }

    private String resolveUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        UserResolver resolver = userResolverProvider.getIfAvailable();
        if (resolver == null) {
            return userId;
        }
        try {
            String resolved = resolver.resolve(userId);
            return resolved == null || resolved.isBlank() ? userId : resolved;
        } catch (RuntimeException exception) {
            log.warn("UserResolver failed; falling back to userId={}", userId, exception);
            return userId;
        }
    }

    private String resolveUsers(String userIds) {
        if (userIds == null || userIds.isBlank()) {
            return null;
        }
        List<String> resolved = Arrays.stream(userIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::resolveUser)
                .toList();
        return resolved.isEmpty() ? null : String.join(",", resolved);
    }

    private Long durationMs(java.time.Instant start, java.time.Instant end) {
        if (start == null || end == null) {
            return null;
        }
        return Math.max(0, Duration.between(start, end).toMillis());
    }
}
