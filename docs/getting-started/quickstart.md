# Quickstart: native Kotlin to first OTA

This is the authoritative V2 alpha quickstart. It uses public SDK candidate
**0.1.0-alpha.1**, Runtime ABI **2**, Logic ABI **1** and the BasicText renderer.
Read the [platform limits](../reference/platform.md) before changing an existing app.
All shell commands below use POSIX syntax; replace quoted placeholders with your own
paths/IDs. Keep credentials and retained release inputs outside source control.

## Prerequisites and tooling

Use JDK 21, Android SDK 36, AGP 8.9.1+, minSdk 29 and arm64-v8a. The concrete build
snippets use Gradle 9.3.1, AGP 8.12.0 and Kotlin 2.1.20. Use a compatible Compose-enabled
Android application with its Gradle wrapper; the legacy repository-root wrapper is
not the V2 build. A physical arm64 device with a supported WebView sandbox provider
is needed to establish OTA behavior, not merely a successful build.

Set `JAVA_HOME` to your JDK 21 installation and `ANDROID_HOME` to your Android SDK.
Put their executables on PATH. Source-building the SDK additionally needs Node 24
(CI uses 24.20.0), Python 3.9+, SDK build-tools 36.0.0, NDK 27.1.12297006 and CMake 3.22.1.
The consumer itself needs no Node/npm project. The separate publishing CLI uses
JDK 21 and Node 24.

From the Dootah checkout, build the CLI once:

```sh
export DOOTAH_REPO="$PWD"
"$DOOTAH_REPO/v2/publishing/gradlew" -p "$DOOTAH_REPO/v2/publishing" test installDist
export PATH="$DOOTAH_REPO/v2/publishing/build/install/dootah-publishing/bin:$PATH"
dootah --help
```

You can copy the entire `build/install/dootah-publishing` directory elsewhere; keep
its `lib`, `tooling` and `bin` together. The executable then needs no Dootah checkout
or Android workspace. See the [CLI reference](../../v2/publishing/ONBOARDING.md).

## Current pre-publication setup

For **ALPHA / CLEAN-ROOM VALIDATION USING NGROK**, complete the
[ngrok live HTTPS preflight](../self-hosting/ngrok.md) **before staging or building
the Android app**. Obtain the final URL first and retain it throughout the trial.

**No public Maven repository availability is claimed.** Build a new public-version
stage from this checkout or obtain an intact reviewed stage from your operator.
Do not point consumers at a historical stage whose plugin predates release retention.

```sh
export DOOTAH_STAGE="$HOME/dootah-work/sdk-stage-alpha-1"
mkdir -p "$(dirname "$DOOTAH_STAGE")"
"$DOOTAH_REPO/v2/android-sdk/release/stage.sh" "$DOOTAH_STAGE"
python3 "$DOOTAH_REPO/v2/android-sdk/release/validate.py" "$DOOTAH_STAGE"
export DOOTAH_MAVEN_REPOSITORY="$DOOTAH_STAGE/repository"
```

The stage directory must not already exist. This builds local unsigned Maven files;
it uploads nothing and does not use `mavenLocal()`. Keep the complete stage/checksums.
See [artifact staging](../../v2/android-sdk/release/README.md).

## Create the Cloud app and obtain its certificate

Cloud must already be running over HTTPS with your user provisioned. If you operate
it yourself, follow [self-hosting](../self-hosting/README.md). No publicly operated
Dootah Cloud endpoint is supplied. The operator provisions a separate xprem delivery
identity/binding for each app after you create its Cloud record.

