export type Org = { id: string; name: string; role: string };
export type App = { id: string; name: string };
export type Environment = {
  id: string;
  app_id: string;
  name: string;
  runtime: string;
  head: string | null;
};
export type Operation = { id: string; action: string; status: string };
export type Metrics = {
  windowDays: number;
  compatible: number;
  eligible: number;
  adopted: number;
  activated: number;
  healthy: number;
  rejected: number;
  adoptionPercent: number | null;
  healthPercent: number | null;
  asOf: string;
};
export type Release = {
  id: string;
  app_id: string;
  environment_id: string;
  revision: string;
  runtime: string;
  status: string;
  percentage: number;
  version: number;
  created_at: string;
  metrics?: Metrics;
  operation?: Operation | null;
};
export type Action = "rollout" | "pause" | "resume" | "rollback" | "kill";
export type Intent = {
  releaseId: string;
  action: Action;
  body: { version: number; percentage?: number; reason?: string };
  key: string;
};
export class ApiError extends Error {
  constructor(
    public code: string,
    public status: number,
    public quota?: { resource: string; limit: number; remaining: number; resetAt: string | null },
  ) {
    super(code);
  }
}
export class Cloud {
  csrf = "";
  org = "";
  // Browsers reject fetch called as a method of another object ("Illegal invocation").
  constructor(
    private transport: typeof fetch = (input, init) => fetch(input, init),
  ) {}
  async request<T>(
    path: string,
    method = "GET",
    body?: unknown,
    key?: string,
  ): Promise<T> {
    let response: Response;
    try {
      response = await this.transport(`/v1/${path}`, {
        method,
        credentials: "same-origin",
        signal: AbortSignal.timeout(15000),
        headers: {
          "Content-Type": "application/json",
          "Dootah-Organization": this.org,
          "X-CSRF-Token": this.csrf,
          ...(key ? { "Idempotency-Key": key } : {}),
        },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
      });
    } catch {
      throw new ApiError("network_error", 0);
    }
    let data;
    try {
      data = await response.json();
    } catch {
      throw new ApiError(
        response.ok ? "invalid_response" : "service_unavailable",
        response.status,
      );
    }
    if (!response.ok)
      throw new ApiError(data.error?.code || "request_failed", response.status, data.error?.quota);
    return data as T;
  }
  async login(email: string, password: string) {
    this.csrf = "";
    this.org = "";
    const session = await this.request<{ csrf: string }>("sessions", "POST", {
      email,
      password,
    });
    this.csrf = session.csrf;
    return this.request<{ items: Org[] }>("organizations");
  }
  async register(email: string, password: string, invitation?: string) {
    await this.request("users", "POST", { email, password, ...(invitation ? { invitation } : {}) });
    return this.login(email, password);
  }
  async restore() {
    this.csrf = "";
    this.org = "";
    const session = await this.request<{ csrf: string }>("sessions/current");
    this.csrf = session.csrf;
    return this.request<{ items: Org[] }>("organizations");
  }
  async all<T>(path: string): Promise<T[]> {
    const items: T[] = [];
    let cursor: string | null = null;
    const seen = new Set<string>();
    do {
      const page: { items: T[]; nextCursor?: string | null } =
        await this.request(
          `${path}${cursor ? `${path.includes("?") ? "&" : "?"}after=${encodeURIComponent(cursor)}` : ""}`,
        );
      items.push(...page.items);
      cursor = page.nextCursor ?? null;
      if (cursor && seen.has(cursor))
        throw new ApiError("invalid_pagination", 502);
      if (cursor) seen.add(cursor);
    } while (cursor);
    return items;
  }
  control(intent: Intent) {
    return this.request<Release | Operation>(
      `releases/${encodeURIComponent(intent.releaseId)}/${intent.action}`,
      "POST",
      intent.body,
      intent.key,
    );
  }
}
export function createIntent(
  release: Release,
  action: Action,
  percentage?: number,
  reason?: string,
): Intent {
  return {
    releaseId: release.id,
    action,
    body: {
      version: release.version,
      ...(action === "rollout" ? { percentage } : {}),
      ...(["rollback", "kill"].includes(action) ? { reason } : {}),
    },
    key: crypto.randomUUID(),
  };
}
export function pending(release: Release) {
  return ["pending", "running"].includes(release.operation?.status ?? "");
}
export function canControl(
  release: Release,
  action: Action,
  head: string | null | undefined,
) {
  if (
    pending(release) ||
    !["ready", "active", "rolling_out", "paused"].includes(release.status)
  )
    return false;
  if (action === "rollout") return release.status !== "paused";
  if (action === "pause")
    return ["active", "rolling_out"].includes(release.status);
  if (action === "resume")
    return release.status === "paused" && head === release.id;
  return head === release.id;
}
export function percent(value: number | null | undefined) {
  return value == null ? "—" : `${value.toFixed(1)}%`;
}
export function errorMessage(error: unknown) {
  const code = error instanceof ApiError ? error.code : "";
  if (error instanceof ApiError && error.code === 'quota_exceeded' && error.quota) {
    const q = error.quota;
    return `Hosted ${q.resource} limit reached (${q.limit}); ${q.remaining} remaining.${q.resetAt ? ` Next capacity release: ${q.resetAt}.` : ' Contact the platform operator.'}`;
  }
  return (
    (
      {
        registration_unavailable: "Registration is unavailable. Use a valid invitation for this email, or sign in to an existing account.",
        registrations_paused: "New registrations are temporarily paused. Existing accounts can sign in.",
        publications_paused: "New publications are temporarily paused. Existing release controls remain available.",
        artifact_too_large: "The OTA artifact exceeds the accepted size limit.",
        body_too_large: "The request exceeds the accepted size limit.",
        weak_password: "Use a password with at least 14 characters.",
        invalid_email: "Enter a valid email address.",
        conflict: "This account or resource already exists. Try signing in.",
        rate_limited: "Too many attempts. Wait a minute and try again.",
        stale_version:
          "This release changed in another session. Refresh and review the latest version before confirming again.",
        operation_pending:
          "Another operation is in progress. Refresh to see its status.",
        not_environment_head:
          "Only the current release for this environment can be rolled back.",
        forbidden: "Your account does not have permission for this action.",
        network_error:
          "Connection interrupted. The request may have reached Cloud. Retry the same request to keep its original identity.",
        service_unavailable:
          "Cloud is unavailable. Check the local Cloud connection and try again.",
        unauthenticated: "Your session has expired. Sign in again.",
        csrf: "Your session needs to be renewed. Sign in again.",
        origin:
          "Cloud rejected this origin. Check the same-origin proxy configuration.",
        idempotency_conflict:
          "This request conflicts with an existing operation. Refresh and review the release.",
        invalid_state:
          "This action is no longer available for the release. Refresh its status.",
      } as Record<string, string>
    )[code] ?? "The request could not be completed. Refresh and try again."
  );
}
