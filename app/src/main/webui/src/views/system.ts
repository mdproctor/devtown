import {
  page, metric, columns, rows, table, barChart, title,
} from "@casehubio/pages-ui";
import { lookup, groupBy, col } from "@casehubio/pages-ui";

export const systemView = page("System",
  rows(
    title("System Health", 2),

    // Health metrics row
    columns([25, 25, 25, 25],
      [metric({ title: "Active Cases", lookup: lookup("system-health", groupBy(null, col("activeCases"))), subtype: "plain-text" })],
      [metric({ title: "Fleet Size", lookup: lookup("system-health", groupBy(null, col("fleetSize"))), subtype: "plain-text" })],
      [metric({ title: "Open Commitments", lookup: lookup("system-health", groupBy(null, col("openCommitments"))), subtype: "plain-text" })],
      [metric({ title: "Pending Work Items", lookup: lookup("system-health", groupBy(null, col("pendingWorkItems"))), subtype: "plain-text" })],
    ),

    // Body — trust chart + problems table
    columns([50, 50],
      [barChart({
        title: "Average Trust by Capability",
        lookup: lookup("system-health", groupBy("capability", col("capability"), col("avgTrust"))),
      })],
      [table({
        title: "Problems",
        lookup: lookup("problems"),
        sortable: true,
      })],
    ),

    // Queue health
    title("Queue Health", 3),
    columns([25, 25, 25, 25],
      [metric({ title: "Queue Depth", lookup: lookup("merge-queue-metrics", groupBy(null, col("queueDepth"))), subtype: "plain-text" })],
      [metric({ title: "Oldest Wait", lookup: lookup("merge-queue-metrics", groupBy(null, col("oldestWait"))), subtype: "plain-text" })],
      [metric({ title: "Avg Wait", lookup: lookup("merge-queue-metrics", groupBy(null, col("avgWait"))), subtype: "plain-text" })],
      [metric({ title: "Failure Rate", lookup: lookup("merge-queue-metrics", groupBy(null, col("failureRate"))), subtype: "plain-text" })],
    ),
  ),
);
