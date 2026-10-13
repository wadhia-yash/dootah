import type { App, Environment, Metrics, Org, Release, Intent } from "./cloud";
export const demoOrg: Org = {
  id: "demo-org",
  name: "Acme workspace",
  role: "Owner",
};
export const demoApps: App[] = [{ id: "demo-app", name: "Acme Android" }];
export const demoEnvironments: Environment[] = [
  {
    id: "demo-prod",
    app_id: "demo-app",
    name: "production",
    runtime: "android-alpha-2",
    head: "demo-checkout",
  },
  {
    id: "demo-staging",
    app_id: "demo-app",
    name: "staging",
    runtime: "android-alpha-2",
    head: "demo-layout",
  },
];
const metrics: Metrics = {
  windowDays: 30,
  compatible: 1280,
  eligible: 320,
  adopted: 248,
  activated: 252,
  healthy: 246,
  rejected: 2,
  adoptionPercent: 77.5,
  healthPercent: 97.619,
  asOf: "2026-10-11T10:30:00Z",
};
export const demoReleases: Release[] = [
  {
    id: "demo-checkout",
    app_id: "demo-app",
    environment_id: "demo-prod",
    revision: "Checkout copy update",
    runtime: "android-alpha-2",
    status: "rolling_out",
    percentage: 25,
    version: 3,
    created_at: "2026-10-11T09:42:00Z",
    metrics,
  },
  {
    id: "demo-layout",
    app_id: "demo-app",
    environment_id: "demo-staging",
    revision: "Settings layout refinement",
    runtime: "android-alpha-2",
    status: "active",
    percentage: 100,
    version: 2,
    created_at: "2026-10-10T14:10:00Z",
    metrics: {
      ...metrics,
      compatible: 24,
      eligible: 24,
      adopted: 24,
      activated: 24,
      healthy: 24,
      rejected: 0,
      adoptionPercent: 100,
      healthPercent: 100,
    },
  },
  {
    id: "demo-previous",
    app_id: "demo-app",
    environment_id: "demo-prod",
    revision: "Welcome message",
    runtime: "android-alpha-2",
    status: "paused",
    percentage: 100,
    version: 4,
    created_at: "2026-10-09T08:00:00Z",
    metrics: {
      ...metrics,
      eligible: 1280,
      adopted: 612,
      adoptionPercent: 47.8,
    },
  },
];
export function applyDemo(release: Release, intent: Intent): Release {
  const status = {
    rollout: intent.body.percentage === 100 ? "active" : "rolling_out",
    pause: "paused",
    resume: release.percentage === 100 ? "active" : "rolling_out",
    rollback: "rolled_back",
    kill: "killed",
  }[intent.action];
  return {
    ...release,
    status,
    percentage: intent.body.percentage ?? release.percentage,
    version: release.version + 1,
    metrics: undefined,
    operation: null,
  };
}
