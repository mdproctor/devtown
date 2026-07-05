package io.casehub.devtown.app.governance;

import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GovernanceEventBridgeTest {

    @Test
    void onCaseLifecycle_sendsJsonToConnectedSessions() throws Exception {
        var bridge = new GovernanceEventBridge();
        var session = mock(Session.class);
        var asyncRemote = mock(RemoteEndpoint.Async.class);
        when(session.getAsyncRemote()).thenReturn(asyncRemote);
        when(session.isOpen()).thenReturn(true);

        bridge.onOpen(session);

        var event = new CaseLifecycleEvent(
            UUID.randomUUID(), "default", "CASE_COMPLETED", "CASE_STATE_CHANGED",
            "COMPLETED", "actor-1", "user", "trace-123"
        );
        bridge.onCaseLifecycle(event);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(asyncRemote).sendText(jsonCaptor.capture());

        String json = jsonCaptor.getValue();
        assertThat(json).contains("\"op\":\"event\"");
        assertThat(json).contains("\"topic\":\"case.state\"");
        assertThat(json).contains("COMPLETED");
    }

    @Test
    void onClose_removesSession() {
        var bridge = new GovernanceEventBridge();
        var session = mock(Session.class);
        var asyncRemote = mock(RemoteEndpoint.Async.class);
        when(session.getAsyncRemote()).thenReturn(asyncRemote);
        when(session.isOpen()).thenReturn(true);

        bridge.onOpen(session);
        bridge.onClose(session);

        // Fire an event — session should NOT receive it
        var event = new CaseLifecycleEvent(
            UUID.randomUUID(), "default", "CASE_COMPLETED", "CASE_STATE_CHANGED",
            "COMPLETED", "actor-1", "user", "trace-123"
        );
        bridge.onCaseLifecycle(event);

        verifyNoInteractions(asyncRemote);
    }

    @Test
    void onError_removesSession() {
        var bridge = new GovernanceEventBridge();
        var session = mock(Session.class);
        var asyncRemote = mock(RemoteEndpoint.Async.class);
        when(session.getAsyncRemote()).thenReturn(asyncRemote);
        when(session.getId()).thenReturn("session-1");

        bridge.onOpen(session);
        bridge.onError(session, new RuntimeException("test error"));

        // Fire an event — session should NOT receive it
        when(session.isOpen()).thenReturn(false);
        var event = new CaseLifecycleEvent(
            UUID.randomUUID(), "default", "CASE_COMPLETED", "CASE_STATE_CHANGED",
            "COMPLETED", "actor-1", "user", "trace-123"
        );
        bridge.onCaseLifecycle(event);

        verifyNoInteractions(asyncRemote);
    }
}
