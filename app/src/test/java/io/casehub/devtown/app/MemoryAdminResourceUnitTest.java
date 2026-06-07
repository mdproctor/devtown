package io.casehub.devtown.app;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.memory.CaseMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryAdminResourceUnitTest {

    @Test
    void eraseContributor_logs_warn_when_adapter_unsupported() {
        var store = mock(CaseMemoryStore.class);
        doThrow(UnsupportedOperationException.class).when(store).eraseEntity(any(), any());
        var principal = mock(CurrentPrincipal.class);
        when(principal.actorId()).thenReturn("admin-user");
        when(principal.tenancyId()).thenReturn("default");

        var resource = new MemoryAdminResource(store, principal);

        var logs = new ArrayList<LogRecord>();
        var handler = new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        handler.setLevel(Level.ALL);

        var julLogger = java.util.logging.Logger.getLogger("io.casehub.devtown.app.MemoryAdminResource");
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        try {
            var response = resource.eraseContributor(new MemoryAdminResource.EraseContributorRequest("alice"));

            assertThat(response.getStatus()).isEqualTo(501);

            // Should have request log (INFO) + unsupported log (WARN)
            assertThat(logs).hasSizeGreaterThanOrEqualTo(2);
            assertThat(logs.get(0).getMessage()).contains("GDPR erasure requested");
            assertThat(logs.get(0).getMessage()).contains("contributor:alice");

            // JBoss LogManager uses org.jboss.logmanager.Level.WARN (int 900) rather
            // than java.util.logging.Level.WARNING — filter by int value for portability
            var warnLogs = logs.stream()
                    .filter(l -> l.getLevel().intValue() == Level.WARNING.intValue())
                    .toList();
            assertThat(warnLogs).isNotEmpty();
            assertThat(warnLogs.get(0).getMessage()).contains("GDPR erasure not supported");
            assertThat(warnLogs.get(0).getMessage()).contains("contributor:alice");
        } finally {
            julLogger.removeHandler(handler);
        }
    }
}
