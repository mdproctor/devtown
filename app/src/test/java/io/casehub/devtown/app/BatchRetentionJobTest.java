package io.casehub.devtown.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.devtown.merge.MergeQueueStore;
import io.casehub.platform.api.preferences.MapPreferences;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

class BatchRetentionJobTest {

    @Test
    void expungesRecordsOlderThanRetentionDays() {
        var store = mock(MergeQueueStore.class);
        when(store.expungeCompletedBefore(any())).thenReturn(5);

        var job = new BatchRetentionJob();
        job.store = store;
        job.preferenceProvider = scope -> new MapPreferences(Map.of(
            "devtown.merge-queue.batch-retention-days", "14"));

        job.expungeCompletedBatches();

        var captor = ArgumentCaptor.forClass(Instant.class);
        verify(store).expungeCompletedBefore(captor.capture());
        Instant cutoff = captor.getValue();
        assertThat(cutoff).isBetween(
            Instant.now().minus(Duration.ofDays(15)),
            Instant.now().minus(Duration.ofDays(13)));
    }

    @Test
    void defaultRetention30Days() {
        var store = mock(MergeQueueStore.class);
        when(store.expungeCompletedBefore(any())).thenReturn(0);

        var job = new BatchRetentionJob();
        job.store = store;
        job.preferenceProvider = scope -> new MapPreferences(Map.of());

        job.expungeCompletedBatches();

        var captor = ArgumentCaptor.forClass(Instant.class);
        verify(store).expungeCompletedBefore(captor.capture());
        Instant cutoff = captor.getValue();
        assertThat(cutoff).isBetween(
            Instant.now().minus(Duration.ofDays(31)),
            Instant.now().minus(Duration.ofDays(29)));
    }

    @Test
    void zeroRetentionDays_skips() {
        var store = mock(MergeQueueStore.class);

        var job = new BatchRetentionJob();
        job.store = store;
        job.preferenceProvider = scope -> new MapPreferences(Map.of(
            "devtown.merge-queue.batch-retention-days", "0"));

        job.expungeCompletedBatches();

        verify(store, never()).expungeCompletedBefore(any());
    }

    @Test
    void failOpenOnException() {
        var store = mock(MergeQueueStore.class);
        when(store.expungeCompletedBefore(any())).thenThrow(new RuntimeException("db down"));

        var job = new BatchRetentionJob();
        job.store = store;
        job.preferenceProvider = scope -> new MapPreferences(Map.of());

        job.expungeCompletedBatches();
    }
}
