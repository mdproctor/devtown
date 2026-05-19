package io.casehub.devtown.app;

import io.casehub.api.engine.YamlCaseHub;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PrReviewCaseHub extends YamlCaseHub {

    public PrReviewCaseHub() {
        super("devtown/pr-review.yaml");
    }
}
