# Security policy

Dootah is **alpha software**. It has not had an independent security audit, and it
makes no guarantee of fitness for production. The trust model and its known limits
are in the [security model](docs/security/README.md).

## Supported versions

| Version | Status |
| --- | --- |
| **0.1.0-alpha.1** (Runtime ABI 2, Logic ABI 1) and current V2 source | Supported for security reports |
| Any earlier V2 development build | Not supported; reproduce on the current alpha |
| Historical V1 (compiler plugin / `dootah-*` Gradle modules) | Not supported |

Fixes ship in a later alpha release. Earlier alpha releases are not patched.

## Reporting a vulnerability

Report privately through **GitHub Private Vulnerability Reporting**:

1. Open the repository's **Security** tab.
2. Select **Report a vulnerability**.

GitHub documents the flow in
[Privately reporting a security vulnerability](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability).

**Do not disclose a vulnerability publicly before coordinated disclosure is agreed.**
That includes issues, pull requests, discussions, commit messages and social media.
If the private reporting form is unavailable, do not post details. Open a public issue
that asks for a private contact and contains no vulnerability details.

Include, where you can:

- the affected version or commit, and the component (Android SDK runtime, Gradle
  plugin, publisher CLI, Cloud, or the self-hosted deployment)
- the steps to reproduce, and the impact you observed
- whether update signing, the JavaScript sandbox, the capability boundary, tenant
  isolation or credentials are involved

Never include real credentials, private keys, end-user data, APKs or database dumps.
Use disposable test values.

## What to expect

Maintainers handle reports on a best-effort basis. There is **no guaranteed response
time or fix timeline**. We will coordinate the disclosure timing with you through the
private advisory and credit you if you wish.

## Security report or normal bug?

Report privately when a problem could let someone:

- get a device to accept an update that the configured signing identity did not sign,
  or bypass rollback or native fallback protection
- run code outside the accepted PortableProgram grammar or the JavaScript sandbox, or
  reach Android APIs beyond the declared capabilities
- read or change another tenant's apps, releases, telemetry or credentials in Cloud
- bypass authentication, authorization, CSRF or origin checks
- obtain signing keys, API keys, tokens, passwords or other secrets
- deny update delivery or logins to other clients beyond the documented rate limits

Use a normal public issue for:

- a compatible-looking source change that Dootah refuses or keeps native
- build, Gradle plugin or publisher failures, and crashes with no security impact
- documentation errors and feature requests

A change that needs a new APK is not a vulnerability. Examples are a new native
capability, permission, manifest entry or native library. The same applies to the
documented limits on rooted devices and compromised signers in the
[security model](docs/security/README.md#trust-assumptions-and-limits). See
[CONTRIBUTING](CONTRIBUTING.md) for normal contributions.