For an ngrok trial, keep the [ngrok guide's](../self-hosting/ngrok.md)
`DOOTAH_CLOUD_URL` and private paths instead of replacing them with the placeholders
below. Android uses normal platform HTTPS trust; the OTA update-signing certificate
is still required separately. The earlier private-CA path has an unresolved TLS
preflight failure and is not the current clean-room acceptance route.

```sh
export DOOTAH_CLOUD_URL='https://updates.example.com'
export DOOTAH_PRIVATE="$HOME/.dootah-alpha/my-app"
umask 077
mkdir -p "$DOOTAH_PRIVATE/publisher" "$DOOTAH_PRIVATE/native-release"
# Have your secret manager write the login password to this private, mode-0600 file.
dootah login --email 'you@example.com' --password-file "$DOOTAH_PRIVATE/password" --session-file "$DOOTAH_PRIVATE/session"
dootah org list --session-file "$DOOTAH_PRIVATE/session"
dootah org create --name 'My organization' --session-file "$DOOTAH_PRIVATE/session"
export DOOTAH_ORGANIZATION='RETURNED_ORGANIZATION_ID'
dootah app create --name 'My Android app' --session-file "$DOOTAH_PRIVATE/session"
export DOOTAH_APP_ID='RETURNED_APP_ID'
```

Use an existing authorized organization if appropriate. Give the app ID and desired
channel (`development` here) to the operator. Obtain only the **public update
certificate** from that app's binding; save it as `app/update-certificate.pem` in your
Android project. Never embed the signing private key or publisher credentials.

## Dependency, plugin and Application setup

The following snippets use Groovy Gradle files. Merge with your app's existing
Android setup. Use the environment variable above for both repository scopes in
`settings.gradle`; exclusive resolution prevents mixing staged and stale Dootah files:

```groovy
pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(System.getenv('DOOTAH_MAVEN_REPOSITORY')) } }
            filter { includeGroupByRegex('dev[.]dootah([.].*)?') }
        }
        google(); mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(System.getenv('DOOTAH_MAVEN_REPOSITORY')) } }
            filter { includeGroupByRegex('dev[.]dootah([.].*)?') }
        }
        google(); mavenCentral()
    }
}
rootProject.name = 'MyDootahApp'
include ':app'
```

In `app/build.gradle` (the plugin supplies its matching `runtime-v2` dependency):

```groovy
plugins {
    id 'com.android.application' version '8.12.0'
    id 'org.jetbrains.kotlin.android' version '2.1.20'
    id 'org.jetbrains.kotlin.plugin.compose' version '2.1.20'
    id 'dev.dootah' version '0.1.0-alpha.1'
}
android {
    namespace 'example.dootah'
    compileSdk 36
    defaultConfig {
        applicationId 'example.dootah'
        minSdk 29
        targetSdk 36
        versionCode 1
        versionName '1.0'
        ndk { abiFilters 'arm64-v8a' }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
    buildFeatures { compose true }
}
dootah {
    appId = System.getenv('DOOTAH_APP_ID')
    updateUrl = System.getenv('DOOTAH_CLOUD_URL') + '/manifest'
    channel = 'development'
    runtimeVersion = 'my-app-native-1'
    publicCertificate = file('update-certificate.pem')
    requireHooks = true
}
dependencies {
    implementation 'androidx.activity:activity-compose:1.10.1'
    implementation 'androidx.compose.foundation:foundation:1.7.8'
    implementation 'androidx.compose.material3:material3:1.3.1'
}
```

`runtimeVersion` identifies your installed native compatibility baseline. Do not
change it for this OTA. If your app uses ABI splits, limit them to arm64; retention
requires exactly one APK per variant. Keep release signing in your normal private
Android build configuration.

Create `MyApplication.kt` in your app source package, or change your existing
Application superclass while preserving its initialization and `super` calls:

```kotlin
package example.dootah

import dev.dootah.runtime.DootahApplication

class MyApplication : DootahApplication()
```

Register it in `app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name=".MyApplication" android:label="Dootah example">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

An ordinary `ComponentActivity` works. For a minimal light-background example:

```kotlin
package example.dootah

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import dev.dootah.runtime.DootahApplication

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                Button(onClick = {
                    val input = android.widget.EditText(this@MainActivity)
                    input.inputType = 129 // Password field; development ticket entry only.
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Development enrollment ticket").setView(input)
                        .setPositiveButton("Enroll") { _, _ ->
                            (application as DootahApplication).enrollTelemetry(input.text.toString()) { ok ->
                                runOnUiThread {
                                    android.widget.Toast.makeText(this@MainActivity,
                                        if (ok) "Enrolled" else "Request a fresh ticket", 0).show()
                                }
                            }
                        }.setNegativeButton("Cancel", null).show()
                }) { Text("Enroll development install") }
                CheckoutScreen(100)
            }
        }
    }
}
```

Copy [baseline CheckoutScreen.kt](../../v2/examples/basic-text/baseline/CheckoutScreen.kt)
into `app/src/main/java/example/dootah/CheckoutScreen.kt` **before building**. Keep its
imports in place. The native layout/background and enrollment button are not changed by this OTA.
The ticket-entry UI is for a controlled development install only; replace it with
an authenticated backend flow for production, before building that native baseline.

## Native build, retain and install

From your Android project root:

```sh
export DOOTAH_APP_ROOT="$PWD"
./gradlew :app:dootahRetainDebugRelease
cp -R app/build/outputs/dootah/debug/retained/. "$DOOTAH_PRIVATE/native-release/"
adb install "$DOOTAH_PRIVATE/native-release/accepted.apk"
```

Retention builds the APK and saves its source snapshot, instrumented pre-R8 classes,
hooks, capabilities and hashes. The directory is private intellectual property;
archive it before any subsequent native build. For a release variant use
`:app:dootahRetainReleaseRelease` with your normal release signing. Import refuses
zero installed identities; `requireHooks` catches zero at build time.

Open the installed app and verify **Discount: 10**. Record its APK hash/install time.
This baseline check requires a device; a build alone is not OTA proof.

## Create the environment and register the contract

```sh
dootah release import --record "$DOOTAH_PRIVATE/native-release/release.json"   --source-root "$DOOTAH_APP_ROOT/app" --config "$DOOTAH_PRIVATE/publisher/publish.json"
dootah env create --config "$DOOTAH_PRIVATE/publisher/publish.json" --session-file "$DOOTAH_PRIVATE/session"
dootah contract register --config "$DOOTAH_PRIVATE/publisher/publish.json" --session-file "$DOOTAH_PRIVATE/session"
dootah env list --app "$DOOTAH_APP_ID" --session-file "$DOOTAH_PRIVATE/session"
export DOOTAH_ENVIRONMENT_ID='RETURNED_ENVIRONMENT_ID'
dootah token create --app "$DOOTAH_APP_ID" --environment "$DOOTAH_ENVIRONMENT_ID"   --scopes app:read,release:read,release:publish,release:control --days 7   --out "$DOOTAH_PRIVATE/publisher.token" --session-file "$DOOTAH_PRIVATE/session"
```

Environment creation and contract registration are the **same immutable operation**.
The second command verifies the existing matching registration idempotently. The CLI
derives parameter types, excludes uninstalled/R8-removed functions and uploads only
capabilities/function contracts, not your source-bearing private baseline.

Enroll this controlled development install using the native button included above:

```sh
dootah enrollment ticket --environment "$DOOTAH_ENVIRONMENT_ID" \
  --token-file "$DOOTAH_PRIVATE/publisher.token" --out "$DOOTAH_PRIVATE/one-use.ticket"
