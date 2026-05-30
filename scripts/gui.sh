#!/usr/bin/env bash
#
# Launch the Swing disassembler GUI. Uses the JDK source launcher, so there is
# no separate compile step — `java` runs DisasmGui.java directly.
#
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# Prefer the project's GraalVM JDK 21 if present; otherwise whatever `java` is
# on PATH (needs JDK 11+ for the single-file source launcher).
JAVA_BIN="java"
if [[ -x "$HOME/.jdks/graalvm-jdk-21/bin/java" ]]; then
  JAVA_BIN="$HOME/.jdks/graalvm-jdk-21/bin/java"
fi

exec "$JAVA_BIN" "-Ddisasm.root=$ROOT_DIR" "$ROOT_DIR/DisasmGui.java" "$ROOT_DIR"
