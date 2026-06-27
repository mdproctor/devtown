package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.runtime.repository.WorkItemStore;
import io.casehub.work.runtime.service.ExpiryLifecycleService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SlaBreachLifecycleTest {

    @Inject PrReviewCaseHub        caseHub;
    @Inject WorkItemQueries        workItemQueries;
    @Inject WorkItemStore          workItemStore;
    @Inject ExpiryLifecycleService expiryService;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;

    @Test
    void twoTierSlaBreach_signalsCaseOnTerminalFail() throws Exception {

        var pr = Map.<String, Object>of(
                "id", "99", "repo", "casehubio/devtown",
                "linesChanged", 600, "baseRef", "main", "headSha", "def456");
        var policy = Map.<String, Object>of(
                "humanApprovalThreshold", 500,
                "securityReviewRequired", false,
                "requireSeniorApproval", false);
        var initialCtx = Map.<String, Object>of(
                "pr", pr,
                "policy", policy,
                "codeAnalysis",        Map.of("complete", true,
                                               "securitySensitive", false,
                                               "architectureCrossing", false),
                "styleCheck",          Map.of("outcome", "PENDING"),
                "testCoverage",        Map.of("outcome", "PENDING"),
                "performanceAnalysis", Map.of("outcome", "PENDING"),
                "ci",                  Map.of("status", "passing"));

        // ── Checkpoint 1: start case ──────────────────────────────────────────
        UUID caseId = caseHub.startCase(initialCtx)
                .toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // ── Checkpoint 2: WorkItem created with candidateGroups=pr-reviewers ──
        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() -> {
            var items = workItemQueries.scanAll().stream()
                    .filter(i -> isHumanApprovalFor(i, caseId)).toList();
            assertThat(items).as("human-approval WorkItem").hasSize(1);
            assertThat(items.get(0).candidateGroups)
                    .as("candidateGroups from YAML").isEqualTo("pr-reviewers");
            assertThat(items.get(0).expiresAt)
                    .as("expiresAt set from expiresIn: PT24H").isNotNull();
        });

        // ── Checkpoint 3: trigger Tier 1 breach ───────────────────────────────
        expireWorkItem(caseId);
        expiryService.checkExpired();

        await().atMost(3, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() -> {
            var items = workItemQueries.scanAll().stream()
                    .filter(i -> isHumanApprovalFor(i, caseId)).toList();
            assertThat(items).hasSize(1);
            assertThat(items.get(0).candidateGroups)
                    .as("escalated to pr-leads").isEqualTo("pr-leads");
            assertThat(items.get(0).status)
                    .as("WorkItem is PENDING again after in-place escalation")
                    .isEqualTo(WorkItemStatus.PENDING);
        });

        // Case context unchanged after Tier 1 (only Fail triggers case signal)
        var instanceAfterTier1 = caseInstanceRepository.findByUuid(caseId)
                .await().atMost(java.time.Duration.ofSeconds(2));
        assertThat(instanceAfterTier1.getCaseContext().getPath("humanApproval"))
                .as("humanApproval context unchanged after escalation").isNull();

        // ── Checkpoint 4: trigger Tier 2 breach ───────────────────────────────
        expireWorkItem(caseId);
        expiryService.checkExpired();

        await().atMost(10, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() -> {
            var instance = caseInstanceRepository.findByUuid(caseId)
                    .await().atMost(java.time.Duration.ofSeconds(2));
            assertThat(instance).isNotNull();
            Object outcome = instance.getCaseContext().getPath("humanApproval.outcome");
            assertThat(outcome)
                    .as("humanApproval.outcome should be BLOCKED after terminal breach")
                    .isEqualTo("BLOCKED");
        });

        // WorkItem itself should be EXPIRED with resolution set
        var finalItems = workItemQueries.scanAll().stream()
                .filter(i -> isHumanApprovalFor(i, caseId)).toList();
        assertThat(finalItems).hasSize(1);
        assertThat(finalItems.get(0).status).isEqualTo(WorkItemStatus.EXPIRED);
        assertThat(finalItems.get(0).resolution).isEqualTo("sla-breach");
    }

    @Transactional
    void expireWorkItem(UUID caseId) {
        workItemQueries.scanAll().stream()
                .filter(i -> isHumanApprovalFor(i, caseId)
                          && i.status == WorkItemStatus.PENDING)
                .forEach(i -> {
                    i.expiresAt = Instant.now().minusSeconds(60);
                    workItemStore.put(i);
                });
    }

    private static boolean isHumanApprovalFor(WorkItem item, UUID caseId) {
        return item.callerRef != null && item.callerRef.startsWith("case:" + caseId);
    }
}
