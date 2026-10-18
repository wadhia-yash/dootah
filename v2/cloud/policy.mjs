// Hosted policy is explicitly opt-in; self-hosted installations keep their behavior.
export function hostedPolicy(env = process.env) {
  const flag = (name, fallback) => {
    const value = env['DOOTAH_' + name];
    if (value === undefined) return fallback;
    if (!['true', 'false'].includes(value)) throw Error(`configuration: DOOTAH_${name}`);
    return value === 'true';
  };
  const number = (name, fallback, min = 1, max = 1000000000000) => {
    const value = env['DOOTAH_' + name] ?? String(fallback);
    if (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value)) || +value < min || +value > max)
      throw Error(`configuration: DOOTAH_${name}`);
    return Number(value);
  };
  const p = {
    enabled: flag('HOSTED_ALPHA', false),
    inviteOnly: flag('INVITE_ONLY', true),
    organizations: number('HOSTED_ORGANIZATIONS', 5),
    apps: number('HOSTED_APPS', 1), environments: number('HOSTED_ENVIRONMENTS', 2),
    publishes: number('HOSTED_PUBLISHES', 10), windowDays: number('HOSTED_WINDOW_DAYS', 30, 1, 365),
    installations: number('HOSTED_INSTALLATIONS', 10),
    artifactBytes: number('HOSTED_ARTIFACT_BYTES', 5 * 1024 * 1024),
    pendingInvitations: number('HOSTED_PENDING_INVITATIONS', 10),
    checkIntervalHours: 12, // Guidance for future APK configuration; never an HTTP polling gate.
    requestBudget: number('HOSTED_MONTHLY_REQUESTS', 0, 0),
    transferBudget: number('HOSTED_MONTHLY_BYTES', 0, 0),
    warningPercent: number('HOSTED_WARNING_PERCENT', 70, 1, 99),
    criticalPercent: number('HOSTED_CRITICAL_PERCENT', 85, 1, 100),
    rates: {
      authentication: number('RATE_AUTHENTICATION', 60), registration: number('RATE_REGISTRATION', 5), login: number('RATE_LOGIN', 20),
      loginEmail: number('RATE_LOGIN_EMAIL', 10), ip: number('RATE_IP', 300),
      delivery: number('RATE_DELIVERY', 600), user: number('RATE_USER', 300),
      mutation: number('RATE_MUTATION', 20), publish: number('RATE_PUBLISH', 30),
      read: number('RATE_READ', 120), global: number('RATE_GLOBAL', 1200),
      concurrent: number('RATE_CONCURRENT', 8, 1, 100),
    },
  };
  if (p.warningPercent >= p.criticalPercent) throw Error('configuration: hosted warning thresholds');
  if (p.enabled && env.DOOTAH_MODE === 'production' && (!p.requestBudget || !p.transferBudget))
    throw Error('configuration: hosted monthly budgets required');
  return Object.freeze(p);
}
export const policy = hostedPolicy();
