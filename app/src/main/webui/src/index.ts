import { loadSite } from "@casehubio/pages-runtime";
import { page, tabs } from "@casehubio/pages-ui";
import "./datasets";
import { defaultTheme } from "./theme";
import { operationsView } from "./views/operations";
import { reviewsView } from "./views/reviews";
import { queueView } from "./views/queue";
import { reviewersView } from "./views/reviewers";
import { triageView } from "./views/triage";
import { systemView } from "./views/system";

const app = page("DevTown",
  tabs(
    ["Operations", operationsView],
    ["Reviews", reviewsView],
    ["Merge Queue", queueView],
    ["Reviewers", reviewersView],
    ["Triage", triageView],
    ["System", systemView],
  ),
  { settings: { mode: "dark" } },
);

const container = document.getElementById("app");
if (container) {
  loadSite(container, app).then(site => {
    site.setTheme(defaultTheme);
  }).catch(console.error);
}
