import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import {
  ArrowLeft,
  ArrowUpRight,
  ArrowRight,
  BookOpen,
  Box,
  ChevronRight,
  CircleHelp,
  Clock3,
  GitFork,
  Layers,
  LogOut,
  Pause,
  Play,
  Radio,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldCheck,
  TriangleAlert,
  X,
  Loader2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Brand } from "@/components/brand";
import {
  ApiError,
  Cloud,
  canControl,
  createIntent,
  errorMessage,
  pending,
  percent,
  type Action,
  type App,
  type Environment,
  type Intent,
  type Org,
  type Release,
} from "@/lib/cloud";
import {
  applyDemo,
  demoApps,
  demoEnvironments,
  demoOrg,
  demoReleases,
} from "@/lib/demo";
import { cloudAvailable, consoleHref, docs, site } from "@/lib/site";
const statuses: Record<string, string> = {
  rolling_out: "Rolling out",
  active: "Active",
  paused: "Paused",
  ready: "Ready",
  draft: "Publishing",
  failed: "Failed",
  rolled_back: "Rolled back",
  killed: "Killed",
};
const actionNames: Record<Action, string> = {
  rollout: "Update rollout",
  pause: "Pause release",
  resume: "Resume release",
  rollback: "Roll back to native",
  kill: "Kill release",
};
function Status({ status }: { status: string }) {
  return (
    <Badge variant="outline" className={`release-status status-${status}`}>
      <span className="status-dot" />
      {statuses[status] ?? status}
    </Badge>
  );
}
function date(value: string) {
  return new Date(value).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}
