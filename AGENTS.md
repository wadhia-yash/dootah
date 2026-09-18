# Dootah — Agent Instructions

## Start Here

For substantial Dootah work:

1. Read `DOOTAH_CURRENT.md` for the current project state and active milestone.
2. Read `DOOTAH_HANDOFF.md` only when deeper architectural or historical context is needed.
3. Treat the repository and these documents as the source of truth.
4. Do not scan `coverage/*.json`, git history, or the entire repository unless the task genuinely requires it.
5. Prefer targeted searches and only open files relevant to the current task.

---

## Product Goal

Dootah aims to provide a CodePush / Expo Updates-style OTA experience for native Android/Kotlin/Jetpack Compose applications.

The core rule is:

> If the installed APK already contains the native capability, component, adapter, builder, resource support, or runtime support required for a change, compatible normal Kotlin/Compose source changes should be publishable OTA without rebuilding or reinstalling the APK.

Dootah does NOT aim to support arbitrary native code execution or 100% of Kotlin/Compose OTA.

A new APK is expected when a change genuinely requires something not present in the installed application, such as:

- new native code or capability
- new native library
- new Android permission
- manifest changes
- native binary changes

Do not treat these cases as Dootah failures.

---

## Development Principles

- Prefer normal Kotlin/Compose developer experience.
- Do not require per-screen annotations as the normal workflow.
- Do not introduce app-specific hacks.
- Do not widen the system into arbitrary JVM/native execution.
- Remote JavaScript must not receive unrestricted Android APIs, `Context`, JVM objects, or reflection access.
- Keep native Android/Compose responsible for native rendering and native capabilities.
- Fail closed when compiler, contract, bundle, or runtime disagree.
- Preserve the original/native implementation as a safe boundary where required.
- Prefer the smallest generic solution that satisfies the product goal.
- Do not add architecture merely for architectural purity.
- Do not chase corpus coverage as a goal by itself.

---

## OTA Proof Standard

Do not claim a feature is OTA-proven from unit tests, generated JavaScript, compiler output, or successful builds alone.

Real OTA proof means:

1. Build and install the APK.
2. Confirm the baseline application.
3. Make the normal Kotlin/Compose source change.
4. Run the Dootah bundle/publish flow.
5. Do NOT rebuild the APK.
6. Do NOT reinstall the APK.
7. Verify the change on a real device/emulator.
8. Verify fallback/failure behavior when relevant.

Tests and compiler checks support this evidence but do not replace it.

Prefer real open-source applications from the existing corpus over synthetic Dootah demos.

---

## Scope Discipline

Follow the active milestone defined in `DOOTAH_CURRENT.md`.

Do not reopen a completed milestone unless there is concrete evidence that the product contract is violated.

Do not turn every unsupported corpus construct into roadmap work.

Before expanding compiler/runtime support, ask:

> Does the installed APK already contain everything required for this meaningful application change?

If no, requiring a new APK may be correct.

If yes and Dootah cannot perform the expected OTA change, investigate the smallest generic missing capability.

Stop when the current milestone's acceptance criteria pass.

Do not automatically begin the next milestone.

---

## Testing

For implementation changes:

- Add focused regression tests for generic behavior.
- Run relevant module tests first.
- Run the broader Dootah test suite before declaring completion.
- Run `dootahRealComposeCheck` when compiler/Compose behavior is affected.
- Preserve FIR/IR/compiler/runtime agreement where applicable.
- Test failure paths for security, update, and persistence changes.
- Never weaken an existing safety check merely to make a test or corpus screen pass.

---

## Repository Hygiene

- Keep changes focused on the requested milestone.
- Do not modify unrelated files.
- Do not rewrite working architecture without evidence.
- Do not commit secrets, private keys, credentials, or local configuration.
- Do not add `Co-authored-by: Claude`, `Co-authored-by: Codex`, `Generated-by`, or similar AI attribution to commits.
- Do not rewrite git history unless explicitly requested.
- Do not commit temporary acceptance-test edits to external corpus applications unless explicitly requested.

---

## Agent Behavior

- Do not spawn subagents unless explicitly requested.
- Avoid broad repository scans when targeted inspection is sufficient.
- Do not repeatedly reread large context files during the same task.
- If the requested approach conflicts with a proven architectural invariant, stop and explain the conflict before changing it.
- If a real-device test cannot honestly be performed, say so rather than claiming success from automated tests.
- When a milestone passes its defined acceptance criteria, report the result and stop.