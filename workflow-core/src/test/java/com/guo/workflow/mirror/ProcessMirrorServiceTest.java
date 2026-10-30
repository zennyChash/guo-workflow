package com.guo.workflow.mirror;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessMirrorServiceTest {

    @Test
    void usesUserIdWhenUserResolverIsNotConfigured() {
        ProcessMirrorRepository repository = mock(ProcessMirrorRepository.class);
        ObjectProvider<UserResolver> provider = userProvider(null);
        ProcessMirrorService service = new ProcessMirrorService(repository, provider);

        service.persist(processStartedEvent());

        ArgumentCaptor<ProcessInstanceMirror> captor =
                ArgumentCaptor.forClass(ProcessInstanceMirror.class);
        verify(repository).upsertInstance(captor.capture());
        assertEquals("user-1", captor.getValue().startUserName());
    }

    @Test
    void usesResolvedUserName() {
        ProcessMirrorRepository repository = mock(ProcessMirrorRepository.class);
        UserResolver resolver = userId -> "Alice";
        ProcessMirrorService service =
                new ProcessMirrorService(repository, userProvider(resolver));

        service.persist(processStartedEvent());

        ArgumentCaptor<ProcessInstanceMirror> captor =
                ArgumentCaptor.forClass(ProcessInstanceMirror.class);
        verify(repository).upsertInstance(captor.capture());
        assertEquals("Alice", captor.getValue().startUserName());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<UserResolver> userProvider(UserResolver resolver) {
        ObjectProvider<UserResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        return provider;
    }

    private ProcessMirrorEvent processStartedEvent() {
        Instant now = Instant.now();
        return new ProcessMirrorEvent(
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
                now,
                null,
                null,
                null,
                now);
    }
}
