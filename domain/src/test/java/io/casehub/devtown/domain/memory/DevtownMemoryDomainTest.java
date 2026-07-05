package io.casehub.devtown.domain.memory;

import io.casehub.neocortex.memory.MemoryDomain;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DevtownMemoryDomainTest {

    @Test
    void softwareReviewDomainMatchesSpec() {
        assertThat(DevtownMemoryDomain.SOFTWARE_REVIEW)
            .isEqualTo(new MemoryDomain("software-review"));
    }

    @Test
    void reviewOutcomeEnumHasCorrectValues() {
        assertThat(ReviewOutcome.values())
            .containsExactly(ReviewOutcome.COMPLETED, ReviewOutcome.DECLINED, ReviewOutcome.FAILED);
    }

    @Test
    void allMemoryKeysAreKebabCase() {
        Pattern kebabCase = Pattern.compile("[a-z-]+");
        assertThat(DevtownMemoryKeys.CAPABILITY).matches(kebabCase);
        assertThat(DevtownMemoryKeys.PR_NUMBER).matches(kebabCase);
        assertThat(DevtownMemoryKeys.PR_REPO).matches(kebabCase);
        assertThat(DevtownMemoryKeys.LINES_CHANGED).matches(kebabCase);
        assertThat(DevtownMemoryKeys.ENTITY_TYPE).matches(kebabCase);
        assertThat(DevtownMemoryKeys.OUTCOME_DETAIL).matches(kebabCase);
    }

    @Test
    void allMemoryKeysMatchExpectedValues() {
        assertThat(DevtownMemoryKeys.CAPABILITY).isEqualTo("capability");
        assertThat(DevtownMemoryKeys.PR_NUMBER).isEqualTo("pr-number");
        assertThat(DevtownMemoryKeys.PR_REPO).isEqualTo("pr-repo");
        assertThat(DevtownMemoryKeys.LINES_CHANGED).isEqualTo("lines-changed");
        assertThat(DevtownMemoryKeys.ENTITY_TYPE).isEqualTo("entity-type");
        assertThat(DevtownMemoryKeys.OUTCOME_DETAIL).isEqualTo("outcome-detail");
    }

    @Test
    void allMemoryKeysUnique() {
        assertThat(Set.of(
            DevtownMemoryKeys.CAPABILITY,
            DevtownMemoryKeys.PR_NUMBER,
            DevtownMemoryKeys.PR_REPO,
            DevtownMemoryKeys.LINES_CHANGED,
            DevtownMemoryKeys.ENTITY_TYPE,
            DevtownMemoryKeys.OUTCOME_DETAIL
        )).hasSize(6);
    }

    @Test
    void allMemoryKeysNonBlank() {
        assertThat(DevtownMemoryKeys.CAPABILITY).isNotBlank();
        assertThat(DevtownMemoryKeys.PR_NUMBER).isNotBlank();
        assertThat(DevtownMemoryKeys.PR_REPO).isNotBlank();
        assertThat(DevtownMemoryKeys.LINES_CHANGED).isNotBlank();
        assertThat(DevtownMemoryKeys.ENTITY_TYPE).isNotBlank();
        assertThat(DevtownMemoryKeys.OUTCOME_DETAIL).isNotBlank();
    }
}
