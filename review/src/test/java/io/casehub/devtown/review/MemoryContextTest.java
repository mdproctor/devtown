package io.casehub.devtown.review;

import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.devtown.domain.memory.ReviewOutcome;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.MemoryDomain;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryContextTest {

    private static final MemoryDomain DOMAIN = new MemoryDomain("software-review");

    @Test
    void emptyContextHasNoRiskSignals() {
        assertThat(MemoryContext.EMPTY.hasRiskSignals()).isFalse();
    }

    @Test
    void emptyContextMapHasEmptyLists() {
        Map<String, Object> map = MemoryContext.EMPTY.toContextMap();
        assertThat(map).containsKeys("contributorHistory", "codeAreaHistory");
        assertThat((List<?>) map.get("contributorHistory")).isEmpty();
        assertThat((List<?>) map.get("codeAreaHistory")).isEmpty();
    }

    @Test
    void completedWithApprovedUppercaseIsNotRisk() {
        Memory m = memory(ReviewOutcome.COMPLETED.name(), "APPROVED");
        MemoryContext ctx = new MemoryContext(List.of(m), List.of());
        assertThat(ctx.hasRiskSignals()).isFalse();
    }

    @Test
    void completedWithPassedIsNotRisk() {
        Memory m = memory(ReviewOutcome.COMPLETED.name(), "passed");
        MemoryContext ctx = new MemoryContext(List.of(m), List.of());
        assertThat(ctx.hasRiskSignals()).isFalse();
    }

    @Test
    void completedWithApprovedLowercaseIsNotRisk() {
        Memory m = memory(ReviewOutcome.COMPLETED.name(), "approved");
        MemoryContext ctx = new MemoryContext(List.of(m), List.of());
        assertThat(ctx.hasRiskSignals()).isFalse();
    }

    @Test
    void completedWithFindingsPresentIsRisk() {
        Memory m = memory(ReviewOutcome.COMPLETED.name(), "FINDINGS_PRESENT");
        MemoryContext ctx = new MemoryContext(List.of(m), List.of());
        assertThat(ctx.hasRiskSignals()).isTrue();
    }

    @Test
    void failedWithAnyDetailIsRisk() {
        Memory m = memory(ReviewOutcome.FAILED.name(), "some detail");
        MemoryContext ctx = new MemoryContext(List.of(m), List.of());
        assertThat(ctx.hasRiskSignals()).isTrue();
    }

    @Test
    void declinedWithAnyDetailIsNotRisk() {
        Memory m = memory(ReviewOutcome.DECLINED.name(), "outside scope");
        MemoryContext ctx = new MemoryContext(List.of(m), List.of());
        assertThat(ctx.hasRiskSignals()).isFalse();
    }

    @Test
    void completedWithNullOutcomeDetailIsRisk() {
        Memory m = new Memory(
            "m1", "contributor:mdproctor", DOMAIN, "t1", "case1",
            "Some review text.",
            Map.of(MemoryAttributeKeys.OUTCOME, ReviewOutcome.COMPLETED.name(),
                   DevtownMemoryKeys.CAPABILITY, "security-review"),
            Instant.parse("2026-06-01T10:00:00Z"));
        MemoryContext ctx = new MemoryContext(List.of(m), List.of());
        assertThat(ctx.hasRiskSignals()).isTrue();
    }

    @Test
    void contextMapExtractsAllFields() {
        Memory m = new Memory(
            "m1",
            "contributor:mdproctor",
            DOMAIN,
            "t1",
            "case1",
            "Some review text.",
            Map.of(
                MemoryAttributeKeys.OUTCOME, ReviewOutcome.COMPLETED.name(),
                DevtownMemoryKeys.OUTCOME_DETAIL, "APPROVED",
                DevtownMemoryKeys.CAPABILITY, "security-review"
            ),
            Instant.parse("2026-06-01T10:00:00Z")
        );

        MemoryContext ctx = new MemoryContext(List.of(m), List.of());
        Map<String, Object> map = ctx.toContextMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contributorHistory = (List<Map<String, Object>>) map.get("contributorHistory");
        assertThat(contributorHistory).hasSize(1);

        Map<String, Object> entry = contributorHistory.get(0);
        assertThat(entry.get("text")).isEqualTo("Some review text.");
        assertThat(entry.get("outcome")).isEqualTo(ReviewOutcome.COMPLETED.name());
        assertThat(entry.get("capability")).isEqualTo("security-review");
        assertThat(entry.get("createdAt")).isEqualTo("2026-06-01T10:00:00Z");
    }

    @Test
    void codeAreaHistoryAppearsInContextMap() {
        Memory m = memory(ReviewOutcome.COMPLETED.name(), "passed");
        MemoryContext ctx = new MemoryContext(List.of(), List.of(m));
        Map<String, Object> map = ctx.toContextMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> codeAreaHistory = (List<Map<String, Object>>) map.get("codeAreaHistory");
        assertThat(codeAreaHistory).hasSize(1);
    }

    private Memory memory(String outcome, String outcomeDetail) {
        return new Memory(
            "m1",
            "contributor:mdproctor",
            DOMAIN,
            "t1",
            "case1",
            "Some review text.",
            Map.of(
                MemoryAttributeKeys.OUTCOME, outcome,
                DevtownMemoryKeys.OUTCOME_DETAIL, outcomeDetail,
                DevtownMemoryKeys.CAPABILITY, "security-review"
            ),
            Instant.parse("2026-06-01T10:00:00Z")
        );
    }
}
