# Startup and activation — local bundle first

Recorded for the acceptance that asked why a cold start showed a blank screen
for about thirty seconds, and why an app with an already-active bundle drew its
installed implementation first and swapped in the update afterwards — the two
toolbar buttons an update had removed appearing, and then being taken away in
front of the user.

IdeaMemo is the test application, not the subject. Nothing below is specific to
it, and nothing in Dootah was taught anything about it.

## What was measured

Instrumentation first, on the device, before any change. `DootahTrace` marks each
stage of startup against elapsed real time, and a `logcat` filtered to the Dootah
tag reads back as a timeline. The frame timeline is sampled on the device itself
— `screencap` piped to `md5sum` with nanosecond timestamps — so the measurement
does not compete over adb with the launch it is measuring.

The device is a vivo 1909, Android 12, mid-range, running a debug build with no
baseline profile.

### Cold start, active bundle, before

```
trace     0ms  initialize
trace    33ms  client built
trace   863ms  first intercepted composable: …router.App
trace  2151ms  active metadata read (took 27ms)
trace  2173ms  active artifact read (took 21ms)
trace 20090ms  sandbox creation (took 17853ms)
trace 20249ms  bundle evaluation (took 18075ms)
trace 20333ms  remote registry ready (took 18160ms)
trace 20689ms  first remote frame
```

and, in the same launch, **306 update checks in the first 34 seconds**, each a
full HTTPS round trip, serialised one behind another.

The app then died: `dumpsys activity exit-info` reported `reason=6 (ANR)` about
thirty seconds in, on every launch with an active bundle.

## Root causes

Four, all measured rather than reasoned about.

### 1. The sandbox connection waited on the host app's main thread — 17.9s of 20s

`JavaScriptSandbox.createConnectedInstanceAsync(context)` binds with
`Context.bindService(intent, connection, flags)`. That overload delivers
`onServiceConnected` on the application's **main thread**. Connecting to the
sandbox is therefore not merely asynchronous: it cannot finish until the host
app's main thread is free to run one more message — and Dootah starts the
sandbox during application startup, which is exactly the window in which a real
app saturates its main thread.

Requesting the bind took 9ms. The future took 17.9 seconds. The work behind it,
once it could begin, took 40.

Android has an overload that takes an executor. The sandbox does not use it, but
the `Context` it binds through is Dootah's to choose. `OffMainThreadBinding` is a
`ContextWrapper` whose `bindService` answers with the executor overload and hands
the callbacks to a thread of its own; everything else passes straight through, so
the same connection object is registered against the same underlying context and
unbinding is unchanged. Below API 29 there is no such overload and the wrapper is
transparent.

**Measured: 19,242ms → 217ms.**

### 2. Reading the update state verified up to eight signatures, on the main thread, per screen

The ANR trace named it exactly:

```
Ed25519Verify.verify
ManifestVerifier.verify
ImageUpdateStore.state
BundleStore.getInstalledBundleVersion
DootahClient.status
DootahScreenState.apply          ← on the main thread, per screen, per render
```

`ImageUpdateStore.state()` parses the persisted record and verifies the
publisher's signature over every proof in it — active, last known good,
candidate, each retained version. Every call. `Dootah.status()` calls it, and
every intercepted screen asks for the status when it finishes rendering, when it
is constructed, and again when it records the version it drew.

IdeaMemo's statistics page alone composes several hundred intercepted screens.
The app was verifying hundreds of Ed25519 signatures on its main thread during
its first composition, which is what the blank screen was, and what the system
eventually killed it for.

A file that has not changed cannot have a different state, so it is now read
once, keyed on the record's length and modification time so that a record
replaced by another process is still noticed. Writes clear the cache before
writing.

### 3. Every screen made its own update check

`LaunchedEffect(state, isFocused) { state.checkForUpdate() }` runs per screen,
which is correct — any screen could be the one a kill switch has to reach — but
the checks were serialised behind one lock, so a first composition's burst became
a queue that went on being answered long after the screens had drawn, and a list
re-made them as its rows scrolled into composition.

