package io.casehub.devtown.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckSuitesResponse(
    @JsonProperty("total_count") int totalCount,
    @JsonProperty("check_suites") List<CheckSuite> checkSuites
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckSuite(
        long id,
        String status,
        String conclusion
    ) {}
}
