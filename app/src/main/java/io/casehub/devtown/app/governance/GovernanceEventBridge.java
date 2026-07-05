package io.casehub.devtown.app.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.ledger.runtime.service.routing.TrustScoreActorUpdatedEvent;
import io.casehub.qhorus.api.message.CommitmentDeclinedEvent;
import io.casehub.qhorus.api.message.CommitmentExpiredEvent;
import io.casehub.work.runtime.event.SlaBreachEvent;
import io.casehub.work.runtime.event.WorkItemLifecycleEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@ApplicationScoped
@ServerEndpoint("/api/governance/events")
public class GovernanceEventBridge {

    private static final Logger LOG = Logger.getLogger(GovernanceEventBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<Session> sessions = new CopyOnWriteArraySet<>();

    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        LOG.infof("WebSocket session opened: %s", session.getId());
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
        LOG.infof("WebSocket session closed: %s", session.getId());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        LOG.warnf("WebSocket error for session %s: %s", session.getId(), error.getMessage());
        sessions.remove(session);
    }

    void onCaseLifecycle(@ObservesAsync CaseLifecycleEvent event) {
        broadcast("case.state", MAPPER.createObjectNode()
            .put("caseId", event.caseId().toString())
            .put("status", event.caseStatus())
            .put("eventType", event.eventType())
            .put("timestamp", java.time.Instant.now().toString()));
    }

    void onWorkItemLifecycle(@ObservesAsync WorkItemLifecycleEvent event) {
        broadcast("workitem.lifecycle", MAPPER.createObjectNode()
            .put("workItemId", event.workItemId().toString())
            .put("status", event.status().name())
            .put("timestamp", java.time.Instant.now().toString()));
    }

    void onCommitmentDeclined(@ObservesAsync CommitmentDeclinedEvent event) {
        broadcast("commitment.lifecycle", MAPPER.createObjectNode()
            .put("commitmentId", event.commitmentId().toString())
            .put("status", "DECLINED")
            .put("timestamp", java.time.Instant.now().toString()));
    }

    void onCommitmentExpired(@ObservesAsync CommitmentExpiredEvent event) {
        broadcast("commitment.lifecycle", MAPPER.createObjectNode()
            .put("commitmentId", event.commitmentId().toString())
            .put("status", "EXPIRED")
            .put("timestamp", java.time.Instant.now().toString()));
    }

    void onTrustScoreUpdated(@ObservesAsync TrustScoreActorUpdatedEvent event) {
        broadcast("trust.update", MAPPER.createObjectNode()
            .put("actorId", event.actorId())
            .put("timestamp", java.time.Instant.now().toString()));
    }

    void onSlaBreached(@ObservesAsync SlaBreachEvent event) {
        broadcast("sla.breach", MAPPER.createObjectNode()
            .put("taskId", event.context().task().taskId().toString())
            .put("breachType", event.context().breachType().name())
            .put("timestamp", java.time.Instant.now().toString()));
    }

    void onMergeQueueState(@ObservesAsync MergeQueueStateEvent event) {
        broadcast("queue.state", MAPPER.createObjectNode()
            .put("action", event.action())
            .put("repository", event.repository())
            .put("prNumber", event.prNumber())
            .put("batchId", event.batchId())
            .put("timestamp", event.timestamp().toString()));
    }

    private void broadcast(String topic, ObjectNode payload) {
        ObjectNode envelope = MAPPER.createObjectNode()
            .put("op", "event")
            .put("topic", topic);
        envelope.set("payload", payload);

        String json;
        try {
            json = MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            LOG.error("Failed to serialize WebSocket message", e);
            return;
        }

        for (Session session : sessions) {
            if (session.isOpen()) {
                session.getAsyncRemote().sendText(json);
            } else {
                sessions.remove(session);
            }
        }
    }
}
