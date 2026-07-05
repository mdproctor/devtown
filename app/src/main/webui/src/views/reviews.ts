import {
  page, tabs, rows, columns, metric, table, title, selector,
} from "@casehubio/pages-ui";
import { lookup, groupBy, col } from "@casehubio/pages-ui";

// List page — all reviews with status filter
const reviewsList = rows(
  title("Reviews", 2),

  selector({
    title: "Status Filter",
    lookup: lookup("queue-status", groupBy(null, col("status"))),
    options: ["Active", "Waiting", "Completed", "Faulted", "Cancelled"],
  }),

  table({
    lookup: lookup("queue-status", groupBy("caseId",
      col("pr"),
      col("contributor"),
      col("status"),
      col("capabilitiesCompleted"),
      col("capabilitiesTotal"),
      col("started"),
      col("duration"),
      col("reviewers")
    )),
    sortable: true,
    filter: { enabled: true },
  }),
);

// Detail page — full review breakdown
const reviewDetail = rows(
  title("Review Detail", 2),

  // PR header metrics
  columns([20, 20, 20, 20, 20],
    [metric({ title: "Repository", lookup: lookup("queue-status", groupBy(null, col("repo"))), subtype: "plain-text" })],
    [metric({ title: "PR Number", lookup: lookup("queue-status", groupBy(null, col("prNumber"))), subtype: "plain-text" })],
    [metric({ title: "Author", lookup: lookup("queue-status", groupBy(null, col("author"))), subtype: "plain-text" })],
    [metric({ title: "Lines Changed", lookup: lookup("queue-status", groupBy(null, col("linesChanged"))), subtype: "plain-text" })],
    [metric({ title: "Status", lookup: lookup("queue-status", groupBy(null, col("status"))), subtype: "plain-text" })],
  ),

  // Capability progress
  title("Capability Progress", 3),
  columns([20, 20, 20, 20, 20],
    [metric({ title: "Code Analysis", lookup: lookup("queue-status", groupBy(null, col("codeAnalysisState"))), subtype: "plain-text" })],
    [metric({ title: "Security Review", lookup: lookup("queue-status", groupBy(null, col("securityState"))), subtype: "plain-text" })],
    [metric({ title: "Architecture Review", lookup: lookup("queue-status", groupBy(null, col("architectureState"))), subtype: "plain-text" })],
    [metric({ title: "Style Review", lookup: lookup("queue-status", groupBy(null, col("styleState"))), subtype: "plain-text" })],
    [metric({ title: "CI", lookup: lookup("queue-status", groupBy(null, col("ciState"))), subtype: "plain-text" })],
  ),

  // Timeline + Routing
  title("Timeline & Routing", 3),
  columns([60, 40],
    [table({
      title: "Event Timeline",
      lookup: lookup("recent-events", groupBy("eventId", col("timestamp"), col("eventType"), col("actor"), col("detail"))),
      sortable: true,
    })],
    [rows(
      metric({ title: "Trust Score", lookup: lookup("reviewers", groupBy(null, col("trustScore"))), subtype: "plain-text" }),
      metric({ title: "Threshold", lookup: lookup("reviewers", groupBy(null, col("threshold"))), subtype: "plain-text" }),
      metric({ title: "Phase", lookup: lookup("reviewers", groupBy(null, col("phase"))), subtype: "plain-text" }),
      metric({ title: "Observations", lookup: lookup("reviewers", groupBy(null, col("observations"))), subtype: "plain-text" }),
    )],
  ),

  // Compliance + Provenance
  title("Compliance & Provenance", 3),
  columns([30, 70],
    [rows(
      metric({ title: "Audit Chain", lookup: lookup("queue-status", groupBy(null, col("auditChain"))), subtype: "plain-text" }),
      metric({ title: "SLA", lookup: lookup("queue-status", groupBy(null, col("sla"))), subtype: "plain-text" }),
      metric({ title: "Trust Routing", lookup: lookup("queue-status", groupBy(null, col("trustRouting"))), subtype: "plain-text" }),
      metric({ title: "GDPR", lookup: lookup("queue-status", groupBy(null, col("gdpr"))), subtype: "plain-text" }),
    )],
    [table({
      title: "PROV-DM Provenance",
      lookup: lookup("queue-status", groupBy("entryId", col("timestamp"), col("activity"), col("entity"), col("agent"), col("causedBy"))),
      sortable: true,
    })],
  ),
);

// View with tabs for list/detail navigation
export const reviewsView = page("Reviews",
  tabs(
    ["List", reviewsList],
    ["Detail", reviewDetail],
  ),
);
