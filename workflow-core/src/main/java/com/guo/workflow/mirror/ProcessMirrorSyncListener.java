package com.guo.workflow.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Persists mirror events only after the Flowable transaction has committed.
 */
@Component
public class ProcessMirrorSyncListener {

    private static final Logger log = LoggerFactory.getLogger(ProcessMirrorSyncListener.class);

    private final ProcessMirrorService mirrorService;

    public ProcessMirrorSyncListener(ProcessMirrorService mirrorService) {
        this.mirrorService = mirrorService;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onMirrorEvent(ProcessMirrorEvent event) {
        try {
            mirrorService.persist(event);
        } catch (RuntimeException exception) {
            log.error(
                    "Mirror synchronization failed eventId={}, type={}, processInstanceId={}",
                    event.eventId(),
                    event.eventType(),
                    event.processInstanceId(),
                    exception);
        }
    }
}
