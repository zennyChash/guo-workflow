package com.guo.workflow.mirror;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ProcessMirrorSyncListenerTest {

    @Test
    void swallowsMirrorPersistenceFailures() {
        ProcessMirrorService service = mock(ProcessMirrorService.class);
        ProcessMirrorSyncListener listener = new ProcessMirrorSyncListener(service);
        ProcessMirrorEvent event = new ProcessMirrorEvent(
                "event-1",
                MirrorEventType.PROCESS_STARTED,
                "process-1",
                "process-1",
                null,
                "definition-1",
                "test",
                1,
                "Test",
                "business-1",
                "tenant-1",
                "RUNNING",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "user-1",
                Instant.now(),
                null,
                null,
                null,
                Instant.now());
        doThrow(new IllegalStateException("mirror unavailable"))
                .when(service)
                .persist(event);

        assertDoesNotThrow(() -> listener.onMirrorEvent(event));
    }
}