`CoalescedCheck` shares one answer: callers that overlap join the run in flight,
and a caller arriving within the quiet period is given the last answer.

**Measured on a fresh-install cold start: 306 checks → 1.**

### 4. Everything Dootah did ran on the caller's dispatcher, which is Compose's

Every entry point is called from composition, so the caller's dispatcher is the
main thread inside the frame callback — visible in the stack as
`AndroidUiDispatcher.performTrampolineDispatch` under `Choreographer.doFrame`.
Reading the bundle off disk, evaluating it and parsing the UI it returns were
competing with the frames they exist to produce. They now run on `Dispatchers
.Default`, and the local load is started at `initialize` rather than by the first
screen to compose.

## The stale first frame

Separate from the delay, and not fixed by it. An intercepted screen cannot decide
synchronously what to draw: rendering from the bundle is suspending work and
Compose cannot suspend a composition, so a screen's first composition necessarily
happens before Dootah has anything, and what it drew then was the implementation
that shipped in the APK. When an active bundle is on the device that
implementation is out of date by definition.

Two mechanisms, because one is not enough.

### A screen that is about to be replaced draws nothing

`hasRemoteImplementation` is now true not only when remote content is ready but
for the moment in which a bundle already on the device is expected to serve this
screen and has not said so yet. `DootahRemoteContent` has nothing to draw in that
moment, which is the point: the screen is blank rather than stale.

It ends the instant the answer arrives, in every direction — content ready, the
bundle does not implement this screen, the load failed, Dootah is not installed —
and it is time-boxed per screen, so blank is a moment and never an outcome. A
device with no active bundle never enters it at all.

This is what covers screens that compose late. The app's root composable, its
navigation host, the page, and the page's toolbar entered composition across
nearly two seconds, in waves, each wave only after the previous one's content was
set. No window-level mechanism can span that.

### The first frame of the process is held

`DootahFirstFrame` declines to pre-draw the first activity's content view while
the local load is running or a composed screen has not settled, which holds the
window on the launch theme — the ordinary appearance of an app that has not
finished starting. Installed only when a bundle is already on disk, on the first
activity only, and with a budget.

Three things about it were wrong before they were right, and each is recorded in
the code because each is invisible from the outside:

- **`onActivityCreated` is dispatched from inside `Activity.onCreate`** — the
  `super.onCreate` call at the top of the host app's own — so there is no content
  view yet, and asking an `AppCompatActivity` for one there builds its decor
  against a theme that is not ready. Dootah asking a question stopped IdeaMemo
  launching at all. It hooks `onActivityStarted`, and the whole callback is
  guarded: nothing about holding a frame is worth a crash in someone else's app.
- **Declining a pre-draw makes `ViewRootImpl` schedule another traversal, and
  scheduling a traversal puts a synchronisation barrier on the main looper.** A
  release posted with `postDelayed` for 2.5 seconds ran after 21, and the app was
  frozen for all of them — the hold had become the blank screen it exists to
  prevent. The budget is a Choreographer frame callback, which is posted past that
  barrier.
- **Readiness needs two frames running.** A screen whose remote content contains
  another intercepted screen registers that one only after its own content is
  set, so there is a moment when everything known has settled and the next wave
  has not signed up.

## Effect, on the device

Cold start, active bundle v2, **control-plane route removed** (`adb reverse
--remove`), three consecutive restarts:

| | before | after |
|---|---|---|
| sandbox creation | 17,853–19,242 ms | 150–173 ms |
| local bundle ready | ~20,300 ms | 492–519 ms |
| first visible app frame | ~34 s, or never (ANR) | 2.9–3.0 s |
| first frame released by | the hold expiring, at 21 s | the bundle being ready, at 2.3 s |
| update checks in the first 30 s | 176–306 | 0 offline, 1 online |
| main-thread CPU when idle | ~98%, indefinitely | 0% |
| process after 30 s | killed, `reason=6 (ANR)` | alive at 75 s |

