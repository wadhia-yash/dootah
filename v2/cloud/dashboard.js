let csrf = "",
  org = "",
  page = "Apps";
const $ = (id) => document.getElementById(id),
  text = (tag, value) => {
    const n = document.createElement(tag);
    n.textContent = value;
    return n;
  };
async function api(path, method = "GET", body) {
  const r = await fetch("/v1/" + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      "Dootah-Organization": org,
      "X-CSRF-Token": csrf,
      "Idempotency-Key": crypto.randomUUID(),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const result = await r.json();
  if (!r.ok) throw Error(result.error.message);
  return result;
}
const button = (label, action) => {
  const b = text("button", label);
  b.onclick = () =>
    Promise.resolve(action()).catch(
      (e) => ($("message").textContent = e.message),
    );
  return b;
};
function form(fields, submit) {
  const f = document.createElement("form");
  for (const name of fields) {
    const label = text("label", name),
      input = document.createElement("input");
    input.name = name;
    input.required = true;
    label.append(input);
    f.append(label);
  }
  f.append(text("button", "Create"));
  f.onsubmit = async (e) => {
    e.preventDefault();
    try {
      await submit(Object.fromEntries(new FormData(f)));
    } catch (e) {
      $("message").textContent = e.message;
    }
  };
  return f;
}
async function detail(id) {
  const r = await api("releases/" + id);
  $("title").textContent = "Release detail";
  $("content").replaceChildren(
    text("pre", JSON.stringify(r, null, 2)),
    text(
      "p",
      "Pause stops new offers. Rollback/Kill restores embedded native behavior for this environment/runtime on a future check and launch. Offline and running code is not instantly revoked.",
    ),
  );
  for (const action of ["pause", "resume", "rollback", "kill"])
    $("content").append(
      button(action, async () => {
        if (
          ["rollback", "kill"].includes(action) &&
          !confirm("Restore embedded behavior for this environment/runtime?")
        )
          return;
        await api(`releases/${id}/${action}`, "POST", {
          version: r.version,
          reason: action,
        });
        await detail(id);
      }),
    );
  const select = document.createElement("select");
  for (const p of [0, 1, 10, 25, 50, 100]) {
    const o = text("option", `${p}%`);
    o.value = p;
    select.append(o);
  }
  select.value = r.percentage;
  $("content").append(
    select,
    button("Set rollout", async () => {
      await api(`releases/${id}/rollout`, "POST", {
        version: r.version,
        percentage: Number(select.value),
      });
      await detail(id);
    }),
  );
}
async function load() {
  $("message").textContent = "";
  $("title").textContent = page;
  const c = $("content");
  c.replaceChildren();
  const route = {
    Apps: "apps",
    Releases: "releases",
    Installations: "installations",
    "Telemetry/health": "events",
    Team: "members",
    "API tokens": "tokens",
    Audit: "audit",
  }[page];
  const result = await api(route);
  if (page === "Apps")
    c.append(
      form(["name"], async (b) => {
        await api("apps", "POST", b);
        await load();
      }),
    );
  if (page === "Team")
    c.append(
      form(["email", "role"], async (b) => {
        await api("members", "POST", b);
        await load();
      }),
    );
  if (page === "API tokens")
    c.append(
      form(["appId", "environmentId", "scopes", "expiresDays"], async (b) => {
        const token = await api("tokens", "POST", {
          ...b,
          scopes: b.scopes.split(","),
          expiresDays: Number(b.expiresDays),
        });
        c.prepend(
          text("pre", "Save this token now; shown once:\n" + token.secret),
        );
      }),
    );
  if (page === "Telemetry/health")
    c.append(
      text(
        "p",
        "Authenticated SDK reports. Health means acknowledged native frame, not crash-free. Open release detail for 30-day adoption and health denominators.",
      ),
    );
  for (const row of result.items) {
    const item = document.createElement("article");
    if (page === "Releases")
      item.append(
        button(`${row.revision} — ${row.status}`, () => detail(row.id)),
      );
    else if (page === "Apps")
      item.append(
        button(row.name, async () => {
          const env = await api(`environments?appId=${row.id}`);
          c.replaceChildren(
            text("h3", row.name),
            text("pre", JSON.stringify(env.items, null, 2)),
          );
          const releases = await api(`releases?appId=${row.id}`);
          for (const r of releases.items)
            c.append(button(`${r.revision} — ${r.status}`, () => detail(r.id)));
        }),
      );
    else item.append(text("pre", JSON.stringify(row, null, 2)));
    if (page === "API tokens")
      item.append(
        button("Revoke", async () => {
          await api(`tokens/${row.id}`, "DELETE");
          await load();
        }),
      );
    c.append(item);
  }
  if (result.nextCursor)
    c.append(
      button("Next page", async () => {
        const next = await api(`${route}?after=${result.nextCursor}`);
        c.replaceChildren(text("pre", JSON.stringify(next, null, 2)));
      }),
    );
}
$("login").onsubmit = async (e) => {
  e.preventDefault();
  try {
    const result = await api(
      "sessions",
      "POST",
      Object.fromEntries(new FormData(e.target)),
    );
    csrf = result.csrf;
    const list = await api("organizations");
    $("organizations").replaceChildren();
    for (const o of list.items) {
      const option = text("option", o.name);
      option.value = o.id;
      $("organizations").append(option);
    }
    org = list.items[0]?.id ?? "";
    $("login").hidden = true;
    $("workspace").hidden = false;
    await load();
  } catch (e) {
    $("message").textContent = e.message;
  }
};
$("organizations").onchange = async () => {
  org = $("organizations").value;
  await load();
};
for (const name of [
  "Apps",
  "Releases",
  "Installations",
  "Telemetry/health",
  "Team",
  "API tokens",
  "Audit",
])
  $("nav").append(
    button(name, async () => {
      page = name;
      await load();
    }),
  );
