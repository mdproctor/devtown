import {
  page, tabs, rows, columns, metric, table, barChart, title,
} from "@casehubio/pages-ui";
import { lookup, groupBy, col } from "@casehubio/pages-ui";

// List page — all reviewers with trust scores
const reviewersList = rows(
  title("Reviewers", 2),

  table({
    lookup: lookup("reviewers", groupBy("actorId",
      col("actorId"),
      col("openCommitments"),
      col("trustCodeAnalysis"),
      col("trustSecurity"),
      col("trustArchitecture"),
      col("trustStyle"),
      col("totalDecisions"),
      col("maturityPhase")
    )),
    sortable: true,
    filter: { enabled: true },
  }),
);

// Profile page — detailed reviewer breakdown
const reviewerProfile = rows(
  title("Reviewer Profile", 2),

  // Header metrics
  columns([25, 25, 25, 25],
    [metric({ title: "Agent Name", lookup: lookup("reviewers", groupBy(null, col("agentName"))), subtype: "plain-text" })],
    [metric({ title: "Phase", lookup: lookup("reviewers", groupBy(null, col("maturityPhase"))), subtype: "plain-text" })],
    [metric({ title: "Total Decisions", lookup: lookup("reviewers", groupBy(null, col("totalDecisions"))), subtype: "plain-text" })],
    [metric({ title: "Open Commitments", lookup: lookup("reviewers", groupBy(null, col("openCommitments"))), subtype: "plain-text" })],
  ),

  // Trust charts — by capability and by dimension
  title("Trust Scores", 3),
  columns([50, 50],
    [barChart({
      title: "Trust by Capability",
      lookup: lookup("reviewers", groupBy("capability", col("capability"), col("trustScore"))),
    })],
    [barChart({
      title: "Trust by Dimension",
      lookup: lookup("reviewers", groupBy("dimension", col("dimension"), col("trustScore"))),
    })],
  ),

  // Review history — recent outcomes with trust delta
  title("Review History", 3),
  table({
    lookup: lookup("reviewers", groupBy("reviewId",
      col("timestamp"),
      col("caseId"),
      col("capability"),
      col("outcome"),
      col("trustDelta")
    )),
    sortable: true,
  }),

  // Active commitments
  title("Active Commitments", 3),
  table({
    lookup: lookup("reviewers", groupBy("commitmentId",
      col("caseId"),
      col("capability"),
      col("createdAt"),
      col("slaRemaining"),
      col("status")
    )),
    sortable: true,
  }),

  // Incidents — FLAGGED attestations
  title("Incidents", 3),
  table({
    lookup: lookup("reviewers", groupBy("incidentId",
      col("timestamp"),
      col("severity"),
      col("capability"),
      col("description"),
      col("trustImpact")
    )),
    sortable: true,
  }),
);

// View with tabs for list/profile navigation
export const reviewersView = page("Reviewers",
  tabs(
    ["List", reviewersList],
    ["Profile", reviewerProfile],
  ),
);
