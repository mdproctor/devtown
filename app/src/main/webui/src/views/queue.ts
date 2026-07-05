import {
  page, rows, columns, metric, table, barChart, timeseries, title,
} from "@casehubio/pages-ui";
import { lookup, groupBy, col } from "@casehubio/pages-ui";

export const queueView = page("Merge Queue",
  rows(
    title("Merge Queue", 2),

    // Metrics row
    columns([20, 20, 20, 20, 20],
      [metric({ title: "Queue Depth", lookup: lookup("merge-queue-metrics", groupBy(null, col("queueDepth"))), subtype: "plain-text" })],
      [metric({ title: "Active Batches", lookup: lookup("merge-queue-metrics", groupBy(null, col("activeBatches"))), subtype: "plain-text" })],
      [metric({ title: "24h Throughput", lookup: lookup("merge-queue-metrics", groupBy(null, col("throughput24h"))), subtype: "plain-text" })],
      [metric({ title: "Failure Rate", lookup: lookup("merge-queue-metrics", groupBy(null, col("failureRate"))), subtype: "plain-text" })],
      [metric({ title: "Adaptive Max", lookup: lookup("merge-queue-metrics", groupBy(null, col("adaptiveMax"))), subtype: "plain-text" })],
    ),

    // Charts row — wait time distribution + throughput timeseries
    title("Analytics", 3),
    columns([50, 50],
      [barChart({
        title: "Wait Time Distribution by Lane",
        lookup: lookup("merge-queue-metrics", groupBy("lane", col("lane"), col("avgWaitTime"))),
      })],
      [timeseries({
        title: "Merge Throughput (24h)",
        lookup: lookup("merge-queue-metrics", groupBy("hour", col("hour"), col("throughput"))),
      })],
    ),

    // Failure rate trend
    title("Failure Rate Trend", 3),
    timeseries({
      lookup: lookup("merge-queue-metrics", groupBy("hour", col("hour"), col("failureRate"))),
    }),

    // Adaptive sizing per repository
    title("Adaptive Batch Sizing", 3),
    columns([50, 50],
      [metric({ title: "Current Adaptive Max", lookup: lookup("merge-queue-metrics", groupBy(null, col("adaptiveMax"))), subtype: "plain-text" })],
      [metric({ title: "Configured Max", lookup: lookup("merge-queue-metrics", groupBy(null, col("maxBatchSize"))), subtype: "plain-text" })],
    ),

    // Queued PRs table
    title("Queued PRs", 3),
    table({
      lookup: lookup("merge-queue", groupBy("prNumber",
        col("prNumber"),
        col("author"),
        col("lane"),
        col("trustScore"),
        col("waitTime"),
        col("dependsOn")
      )),
      sortable: true,
      filter: { enabled: true },
    }),

    // Active Batches table
    title("Active Batches", 3),
    table({
      lookup: lookup("merge-queue-metrics", groupBy("batchId",
        col("batchId"),
        col("prCount"),
        col("riskLevel"),
        col("dispatchedTime"),
        col("status")
      )),
      sortable: true,
      filter: { enabled: true },
    }),
  ),
);
