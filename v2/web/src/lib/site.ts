// One build serves "/" beside a self-hosted Cloud, or a static host's base path.
const base = import.meta.env.BASE_URL;
export const site = (path = "") => base + path.replace(/^\//, "");
export const consoleHref = (demo = false) =>
  site(demo ? "console/?demo=1" : "console/");
export const isConsolePath = (pathname: string) =>
  pathname.startsWith(site("console"));
// Static builds (VITE_DOOTAH_CLOUD=none) have no Cloud API at their origin.
export const cloudAvailable = import.meta.env.VITE_DOOTAH_CLOUD !== "none";
export const repo = "https://github.com/wadhia-yash/dootah";
export const docs = `${repo}/blob/main/docs`;
export const feedback = `${repo}/issues/new/choose`;
