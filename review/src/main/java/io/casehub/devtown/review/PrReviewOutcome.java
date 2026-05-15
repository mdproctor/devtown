package io.casehub.devtown.review;

import java.util.List;

public record PrReviewOutcome(String verdict, List<String> findings) {}
