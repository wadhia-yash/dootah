import { readFile, mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";
import { publish } from "../server/publish.mjs";
import { loadBindings } from "./config.mjs";
import { requireValue } from "./core.mjs";
export async function validate(artifact, runtime, contract) {
  requireValue(Buffer.byteLength(JSON.stringify(artifact)) <= 65536);
  await new Promise((resolve, reject) => {
    const child = spawn(
      process.env.DOOTAH_JAVA ?? "java",
      ["-cp", process.env.DOOTAH_VALIDATOR_CLASSPATH, "Validate"],
      { stdio: ["pipe", "ignore", "ignore"] },
    );
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(Error("validator timeout"));
    }, 5000);
    child.on("error", reject);
    child.on("exit", (code) => {
      clearTimeout(timer);
      code === 0
        ? resolve()
        : reject(
            Object.assign(Error("artifact_validation_failed"), { status: 422 }),
          );
    });
    child.stdin.on("error", () => {});
    child.stdin.end(JSON.stringify({ artifact, runtime, contract }));
  });
}
export async function binding(app) {
  const config = loadBindings();
  requireValue(config[app], "delivery_not_provisioned", 409);
  requireValue(
    Object.values(config).filter((item) => item.appId === config[app].appId)
      .length === 1,
    "delivery_binding_must_be_app_exclusive",
    409,
  );
  return config[app];
}
export async function internal(app, path, options = {}) {
  const c = await binding(app);
  const response = await fetch(`${c.base}/${c.appId}${path}`, {
    ...options,
    redirect: "error",
    signal: AbortSignal.timeout(20000),
    headers: { Authorization: `Bearer ${c.apiKey}`, ...options.headers },
  });
  requireValue(response.ok, "delivery_operation_failed", 502);
  return response;
}
export async function prepare(app, branch) {
  await internal(app, `/cloudChannel/${branch}`, { method: "POST" });
}
export async function upload(release) {
  const c = await binding(release.app_id),
    root = await mkdtemp(join(tmpdir(), "dootah-cloud-"));
  try {
    await writeFile(join(root, "program.json"), release.artifact_bytes);
    await writeFile(
      join(root, "metadata.json"),
      JSON.stringify({
        version: 0,
        bundler: "dootah",
        fileMetadata: { android: { bundle: "program.json", assets: [] } },
      }),
    );
    await writeFile(
      join(root, "expoConfig.json"),
      JSON.stringify({ runtimeVersion: release.runtime }),
    );
    return await publish(c.base, { ...c, branch: release.branch }, root, {
      publicOrigin: c.publicOrigin ?? c.base,
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}
export async function rollback(release, branch) {
  return (
    await internal(
      release.app_id,
      `/rollback/${branch}?${new URLSearchParams({ runtimeVersion: release.runtime, platform: "android" })}`,
      { method: "POST" },
    )
  ).json();
}
export async function manifest(app, branch, runtime, headers = {}) {
  const c = await binding(app);
  return fetch(`${c.base}/manifest`, {
    redirect: "error",
    signal: AbortSignal.timeout(15000),
    headers: {
      ...headers,
      "expo-app-id": c.appId,
      "expo-channel-name": branch,
      "expo-runtime-version": runtime,
      "expo-platform": "android",
      "expo-protocol-version": "1",
    },
  });
}
export async function inspect(app, branch, runtime) {
  const response = await manifest(app, branch, runtime, {
    "expo-embedded-update-id": "00000000-0000-4000-8000-000000000000",
    "expo-expect-signature": 'sig, keyid="main", alg="rsa-v1_5-sha256"',
  });
  if (response.status === 204 || response.status === 404) return null;
  requireValue(response.ok, "delivery_inspect_failed", 502);
  const text = await response.text(),
    boundary = /boundary="?([^";]+)/i.exec(
      response.headers.get("content-type"),
    )?.[1];
  requireValue(boundary, "signed_delivery_required", 502);
  const part = text
    .split("--" + boundary)
    .find((s) => /name="(manifest|directive)"/.test(s));
  requireValue(part, "signed_delivery_required", 502);
  const at = part.indexOf("\r\n\r\n"),
    body = part.slice(at + 4, -2),
    sig = /expo-signature:.*?sig="([^"]+)"/i.exec(part.slice(0, at))?.[1];
  const { verify, X509Certificate } = await import("node:crypto"),
    c = await binding(app);
  requireValue(
    sig &&
      verify(
        "RSA-SHA256",
        Buffer.from(body),
        new X509Certificate(c.certificate).publicKey,
        Buffer.from(sig, "base64"),
      ),
    "delivery_signature_invalid",
    502,
  );
  const parsed = JSON.parse(body);
  return parsed.type === "noUpdateAvailable" ? null : parsed;
}
