# Engineering history and preserved evidence

[Current product documentation](../README.md) is the starting point for users.
This page is for provenance and repository maintenance. Earlier records contain
superseded claims, failed intermediate designs, SDK versions and machine paths.
Do not apply their setup commands to the current alpha.

## V2 records

[docs/v2](../v2/) retains the development record in its original location so evidence
links stay stable. In particular:

- [Real-app acceptance](../v2/REAL_APP_ACCEPTANCE.md) and [matrix](../v2/REAL_APP_MATRIX.md)
- [Packaging](../v2/PHASE9B_RELEASE.md), [CLI/onboarding](../v2/PHASE9C_ONBOARDING.md),
  [deployment](../v2/PHASE9D_DEPLOYMENT.md), [CI](../v2/PHASE9E_CI.md),
  [public documentation](../v2/PHASE9F_DOCS.md)
- [Security investigation and replacement](../v2/SECURITY_MODEL.md),
  [portable logic development](../v2/PORTABLE_LOGIC.md),
  [SDK development](../v2/ANDROID_SDK.md)

Current public security, support and integration guides supersede instructional
claims in these records. Personal home-directory paths were generalized; temporary
scratch paths remain as provenance, not public setup examples.
Curated evidence bytes are not rewritten.
Raw logs, credentials, retained customer source/classes, dumps and private state
must remain outside Git. Add only reviewed sanitized receipts explicitly; see
[the evidence policy](../development/README.md#evidence-policy).

<a id="v1-preservation-and-deferred-cleanup"></a>

## V1 archive

Dootah V1 was a Kotlin compiler-plugin approach with `dootah-*` Gradle modules, a root
Gradle build, the `Android-Dootah` demo app and the `compiler-compatibility.yml`
workflow. It is **not supported** and has been removed from this tree. V1 also used the
`dev.dootah` plugin ID and group; its artifacts and versions are not compatible with the
current alpha. Its final committed state is preserved in this repository:

| Ref | Points to |
| --- | --- |
| Tag `archive/dootah-v1-final` (annotated) | V1 commit `5784cb4402f3ae09cb0c9adf5534f74ec588deb0` |
| Branch `archive/dootah-v1-pre-v2` | the same commit, with its full history |

```sh
git fetch origin tag archive/dootah-v1-final
git switch --detach archive/dootah-v1-final
```

Do not use V1 code, versions or documentation with the current alpha.
