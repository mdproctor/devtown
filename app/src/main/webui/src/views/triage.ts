import {
  page, rows, columns, metric, table, title,
} from "@casehubio/pages-ui";
import { lookup, groupBy, col, sortBy } from "@casehubio/pages-ui";

export const triageView = page("Human Triage",
  rows(
    title("Human Triage", 2),

    // Metrics row
    columns([25, 25, 25, 25],
      [metric({ title: "Pending Decisions", lookup: lookup("triage", groupBy(null, col("pending"))), subtype: "plain-text" })],
      [metric({ title: "SLA Breached", lookup: lookup("triage", groupBy(null, col("breached"))), subtype: "plain-text" })],
      [metric({ title: "Escalated", lookup: lookup("triage", groupBy(null, col("escalated"))), subtype: "plain-text" })],
      [metric({ title: "Approved (24h)", lookup: lookup("triage", groupBy(null, col("approved24h"))), subtype: "plain-text" })],
    ),

    // Pending decisions table — sorted by SLA urgency
    title("Pending Decisions", 3),
    table({
      lookup: lookup("triage", sortBy("slaRemaining",
        groupBy("workItemId",
          col("pr"),
          col("decisionType"),
          col("candidateGroup"),
          col("slaRemaining"),
          col("escalationStage"),
          col("age")
        )
      )),
      sortable: true,
      filter: { enabled: true },
    }),
  ),
);
