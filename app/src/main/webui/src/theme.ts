import { DARK_THEME } from "@casehubio/pages-runtime";
import type { PagesTheme } from "@casehubio/pages-runtime";

export const devtownDark: PagesTheme = {
  ...DARK_THEME,
  accent: "#7c8cf8",
  accentHover: "#9ba6ff",
};

export const defaultTheme = devtownDark;
