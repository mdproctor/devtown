package io.casehub.devtown.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.devtown.domain.queue.MergeQueuePreferenceKeys;
import io.casehub.devtown.merge.AdmissionResult;
import io.casehub.devtown.merge.MergeQueueStore;
import io.casehub.devtown.merge.QueueEntry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BatchFormationTestProfile.class)
@TestSecurity(user = "devtown-admin", roles = {"devtown-admin"})
class MergeQueueAdmissionTrustScoreTest {

    @Inject MergeQueueService mergeQueueService;
    @Inject MergeQueueStore store;

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanAll() {
        em.createQuery("DELETE FROM QueuedPrEntity").executeUpdate();
        em.createQuery("DELETE FROM BatchEntity").executeUpdate();
    }

    @Test
    void admit_usesDefaultTrustScore_whenNoPreferenceConfigured() {
        AdmissionResult result = mergeQueueService.admit(100, "casehubio/devtown", "abc123", "alice");

        assertThat(result).isEqualTo(AdmissionResult.ENQUEUED);

        var entries = store.queued();
        assertThat(entries).hasSize(1);
        QueueEntry entry = entries.get(0);
        assertThat(entry.pr().trustScore())
            .isCloseTo(MergeQueuePreferenceKeys.DEFAULT_ADMISSION_TRUST_SCORE.defaultValue().value(),
                org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void admit_idempotent_duplicateReturnsAlreadyQueued() {
        mergeQueueService.admit(200, "casehubio/devtown", "def456", "bob");
        AdmissionResult second = mergeQueueService.admit(200, "casehubio/devtown", "def456", "bob");

        assertThat(second).isEqualTo(AdmissionResult.ALREADY_QUEUED);
        assertThat(store.queued()).hasSize(1);
    }
}
