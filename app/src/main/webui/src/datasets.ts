import { dataset } from "@casehubio/pages-ui";

// REST datasets — resolved relative to the Quarkus server
dataset("queue-status", "/api/governance/queue-status");
dataset("recent-events", "/api/governance/recent-events?limit=100");
dataset("system-health", "/api/governance/system-health");
dataset("problems", "/api/governance/problems");
dataset("reviewers", "/api/governance/reviewers");
dataset("merge-queue", "/api/governance/merge-queue");
dataset("merge-queue-metrics", "/api/governance/merge-queue/metrics");
dataset("triage", "/api/governance/triage");
