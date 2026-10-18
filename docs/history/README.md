# Archived versions

Current integration instructions are in the [documentation index](../README.md).
Development diaries and raw acceptance evidence are not maintained public documentation.

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