export default function Console() {
  const demo = new URLSearchParams(window.location.search).get("demo") === "1";
  const [session, setSession] = useState<{ csrf: string; orgs: Org[] } | null>(
    demo ? { csrf: "", orgs: [demoOrg] } : null,
  );
  const [org, setOrg] = useState(demo ? demoOrg.id : "");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const cloud = useMemo(() => {
    const c = new Cloud();
    c.csrf = session?.csrf ?? "";
    c.org = org;
    return c;
  }, [session, org]);
  async function login(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    const c = new Cloud();
    try {
      const { items } = await c.login(
        String(data.get("email")),
        String(data.get("password")),
      );
      setSession({ csrf: c.csrf, orgs: items });
      setOrg(items[0]?.id ?? "");
    } catch (e) {
      setError(
        e instanceof ApiError && e.code === "unauthenticated"
          ? "Sign-in failed. Check your email and password."
          : errorMessage(e),
      );
    } finally {
      setBusy(false);
    }
  }
  async function logout() {
    if (demo) {
      window.location.assign(consoleHref());
      return;
    }
    setBusy(true);
    try {
      await cloud.request("sessions/current", "DELETE");
      setSession(null);
      setOrg("");
      setError("");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        setSession(null);
        setOrg("");
      } else setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  const expired = () => {
    setSession(null);
    setOrg("");
    setError("Your session has expired. Sign in again to continue.");
  };
  if (!session)
    return (
      <div className="login-page">
        <header className="wrap site-header">
          <Brand />
          <a className="text-link" href={site()}>
            Back to website <ArrowUpRight size={15} />
          </a>
        </header>
        <main className="login-layout wrap">
          <div className="login-copy">
            <div className="eyebrow">DOOTAH CLOUD</div>
            <h1>
              A clear view of
              <br />
              every release.
            </h1>
            <p>
              Roll out supported updates, follow adoption, and return to your
              native baseline when you need to.
            </p>
            <div className="login-feature">
              <ShieldCheck /> Your infrastructure. Your release controls.
            </div>
            <div className="login-feature">
              <Layers /> Built for native Kotlin and Compose.
            </div>
            <a className="text-link" href={consoleHref(true)}>
              Explore with sample data <ArrowRight size={16} />
            </a>
          </div>
          <div className="login-card">
            <span className="release-icon">
              <Layers />
            </span>
            {cloudAvailable ? (
              <>
                <h2>Welcome back</h2>
                <p>Sign in to your self-hosted Dootah Cloud.</p>
                <form onSubmit={login}>
                  <Label htmlFor="email">Email address</Label>
                  <Input
                    id="email"
                    name="email"
                    type="email"
                    autoComplete="username"
                    placeholder="you@company.com"
                    required
                  />
                  <Label htmlFor="password">Password</Label>
                  <Input
                    id="password"
                    name="password"
                    type="password"
                    autoComplete="current-password"
                    required
                  />
                  <Button type="submit" disabled={busy}>
                    {busy ? <Loader2 className="spin" size={16} /> : "Sign in"}
                    <ArrowRight size={16} />
                  </Button>
                </form>
                {error && (
                  <p className="error-box" role="alert">
                    {error}
                  </p>
                )}
              </>
            ) : (
              <>
                <h2>Connect your Cloud</h2>
                <p>
                  This public site hosts no Cloud or accounts. To sign in, serve
                  this console from your self-hosted Dootah Cloud.
                </p>
                <Button className="static-cloud-action" asChild>
                  <a href={`${docs}/self-hosting/README.md`}>
                    Self-hosting guide <ArrowUpRight size={16} />
                  </a>
                </Button>
              </>
            )}
            {cloudAvailable && (
              <div className="login-help">
                <CircleHelp size={15} />
                <span>
                  Accounts are provisioned by your Cloud operator.{" "}
                  <a href={`${docs}/self-hosting/README.md`}>Setup guide</a>
                </span>
              </div>
            )}
            <a className="demo-entry" href={consoleHref(true)}>
              Just looking? Open the demo console <ArrowUpRight size={14} />
            </a>
          </div>
        </main>
        <footer className="login-footer">
          Experimental alpha.2 · No publicly hosted account service
        </footer>
      </div>
    );
  return (
    <div className="console-shell">
      <aside className="console-sidebar">
        <Brand />
        <div className="workspace-select">
          <span>WORKSPACE</span>
          <select
            aria-label="Organization"
            value={org}
            onChange={(e) => {
              setError("");
              setOrg(e.target.value);
            }}
          >
            {session.orgs.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </div>
        <div className="sidebar-label">MANAGE</div>
        <nav aria-label="Console navigation">
          <a className="selected" href={consoleHref(demo)}>
            <Layers size={17} /> Releases <span>↗</span>
          </a>
          <a href={`${docs}/concepts/delivery.md`}>
            <Radio size={17} /> Delivery guide <ArrowUpRight size={13} />
          </a>
          <a href={`${docs}/security/README.md`}>
            <ShieldCheck size={17} /> Security model <ArrowUpRight size={13} />
          </a>
        </nav>
        <div className="sidebar-bottom">
          <div className="alpha-card">
            <span className="status-dot" /> BUILT IN THE OPEN
            <p>
              Small, deliberate steps.
              <br />
              Native all the way.
            </p>
            <a href="https://github.com/wadhia-yash/dootah">
              <GitFork size={15} /> Follow on GitHub <ArrowUpRight size={13} />
            </a>
          </div>
          <a href={`${docs}/README.md`}>
            <BookOpen size={16} /> Documentation <ArrowUpRight size={13} />
          </a>
          <button disabled={busy} onClick={logout}>
            <LogOut size={16} />
            {demo ? "Exit demo" : "Sign out"}
          </button>
          <small>Dootah · Experimental alpha.2</small>
        </div>
      </aside>
      <div className="console-main">
        <header className="console-topbar">
          <span>
            Workspace <ChevronRight size={14} /> <b>Releases</b>
          </span>
          <div>
            <span className="connection-label">
              <span className="status-dot" />
              {demo ? "Sample workspace" : "Connected session"}
            </span>
            <span className="avatar">
              {session.orgs.find((o) => o.id === org)?.name.slice(0, 1) ?? "D"}
            </span>
          </div>
        </header>
        {demo && (
          <section className="demo-banner" aria-label="Demo workspace notice">
            <Box size={16} />
            <span>
              <b>Demo workspace.</b> Sample data only. Actions are simulated in
              this browser and reset on reload.
            </span>
            <a href={consoleHref()}>
              Connect to Cloud <ArrowUpRight size={13} />
            </a>
          </section>
        )}
        {error && (
          <p className="error-box" role="alert">
            {error}
          </p>
        )}
        {org ? (
          <Workspace
            key={org}
            cloud={cloud}
            demo={demo}
            role={session.orgs.find((o) => o.id === org)?.role ?? ""}
            onExpired={expired}
          />
        ) : (
          <div className="empty-state">
            <Box />
            <h2>No workspace yet</h2>
            <p>
              Ask your operator for organization access, or create one with the
              existing Dootah CLI.
            </p>
            <a href={`${docs}/getting-started/quickstart.md`}>
              Open setup guide
            </a>
          </div>
        )}
      </div>
    </div>
  );
}
function Workspace({
  cloud,
  demo,
  role,
  onExpired,
}: {
  cloud: Cloud;
  demo: boolean;
  role: string;
  onExpired: () => void;
}) {
  const [apps, setApps] = useState<App[]>(demo ? demoApps : []);
  const [environments, setEnvironments] = useState<Environment[]>(
    demo ? structuredClone(demoEnvironments) : [],
  );
  const [releases, setReleases] = useState<Release[]>(
    demo ? structuredClone(demoReleases) : [],
  );
  const [selected, setSelected] = useState<Release | null>(null);
  const [query, setQuery] = useState("");
  const [app, setApp] = useState("all");
  const [environment, setEnvironment] = useState("all");
  const [loading, setLoading] = useState(!demo);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [dialog, setDialog] = useState<Action | null>(null);
  const [rollout, setRollout] = useState(25);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [intent, setIntent] = useState<Intent | null>(null);
  const [retry, setRetry] = useState(false);
  const [dialogError, setDialogError] = useState("");
  const detailSequence = useRef(0);
  const mounted = useRef(true);
  const controlRole = ["Owner", "Developer"].includes(role);
  function fail(e: unknown) {
    if (!mounted.current) return;
    if (e instanceof ApiError && (e.status === 401 || e.code === "csrf"))
      onExpired();
    else setError(errorMessage(e));
  }
  async function refresh() {
    setLoading(true);
    setError("");
    try {
      if (!demo) {
        const [a, e, r] = await Promise.all([
          cloud.all<App>("apps"),
          cloud.all<Environment>("environments"),
          cloud.all<Release>("releases"),
        ]);
        if (!mounted.current) return;
        setApps(a);
        setEnvironments(e);
        setReleases(r);
      }
    } catch (e) {
      fail(e);
    } finally {
      if (mounted.current) setLoading(false);
    }
  }
  useEffect(() => {
    mounted.current = true;
    void refresh();
    return () => {
      mounted.current = false;
      detailSequence.current++;
    };
  }, []);
  async function detail(id: string) {
    const sequence = ++detailSequence.current;
    setDetailLoading(true);
    setError("");
    if (selected?.id !== id) setSelected(null);
    try {
      const release = demo
        ? releases.find((r) => r.id === id)!
        : await cloud.request<Release>(`releases/${encodeURIComponent(id)}`);
      if (sequence !== detailSequence.current || !mounted.current) return;
      setSelected(release);
      setReleases((rows) =>
        rows.map((r) => (r.id === release.id ? release : r)),
      );
      setRollout(release.percentage);
    } catch (e) {
      if (sequence === detailSequence.current) fail(e);
    } finally {
      if (sequence === detailSequence.current && mounted.current)
        setDetailLoading(false);
    }
  }
  useEffect(() => {
    if (demo || !selected || !pending(selected)) return;
    const id = selected.id;
    const timer = setTimeout(() => void detail(id), 4000);
    return () => clearTimeout(timer);
  }, [selected, demo]);
  const visible = releases
    .filter(
      (r) =>
        (app === "all" || r.app_id === app) &&
        (environment === "all" || r.environment_id === environment) &&
        r.revision.toLowerCase().includes(query.toLowerCase()),
    )
    .sort((a, b) => b.created_at.localeCompare(a.created_at));
  const envFor = (r: Release) =>
    environments.find((e) => e.id === r.environment_id);
  function openAction(action: Action) {
    setDialog(action);
    setDialogError("");
    setReason("");
    setIntent(null);
    setRetry(false);
  }
  async function submitAction() {
    if (!selected || !dialog || busy) return;
    const next =
      intent ?? createIntent(selected, dialog, rollout, reason.trim());
    setIntent(next);
    setBusy(true);
    setDialogError("");
    try {
      if (demo) {
        const updated = applyDemo(selected, next);
        setReleases((rows) =>
          rows.map((r) =>
            r.id === updated.id
              ? updated
              : next.action === "rollout" &&
                  r.environment_id === updated.environment_id &&
                  ["active", "rolling_out"].includes(r.status)
                ? { ...r, status: "paused", version: r.version + 1 }
                : r,
          ),
        );
        if (next.action === "rollout")
          setEnvironments((rows) =>
            rows.map((e) =>
              e.id === updated.environment_id ? { ...e, head: updated.id } : e,
            ),
          );
        setSelected(updated);
        setNotice(
          "Demo action applied locally. No update was sent to any device.",
        );
      } else {
        await cloud.control(next);
        setNotice(
          ["rollback", "kill"].includes(next.action)
            ? "Request accepted. Cloud operation status is shown below; device recovery still requires a future check and launch."
            : "Release control saved. New offers follow the updated settings.",
        );
        // A successful mutation is never retried because a subsequent read failed.
        setDialog(null);
        setIntent(null);
        setRetry(false);
        await refresh();
        await detail(selected.id);
      }
      setDialog(null);
      setIntent(null);
      setRetry(false);
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.code === "csrf")) {
        onExpired();
        return;
      }
      const ambiguous =
        !(e instanceof ApiError) ||
        e.status === 0 ||
        e.status >= 500 ||
        e.code === "invalid_response";
      setRetry(ambiguous);
      if (!ambiguous) setIntent(null);
      if (e instanceof ApiError && e.code === "stale_version") {
        setDialog(null);
        await refresh();
        await detail(selected.id);
        setError(errorMessage(e));
      } else setDialogError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }
  const head = selected ? envFor(selected)?.head : null;
  return (
    <main className="workspace-main">
      <div className="console-heading">
        <div>
          <div className="eyebrow">RELEASE MANAGEMENT</div>
          <h1>Every update. Under control.</h1>
          <p>Ship a little, learn a little. Keep your native baseline close.</p>
        </div>
        <Button
          variant="outline"
          disabled={loading || detailLoading || busy}
          onClick={async () => {
            await refresh();
            if (selected) await detail(selected.id);
          }}
        >
          <RefreshCw size={15} className={loading ? "spin" : ""} /> Refresh
        </Button>
      </div>
      <div className="overview-stats">
        <div>
          <span>
            <Layers size={16} /> Total releases
          </span>
          <strong>
            {loading ? "—" : releases.length}
            <small>in this workspace</small>
          </strong>
        </div>
        <div>
          <span>
            <Radio size={16} /> Delivering
          </span>
          <strong>
            {loading
              ? "—"
              : releases.filter((r) =>
                  ["active", "rolling_out"].includes(r.status),
                ).length}
            <small>active or rolling out</small>
          </strong>
        </div>
        <div>
          <span>
            <Pause size={16} /> Paused
          </span>
          <strong>
            {loading
              ? "—"
              : releases.filter((r) => r.status === "paused").length}
            <small>new offers stopped</small>
          </strong>
        </div>
        <div className="baseline-stat">
          <ShieldCheck size={22} />
          <div>
            <b>Native baseline</b>
            <p>Fallback stays in the APK.</p>
          </div>
        </div>
      </div>
      <div className="release-workspace">
        <div className="release-list-card">
          <div className="list-heading">
            <h2>
              Releases <span>{visible.length}</span>
            </h2>
            <a href={`${docs}/getting-started/quickstart.md`}>
              Publish with the CLI <ArrowUpRight size={14} />
            </a>
          </div>
          <div className="list-filters">
            <div className="search-field">
              <Search size={16} />
              <Input
                aria-label="Search releases"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search releases…"
              />
            </div>
            <select
              aria-label="Application"
              value={app}
              onChange={(e) => {
                setApp(e.target.value);
                setEnvironment("all");
              }}
            >
              <option value="all">All apps</option>
              {apps.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
            <select
              aria-label="Environment"
              value={environment}
              onChange={(e) => setEnvironment(e.target.value)}
            >
              <option value="all">All environments</option>
              {environments
                .filter((e) => app === "all" || e.app_id === app)
                .map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.name} · {apps.find((a) => a.id === e.app_id)?.name}
                  </option>
                ))}
            </select>
          </div>
          {error && (
            <div className="error-box" role="alert">
              {error}
            </div>
          )}
          {notice && (
            <div className="notice-box" role="status">
              {notice}
              <button aria-label="Dismiss notice" onClick={() => setNotice("")}>
                <X size={14} />
              </button>
            </div>
          )}
          {loading ? (
            <div className="empty-state">
              <Loader2 className="spin" />
              <p>Loading releases…</p>
            </div>
          ) : visible.length === 0 ? (
            <div className="empty-state">
              <Box />
              <h3>
                {releases.length
                  ? "No matching releases"
                  : "Your first update starts here"}
              </h3>
              <p>
                {releases.length
                  ? "Try a different search or environment."
                  : "Publish a supported source change with the Dootah CLI. Your releases will appear here."}
              </p>
              <a href={`${docs}/getting-started/quickstart.md`}>
                Open the quickstart <ArrowUpRight size={14} />
              </a>
            </div>
          ) : (
            <div className="release-table-wrap">
              <table className="release-table">
                <thead>
                  <tr>
                    <th>Release</th>
                    <th>Status</th>
                    <th>Rollout</th>
                    <th>Created</th>
                    <th>
                      <span className="sr-only">Details</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((r) => (
                    <tr
                      key={r.id}
                      className={selected?.id === r.id ? "row-selected" : ""}
                    >
                      <td>
                        <button
                          className="release-name"
                          disabled={busy}
                          onClick={() => void detail(r.id)}
                        >
                          <span className="release-icon">
                            <Box size={19} />
                          </span>
                          <span>
                            <b>{r.revision}</b>
                            <small>
                              {apps.find((a) => a.id === r.app_id)?.name ??
                                r.app_id}{" "}
                              <span> / </span>{" "}
                              {envFor(r)?.name ?? r.environment_id}
                            </small>
                          </span>
                        </button>
                      </td>
                      <td data-label="Status">
                        <Status status={r.status} />
                      </td>
                      <td data-label="Rollout">
                        <div className="rollout-cell">
                          <span>{r.percentage}%</span>
                          <div className="progress-track">
                            <span style={{ width: `${r.percentage}%` }} />
                          </div>
                        </div>
                      </td>
                      <td className="date-cell" data-label="Created">
                        {date(r.created_at)}
                      </td>
                      <td>
                        <Button
                          variant="ghost"
                          size="icon"
                          aria-label={`View ${r.revision}`}
                          disabled={busy}
                          onClick={() => void detail(r.id)}
                        >
                          <ChevronRight size={16} />
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="table-footer">
            <span>
              <ShieldCheck size={14} /> Signed delivery · Installed capability
              checks
            </span>
            <span>{demo ? "Sample data" : "Authenticated Cloud API"}</span>
          </div>
        </div>
        {!selected && !detailLoading && (
          <div className="console-tip">
            <span className="tip-icon">
              <Radio size={22} />
            </span>
            <div>
              <h3>Start small. Expand with evidence.</h3>
              <p>
                Open a release to adjust its rollout, review adoption or request
                rollback. Pause stops new offers; it does not revoke updates
                already cached on devices.
              </p>
            </div>
            <a href={`${docs}/concepts/delivery.md`}>
              Delivery behavior <ArrowUpRight size={14} />
            </a>
          </div>
        )}
        {detailLoading && !selected && (
          <div className="empty-state" role="status">
            <Loader2 className="spin" /> Loading release detail…
          </div>
        )}
        {selected && (
          <section className="release-detail" aria-label="Release detail">
            <div className="detail-heading">
              <div>
                <div className="eyebrow">
                  RELEASE DETAIL <span>· VERSION {selected.version}</span>
                </div>
                <h2>{selected.revision}</h2>
                <div className="detail-meta">
                  <Status status={selected.status} />
                  <span>
                    {envFor(selected)?.name ?? selected.environment_id}
                  </span>
                  <code>{selected.runtime}</code>
                </div>
              </div>
              <Button
                variant="ghost"
                size="icon"
                aria-label="Close release detail"
                disabled={busy}
                onClick={() => {
                  detailSequence.current++;
                  setDetailLoading(false);
                  setSelected(null);
                }}
              >
                <X size={18} />
              </Button>
            </div>
            {selected.operation && (
              <div
                className={`operation-banner ${selected.operation.status === "failed" ? "error-box" : ""}`}
                role="status"
              >
                <Clock3 size={16} />
                <span>
                  Cloud operation:{" "}
                  <b>
                    {selected.operation.action} · {selected.operation.status}
                  </b>
                  {pending(selected)
                    ? " — checking every 4 seconds. Devices still need a future check and launch."
                    : ""}
                </span>
                {detailLoading && <Loader2 size={14} className="spin" />}
              </div>
            )}
            <div className="detail-columns">
              <div>
                <div className="detail-section-title">
                  <h3>Rollout audience</h3>
                  <span>Compatible installations</span>
                </div>
                <div className="rollout-number">
                  {selected.percentage}
                  <span>%</span>
                  <Badge variant="outline">
                    {selected.status === "paused"
                      ? "New offers paused"
                      : "Configured audience"}
                  </Badge>
                </div>
                <div className="progress-track large">
                  <span style={{ width: `${selected.percentage}%` }} />
                </div>
                <div className="rollout-controls">
                  <Label htmlFor="rollout">Rollout percentage</Label>
                  <select
                    id="rollout"
                    value={rollout}
                    onChange={(e) => setRollout(Number(e.target.value))}
                    disabled={
                      busy ||
                      detailLoading ||
                      !controlRole ||
                      !canControl(selected, "rollout", head)
                    }
                  >
                    {Array.from(
                      new Set([0, 1, 10, 25, 50, 100, selected.percentage]),
                    )
                      .sort((a, b) => a - b)
                      .map((n) => (
                        <option key={n} value={n}>
                          {n}%
                        </option>
                      ))}
                  </select>
                  <Button
                    disabled={
                      busy ||
                      detailLoading ||
                      rollout === selected.percentage ||
                      !controlRole ||
                      !canControl(selected, "rollout", head)
                    }
                    onClick={() => openAction("rollout")}
                  >
                    Save rollout <ArrowRight size={14} />
                  </Button>
                </div>
                <p className="muted-note">
                  An eligible installation may receive an offer on a future
                  check. This percentage is not a measure of adoption.
                </p>
              </div>
              <div>
                <div className="detail-section-title">
                  <h3>Device signals</h3>
                  <span>Last {selected.metrics?.windowDays ?? 30} days</span>
                </div>
                <div className="signal-grid">
                  <div>
                    <span>Adoption</span>
                    <strong>
                      {percent(selected.metrics?.adoptionPercent)}
                    </strong>
                    <small>
                      {selected.metrics
                        ? `${selected.metrics.adopted} of ${selected.metrics.eligible} eligible`
                        : "No report available"}
                    </small>
                  </div>
                  <div>
                    <span>Healthy activations</span>
                    <strong>{percent(selected.metrics?.healthPercent)}</strong>
                    <small>
                      {selected.metrics
                        ? `${selected.metrics.healthy} of ${selected.metrics.activated} activated`
                        : "No report available"}
                    </small>
                  </div>
                </div>
                <p className="muted-note">
                  {demo
                    ? "Illustrative sample reports."
                    : "Authenticated device reports."}{" "}
                  Health means an acknowledged native frame, not a crash-free
                  metric.
                  {selected.metrics
                    ? ` As of ${new Date(selected.metrics.asOf).toLocaleString()}.`
                    : ""}
                </p>
              </div>
            </div>
            <div className="release-actions">
              <div>
                <Button
                  variant="outline"
                  disabled={
                    busy ||
                    detailLoading ||
                    !controlRole ||
                    !canControl(
                      selected,
                      selected.status === "paused" ? "resume" : "pause",
                      head,
                    )
                  }
                  onClick={() =>
                    openAction(
                      selected.status === "paused" ? "resume" : "pause",
                    )
                  }
                >
                  {selected.status === "paused" ? (
                    <Play size={15} />
                  ) : (
                    <Pause size={15} />
                  )}{" "}
                  {selected.status === "paused"
                    ? "Resume release"
                    : "Pause release"}
                </Button>
                <span>
                  {!controlRole
                    ? "Your role has read-only access."
                    : pending(selected)
                      ? "Controls wait for the current operation."
                      : "Pause stops new offers only."}
                </span>
              </div>
              <div>
                <Button
                  variant="outline"
                  className="danger-outline"
                  disabled={
                    busy ||
                    detailLoading ||
                    !controlRole ||
                    !canControl(selected, "rollback", head)
                  }
                  onClick={() => openAction("rollback")}
                >
                  <RotateCcw size={15} /> Roll back to native
                </Button>
                <Button
                  variant="ghost"
                  className="danger-text"
                  disabled={
                    busy ||
                    detailLoading ||
                    !controlRole ||
                    !canControl(selected, "kill", head)
                  }
                  onClick={() => openAction("kill")}
                >
                  Kill release
                </Button>
              </div>
            </div>
            <p className="rollback-note">
              <TriangleAlert size={14} /> Rollback and kill target the current
              environment/runtime and restore the embedded baseline on a future
              check and launch. Offline and running code are not instantly
              revoked.
            </p>
          </section>
        )}
      </div>
      <footer className="console-footer">
        <span>Native Android. Controlled updates.</span>
        <a href={`${docs}/reference/support.md`}>
          Alpha support boundaries <ArrowUpRight size={13} />
        </a>
      </footer>
      <Dialog
        open={!!dialog}
        onOpenChange={(open) => {
          if (!open && !busy && !retry) setDialog(null);
        }}
      >
        <DialogContent showCloseButton={!busy && !retry}>
          <DialogHeader>
            <DialogTitle>{dialog ? actionNames[dialog] : ""}</DialogTitle>
            <DialogDescription>
              {selected?.revision} · {selected ? envFor(selected)?.name : ""} ·{" "}
              {selected?.runtime}
            </DialogDescription>
          </DialogHeader>
          {dialog === "rollout" ? (
            <p>
              Change the configured rollout from <b>{selected?.percentage}%</b>{" "}
              to <b>{rollout}%</b> of compatible installations. Devices receive
              offers on future checks.
            </p>
          ) : dialog === "pause" ? (
            <p>
              Stop new offers for this release. Cached updates and running code
              remain available on devices.
            </p>
          ) : dialog === "resume" ? (
            <p>
              Resume offers for this release at its configured{" "}
              <b>{selected?.percentage}%</b> rollout.
            </p>
          ) : (
            <>
              <div className="rollback-dialog-warning">
                <TriangleAlert size={20} />
                <p>
                  This requests signed recovery to the embedded native baseline
                  for the entire environment/runtime. It does not select an
                  older OTA release or instantly revoke offline updates.
                </p>
              </div>
              <Label htmlFor="reason">
                Reason for{" "}
                {dialog === "kill" ? "killing this release" : "rollback"}
              </Label>
              <Input
                id="reason"
                maxLength={256}
                value={reason}
                disabled={busy || retry}
                onChange={(e) => setReason(e.target.value)}
                placeholder="Describe why recovery is needed"
              />
            </>
          )}
          {demo && (
            <p className="muted-note">
              Demo only. This action changes sample data in this browser.
            </p>
          )}
          {dialogError && (
            <div className="error-box" role="alert">
              {dialogError}
              {retry && (
                <p>
                  Keep this dialog open and retry. The original request key and
                  version will be reused.
                </p>
              )}
            </div>
          )}
          <DialogFooter>
            <Button
              variant="outline"
              disabled={busy || retry}
              onClick={() => setDialog(null)}
            >
              Cancel
            </Button>
            <Button
              variant={
                dialog === "rollback" || dialog === "kill"
                  ? "destructive"
                  : "default"
              }
              disabled={
                busy ||
                (!retry &&
                  ["rollback", "kill"].includes(dialog ?? "") &&
                  !reason.trim())
              }
              onClick={() => void submitAction()}
            >
              {busy ? <Loader2 size={15} className="spin" /> : null}
              {retry
                ? "Retry same request"
                : demo
                  ? "Confirm demo action"
                  : "Confirm action"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </main>
  );
}
