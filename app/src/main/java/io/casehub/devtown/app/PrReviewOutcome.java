package io.casehub.devtown.app;

import java.util.List;

public record PrReviewOutcome(String verdict, List<String> findings) {}