```

Transfer the ticket privately to the test device and enter it in **Enroll development
install** within ten minutes. The ticket is single-use; a failed attempt may require
a fresh ticket and a new output filename. Confirm the native enrollment result, then
remove the private ticket file. Do not log, commit or share tickets as evidence.

For production, integrate [native enrollment](../../v2/publishing/ONBOARDING.md#telemetry-enrollment)
with your authenticated backend instead of this manual UI. The backend issues a fresh
ticket to native code calling `application.enrollTelemetry(ticket) { ... }` and handles
renewal of the 30-day installation credential. Enrollment must not block startup.
Never package the publisher token. No production authentication backend is implemented
by this minimal example.

## Edit supported Kotlin and analyze

In `CheckoutScreen.kt`, change only the local helper's `10` to `20`, as shown in the
[OTA version](../../v2/examples/basic-text/ota/CheckoutScreen.kt). Do not change imports,
signature, file set, call site or `runtimeVersion`. Do not rebuild, reinstall, rerun
retention or replace the imported baseline.

```sh
dootah analyze "$DOOTAH_PRIVATE/publisher/publish.json"
```

Expect the changed CheckoutScreen to be portable. Any unsupported changed function
refuses the whole publication; resolve it within the [supported subset](../reference/support.md)
or ship a new native APK.

## Publish, roll out and verify

```sh
dootah publish "$DOOTAH_PRIVATE/publisher/publish.json" --token-file "$DOOTAH_PRIVATE/publisher.token"   --revision 'YOUR_SOURCE_REVISION' --key 'YOUR_STABLE_PUBLICATION_REQUEST_ID'
export DOOTAH_OPERATION_ID='RETURNED_OPERATION_ID'
export DOOTAH_RELEASE_ID='RETURNED_RELEASE_ID'
dootah operation inspect --id "$DOOTAH_OPERATION_ID" --token-file "$DOOTAH_PRIVATE/publisher.token"
dootah release inspect --id "$DOOTAH_RELEASE_ID" --token-file "$DOOTAH_PRIVATE/publisher.token"
# Wait for operation succeeded and release ready. Read its current version.
export DOOTAH_RELEASE_VERSION='CURRENT_RELEASE_VERSION'
dootah rollout --id "$DOOTAH_RELEASE_ID" --version "$DOOTAH_RELEASE_VERSION" --percentage 100   --token-file "$DOOTAH_PRIVATE/publisher.token"
```

Use 100% only for this controlled development environment; choose a deliberate
percentage for real users. Keep the same request key when retrying an ambiguous
publication. It does not automatically enable rollout.

Launch the enrolled app online to allow its update check/download, then restart and
verify **Discount: 20**. Confirm the APK hash/install time did not change. Restart
offline and check the cached update. Observe native frame/health acknowledgement;
health is not crash-free analytics. On failure, check registration, runtime/channel,
enrollment expiry, sandbox availability and [limitations](../reference/platform.md).
Do not count a local analysis or server receipt as device proof.

## Roll back

Inspect again and use the new current version for every mutation:

```sh
dootah release inspect --id "$DOOTAH_RELEASE_ID" --token-file "$DOOTAH_PRIVATE/publisher.token"
export DOOTAH_RELEASE_VERSION='LATEST_RELEASE_VERSION'
dootah rollback --id "$DOOTAH_RELEASE_ID" --version "$DOOTAH_RELEASE_VERSION"   --key 'YOUR_STABLE_ROLLBACK_REQUEST_ID' --token-file "$DOOTAH_PRIVATE/publisher.token"
# Inspect the returned rollback operation ID until it succeeds.
dootah operation inspect --id 'RETURNED_ROLLBACK_OPERATION_ID' --token-file "$DOOTAH_PRIVATE/publisher.token"
```

Allow an online check, restart and verify **Discount: 10** from the embedded/native
baseline. Then verify offline restart. Rollback is signed and asynchronous; offline
clients cannot receive it immediately. `pause` stops offers without removing cached
updates; see [delivery controls](../concepts/delivery.md).

## Post-publication expected setup

Only after an authorized public release identifies the actual registry, replace the
local exclusive Maven blocks with that registry in **both** plugin and dependency
repository scopes. If Maven Central is chosen, use `mavenCentral()` in both plus
`google()` for Android dependencies. If Artifactory is chosen, use its announced URL
and access policy. Keep `id 'dev.dootah' version '0.1.0-alpha.1'` only if that exact
version is released. No Plugin Portal listing, Central/JFrog availability or upload
is implied by these prospective instructions. The integration and retained-release
workflow otherwise stays the same.