Fresh install, no cached bundle, network up: `no active bundle on disk`, `first
frame not held: nothing local to draw`, native UI at 4.3 s, one background
update check at 3.6 s. Nothing waits for the network, and nothing is held.

## The state machine, as asked for

**A — local active bundle has priority.** `Dootah.initialize` reads the persisted
record and loads the artifact from local disk immediately, on a background
dispatcher, with no network involved. `prime()` returns without doing anything if
there is no bundle on disk. Measured: registry ready at ~500 ms, before the first
intercepted composable composes.

**B — no active bundle.** Nothing is held, nothing is loaded, the app's own UI
renders, and the update check runs in the background.

**C — candidate update.** Unchanged, and already correct: `stage` verifies the
hash and the signature and writes a candidate; `activateCandidate` /
`prepareForLoad` re-check the payload and the signed proof at the transition and
commit the marker before any JavaScript runs; `confirmHealthy` promotes to last
known good only after the remote content has rendered. Active is never replaced
until validation succeeds.

**D — failure.** Also unchanged and local: `failUnconfirmed` quarantines the
failed hash and falls back to the last known good, re-reading its bytes before
selecting it, and to the app's own implementation when there is none. No network
is consulted and no timeout is waited for.

## Regression coverage

`dootah-android/src/test/java/com/dootah/ota/CoalescedCheckTest.kt`

- callers that overlap share one run
- a caller inside the quiet period is given the last answer
- a caller after the quiet period runs the check again
- an answer that changes is not hidden by an earlier one

`dootah-android/src/test/java/com/dootah/ota/StateReadCostTest.kt`

- an unchanged record is verified once however often it is read
- the installed version is readable without verifying again
- a record this store changes is read again
- a record replaced underneath the store is not answered from a stale read

`dootah-android/src/test/java/com/dootah/FirstFrameGateTest.kt`

- holds while the local bundle is still loading
- holds while a composed screen has nothing to draw
- is ready once the bundle is loaded and every screen has settled
- a screen that registers in the next wave is waited for
- a screen composed after release is not waited for
- the gate opens once and cannot be re-armed
- settling a screen nobody waited for is harmless
- releasing while still loading stops the hold

## Files changed

Runtime (`dootah-android`):

- `DootahTrace.kt` (new) — the startup timeline
- `runtime/OffMainThreadBinding.kt` (new) — the sandbox binds off the main thread
- `FirstFrameGate.kt` (new) — when the first frame is worth waiting for
- `DootahFirstFrame.kt` (new) — the Android wiring for that
- `ota/CoalescedCheck.kt` (new) — one answer per quiet period
- `ota/ImageUpdateStore.kt` — the state read is cached against the record on disk
- `Dootah.kt` — background scope, priming at initialize, work off the caller's
  dispatcher, `willServe`, coalesced checks
- `ui/DootahInterception.kt` — a screen about to be replaced draws nothing,
  time-boxed; registration with the first-frame gate
- `DootahConfig.kt` — `firstFrameHoldMillis`, `updateCheckIntervalMillis`
- `runtime/JavaScriptRuntime.kt`, `runtime/BundleEngine.kt` — trace marks

Gradle plugin, found while publishing:

- `tasks/DootahPublishTask.kt`, `tasks/DootahBundleTask.kt`,
  `DootahBundleWiring.kt` — both tasks read `project` from their task action,
  which is unsupported once the configuration cache is in play, so a developer
  who had published once could not publish again until they cleared the cache.
  The project root is captured while configuring instead.

## Versions

`runtimeVersion` remains **9**. No wire or runtime protocol semantics changed:
the bundle format, the manifest, the signing and the renderer are untouched.
What changed is when Dootah does its work and on which thread.

`0.1.0-alpha.1` through `alpha.6` are untouched. `alpha.7` through `alpha.16`
were published in sequence as each measurement was taken; none was overwritten,
because a consumer's configuration cache may have captured any of them. All were
published to the isolated local test repository **without PGP signatures**: no
signing key is available in this environment. Artifact signing is unchanged and
nothing about it is claimed here.
