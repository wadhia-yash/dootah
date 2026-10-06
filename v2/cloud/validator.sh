#!/usr/bin/env bash
set -euo pipefail
cloud_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$cloud_dir/../.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
"$repo_dir/v2/publishing/gradlew" -p "$repo_dir/v2/publishing" installDist
mkdir -p "$cloud_dir/build/validator"
"$JAVA_HOME/bin/javac" -cp "$repo_dir/v2/publishing/build/install/dootah-publishing/lib/*" \
 -d "$cloud_dir/build/validator" "$cloud_dir/Validate.java" \
 "$repo_dir/v2/portable/src/main/java/dev/dootah/portable/PortableProgram.java"
printf 'Validator built in %s/build/validator\n' "$cloud_dir"
