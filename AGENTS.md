# Dootah — contributor and agent guidance

This file is for anyone, human or automated, changing this repository. It adds working
rules to [CONTRIBUTING](CONTRIBUTING.md), which holds the environment, layout and
commands.

## Start here

1. Take the scope and acceptance criteria from the task you were given.
2. Read the [public docs](docs/README.md), the [support boundary](docs/reference/support.md)
   and the [architecture overview](docs/concepts/architecture.md).
3. For security-sensitive areas, read the [security model](docs/security/README.md).
4. `docs/v2/` holds dated engineering records. Use them for provenance, not as current
   instructions; see [history](docs/history/README.md).
5. Prefer targeted searches. Do not scan `docs/v2/evidence/`, Git history or the whole
   repository unless the task needs it.

## Product goal

Dootah brings CodePush / Expo Updates-style OTA updates to native Android apps written
in Kotlin and Jetpack Compose.

> If the installed APK already contains the native capability, component, resource or
> runtime support a change needs, a compatible ordinary Kotlin/Compose source change
> should be publishable OTA without rebuilding or reinstalling the APK.

Dootah does not aim to run arbitrary native code or to cover all of Kotlin/Compose.
A new APK is expected for new native code or capabilities, native libraries,
permissions, manifest changes or native binary changes. These are not Dootah failures.

## Principles

- Keep the normal Kotlin/Compose developer experience. No per-screen annotations.
- No app-specific hacks or exceptions.
- Do not widen the system into arbitrary JVM/native execution. Remote logic must not
  reach unrestricted Android APIs, `Context`, JVM objects or reflection.
- Native Android/Compose keeps native rendering and native capabilities.
- Fail closed when the analyzer, contract, bundle or runtime disagree, and keep the
  original native implementation as the fallback.
- Prefer the smallest generic change that meets the goal. Do not add architecture for
  its own sake or chase example coverage as a goal.
- Do not change Runtime ABI, Logic ABI, the PortableProgram grammar, update signing or
  the sandbox boundary unless the task explicitly calls for it.

## OTA proof standard

Unit tests, generated output and successful builds support a claim but do not prove
OTA. Real OTA proof means: install the APK, confirm the baseline, make an ordinary source
change, publish it without rebuilding or reinstalling, verify it on a device or
emulator, and check fallback or rollback where relevant. If a device test cannot
honestly be run, say so.

## Scope

- Stay within the task. Do not reopen completed work without concrete evidence that
  the product contract is violated.
- Before extending analyzer or runtime support, ask whether the installed APK already
  contains everything the change needs. If not, requiring a new APK may be correct.
- Stop when the task's acceptance criteria pass. Do not start the next milestone.
- If a requested approach conflicts with a proven invariant, stop and explain.

## Testing

- Add focused regression tests for generic behavior, including failure paths for
  security, update and persistence changes.
- Run the relevant module checks first, then the broader gates in
  [CONTRIBUTING](CONTRIBUTING.md#required-acceptance). Report hosted CI separately from
  local runs.
- Never weaken a safety check to make a test or example pass.

## Repository hygiene

- Keep changes focused. Do not modify unrelated files.
- Never commit secrets, private keys, credentials, operator state, raw acceptance logs
  or local configuration. Inspect every staged diff; `.gitignore` is not a scanner.
- Stage explicit paths. Do not stage unreviewed untracked files.
- Do not add AI or tool attribution (such as `Co-authored-by` or `Generated-by`
  trailers) to commits or pull requests.
- Do not rewrite Git history unless explicitly asked.
- Do not commit temporary edits to external example or corpus applications.
- Do not spawn subagents unless explicitly asked.
