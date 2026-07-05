import {
  page, rows, columns, metric, table, barChart, title,
} from "@casehubio/pages-ui";
import { lookup, groupBy, col } from "@casehubio/pages-ui";

// Upgrade to dockBar() when casehub-pages#64 ships
export const operationsView = page("Operations",
  rows(
    // Three-column layout: left (active reviews + merge queue), center (metrics/detail), right (context)
    columns([20, 50, 30],
      // Left dock — Active Reviews + Merge Queue
      [rows(
        title("Active Reviews", 3),
        table({
          lookup: lookup("queue-status", groupBy("caseId", col("pr"), col("contributor"), col("status"), col("capabilityProgress"), col("started"), col("lastEvent"))),
          sortable: true,
          filter: { enabled: true },
        }),

        title("Merge Queue", 3),
        table({
          lookup: lookup("merge-queue", groupBy("prNumber", col("prNumber"), col("lane"), col("author"), col("trustScore"), col("waitTime"), col("status"))),
          sortable: true,
          filter: { enabled: true },
        }),
      )],

      // Center — default metrics + review detail when selected
      [rows(
        title("Workbench", 2),
        columns([25, 25, 25, 25],
          [metric({ title: "Active Cases", lookup: lookup("system-health", groupBy(null, col("activeCases"))), subtype: "plain-text" })],
          [metric({ title: "Waiting on Human", lookup: lookup("triage", groupBy(null, col("pending"))), subtype: "plain-text" })],
          [metric({ title: "Completed Today", lookup: lookup("queue-status", groupBy(null, col("completedToday"))), subtype: "plain-text" })],
          [metric({ title: "Problems", lookup: lookup("problems", groupBy(null, col("count"))), subtype: "plain-text" })],
        ),
        barChart({
          title: "Review Throughput (24h)",
          lookup: lookup("queue-status", groupBy("hour", col("hour"), col("throughput"))),
        }),
      )],

      // Right dock — context panel (routing, trust, commitment)
      [rows(
        title("Context", 3),
        metric({ title: "Routing Rationale", lookup: lookup("queue-status", groupBy(null, col("routingRationale"))), subtype: "plain-text" }),
        barChart({
          title: "Trust Scores",
          lookup: lookup("reviewers", groupBy("capability", col("capability"), col("trustScore"))),
        }),
        metric({ title: "Commitment State", lookup: lookup("queue-status", groupBy(null, col("commitmentState"))), subtype: "plain-text" }),
      )],
    ),

    // Bottom dock — Event Stream
    title("Event Stream", 3),
    table({
      lookup: lookup("recent-events", groupBy("eventId", col("timestamp"), col("caseId"), col("eventType"), col("actor"), col("status"))),
      sortable: true,
      filter: { enabled: true },
    }),
  ),
);
