package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.work.api.BreachDecision;
import io.casehub.work.api.BreachType;
import io.casehub.work.api.BreachedTask;
import io.casehub.work.api.SlaBreachContext;
import io.casehub.work.api.spi.SlaBreachPolicy;
import io.casehub.work.runtime.event.SlaBreachEvent;
import io.casehub.workadapter.PlanItemCallerRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SlaBreachHandlerWiringTest {

    @Inject SlaBreachPolicy        policy;
    @Inject PrReviewCaseHub        caseHub;
    @Inject Event<SlaBreachEvent>  breachEvents;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;
    @Inject PlanItemStore          planItemStore;

    private static final Map<String, Object> MINIMAL_CTX = Map.of(
            "pr",                  Map.of("id", "wt1", "repo", "r", "linesChanged", 600,
                                           "baseRef", "main", "headSha", "abc"),
            "policy",              Map.of("humanApprovalThreshold", 500,
                                          "securityReviewRequired", false,
                                          "requireSeniorApproval", false),
            "codeAnalysis",        Map.of("complete", true, "securitySensitive", false,
                                           "architectureCrossing", false),
            "styleCheck",          Map.of("outcome", "PENDING"),
            "testCoverage",        Map.of("outcome", "PENDING"),
            "performanceAnalysis", Map.of("outcome", "PENDING"),
            "ci",                  Map.of("status", "passing"));

    @Test
    void slaBreachPolicyBean_displaces_noOp() {
        assertThat(policy).isInstanceOf(SlaBreachPolicyBean.class);
    }

    @Test
    void slaBreachHandler_onFail_resolvesOutputKeyFromBinding() throws Exception {
        UUID caseId = caseHub.startCase(MINIMAL_CTX).toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        String planItemId = await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).until(() -> {
            var records = planItemStore.findDelegatedCrossTenant(caseId);
            return records.stream()
                    .filter(r -> "human-approval".equals(r.bindingName()))
                    .map(PlanItemRecord::planItemId)
                    .findFirst()
                    .orElse(null);
        }, pid -> pid != null);

        String callerRef = PlanItemCallerRef.encode(caseId, planItemId);
        var task = new BreachedTask(UUID.randomUUID(), callerRef,
                                    "PR approval", Set.of("pr-leads"));
        var ctx  = new SlaBreachContext(BreachType.CLAIM_EXPIRED, task,
                                        Path.root(), new MapPreferences(Map.of()));
        breachEvents.fire(new SlaBreachEvent(ctx, new BreachDecision.Fail("sla-breach"), "default"));

        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() -> {
            var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
            assertThat(instance.getCaseContext().getPath("humanApproval.outcome"))
                    .isEqualTo("BLOCKED");
        });
    }
}
