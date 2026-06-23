package io.casehub.devtown.domain;

public interface CiStatusClient {
    CombinedCiStatus getCombinedStatus(String owner, String repo, String headSha);
}
