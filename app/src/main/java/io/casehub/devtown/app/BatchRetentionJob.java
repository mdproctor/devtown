package io.casehub.devtown.app;

import io.casehub.devtown.domain.queue.MergeQueuePreferenceKeys;
import io.casehub.devtown.merge.MergeQueueStore;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class BatchRetentionJob {

    private static final Logger LOG = Logger.getLogger(BatchRetentionJob.class);
    private static final SettingsScope MERGE_QUEUE_SCOPE =
        SettingsScope.of("devtown", "merge-queue");

    @Inject
    MergeQueueStore store;

    @Inject
    PreferenceProvider preferenceProvider;

    @Scheduled(cron = "0 3 * * *", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void expungeCompletedBatches() {
        try {
            Preferences prefs = preferenceProvider.resolve(MERGE_QUEUE_SCOPE);
            int retentionDays = prefs.getOrDefault(MergeQueuePreferenceKeys.BATCH_RETENTION_DAYS).value();
            if (retentionDays <= 0) return;

            Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
            int expunged = store.expungeCompletedBefore(cutoff);
            if (expunged > 0) {
                LOG.infof("Batch retention: expunged %d completed batch records older than %d days", expunged, retentionDays);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Batch retention job failed — will retry next run");
        }
    }
}
