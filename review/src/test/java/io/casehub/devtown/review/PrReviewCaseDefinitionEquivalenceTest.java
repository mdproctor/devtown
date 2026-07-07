package io.casehub.devtown.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.converter.CaseDefinitionYamlMapper;
import io.casehub.api.spi.routing.CandidateSetSpec;
import io.casehub.api.spi.routing.StaticSetStrategy;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class PrReviewCaseDefinitionEquivalenceTest {

    @Test
    void dslMatchesYaml() throws IOException {
        CaseDefinition fromYaml = CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("devtown/pr-review.yaml"));
        CaseDefinition fromDsl = PrReviewCaseDefinition.build(500);

        assertThat(fromDsl.getName()).isEqualTo(fromYaml.getName());
        assertThat(fromDsl.getNamespace()).isEqualTo(fromYaml.getNamespace());
        assertThat(fromDsl.getVersion()).isEqualTo(fromYaml.getVersion());

        assertThat(fromDsl.getCapabilities()).hasSameSizeAs(fromYaml.getCapabilities());
        for (int i = 0; i < fromYaml.getCapabilities().size(); i++) {
            var yamlCap = fromYaml.getCapabilities().get(i);
            var dslCap = fromDsl.getCapabilities().get(i);
            assertThat(dslCap.name()).isEqualTo(yamlCap.name());
            assertThat(dslCap.inputSchema()).isEqualTo(yamlCap.inputSchema());
            assertThat(dslCap.outputSchema()).isEqualTo(yamlCap.outputSchema());
        }

        assertThat(fromDsl.getGoals()).hasSameSizeAs(fromYaml.getGoals());
        for (int i = 0; i < fromYaml.getGoals().size(); i++) {
            assertThat(fromDsl.getGoals().get(i).getName())
                .isEqualTo(fromYaml.getGoals().get(i).getName());
            assertThat(fromDsl.getGoals().get(i).getKind())
                .isEqualTo(fromYaml.getGoals().get(i).getKind());
        }

        assertThat(fromDsl.getBindings()).hasSameSizeAs(fromYaml.getBindings());
        for (int i = 0; i < fromYaml.getBindings().size(); i++) {
            var yamlBinding = fromYaml.getBindings().get(i);
            var dslBinding = fromDsl.getBindings().get(i);
            assertThat(dslBinding.getName()).isEqualTo(yamlBinding.getName());
            assertThat(dslBinding.target().getClass())
                .as("binding '%s' target type", yamlBinding.getName())
                .isEqualTo(yamlBinding.target().getClass());
            if (yamlBinding.getWhen() != null) {
                assertThat(dslBinding.getWhen())
                    .as("binding '%s' should have a when condition", yamlBinding.getName())
                    .isNotNull();
            }
            if (yamlBinding.target() instanceof HumanTaskTarget yamlHT
                    && dslBinding.target() instanceof HumanTaskTarget dslHT) {
                assertThat(dslHT.title()).isEqualTo(yamlHT.title());
                assertThat(dslHT.expiresIn()).isEqualTo(yamlHT.expiresIn());
                assertThat(staticValues(dslHT.candidateGroups()))
                    .isEqualTo(staticValues(yamlHT.candidateGroups()));
                assertThat(dslHT.outputMapping().toString())
                    .isEqualTo(yamlHT.outputMapping().toString());
            }
        }

        assertThat(fromDsl.getCompletion()).isNotNull();
        assertThat(fromDsl.getCompletion().getClass())
            .isEqualTo(fromYaml.getCompletion().getClass());
    }

    private static java.util.Set<String> staticValues(CandidateSetSpec spec) {
        if (spec instanceof CandidateSetSpec.Inline inline
                && inline.strategy() instanceof StaticSetStrategy s) {
            return s.values();
        }
        throw new AssertionError("expected Inline with StaticSetStrategy, got " + spec);
    }
}
