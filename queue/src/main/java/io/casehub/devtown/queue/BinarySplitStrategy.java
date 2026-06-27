package io.casehub.devtown.queue;

import java.util.List;
import java.util.Map;

public class BinarySplitStrategy implements BisectionSplitStrategy {

    @Override
    public SplitResult split(List<Map<String, Object>> prs, String batchId,
                              String targetBranch, int bisectionDepth,
                              String bisectionStrategy, String riskLevel) {
        if (prs.size() < 2) {
            throw new IllegalArgumentException("Cannot split a batch with fewer than 2 PRs");
        }

        int midpoint = prs.size() / 2;
        List<Map<String, Object>> leftPrs = List.copyOf(prs.subList(0, midpoint));
        List<Map<String, Object>> rightPrs = List.copyOf(prs.subList(midpoint, prs.size()));

        var left = new BatchSlice(
            batchId + "-L", targetBranch, leftPrs, leftPrs.size(),
            batchId, bisectionDepth, bisectionStrategy, riskLevel
        );
        var right = new BatchSlice(
            batchId + "-R", targetBranch, rightPrs, rightPrs.size(),
            batchId, bisectionDepth, bisectionStrategy, riskLevel
        );
        return new SplitResult(left, right);
    }
}
