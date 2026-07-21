import {
  page, rows, table, title, tabs,
} from "@casehubio/pages-ui";
import { lookup, groupBy, col } from "@casehubio/pages-ui";

const definitionsList = rows(
  title("Case Definitions", 2),
  table({
    lookup: lookup("case-definitions", groupBy("name",
      col("namespace"), col("name"), col("version"), col("title")
    )),
    sortable: true,
    filter: { enabled: true },
  }),
);

const definitionDetail = rows(
  title("Definition Detail", 2),

  title("Goals", 3),
  table({
    lookup: lookup("case-definitions", groupBy("goalName",
      col("goalName"), col("goalKind"), col("goalCondition")
    )),
    sortable: true,
  }),

  title("Bindings", 3),
  table({
    lookup: lookup("case-definitions", groupBy("bindingName",
      col("bindingName"), col("targetType"), col("targetName"),
      col("conflictStrategy"), col("outcomePolicy")
    )),
    sortable: true,
  }),

  title("Capabilities", 3),
  table({
    lookup: lookup("case-definitions", groupBy("capabilityName",
      col("capabilityName"), col("inputSchema"), col("outputSchema")
    )),
    sortable: true,
  }),
);

export const definitionsView = page("Definitions",
  tabs(
    ["List", definitionsList],
    ["Detail", definitionDetail],
  ),
);
