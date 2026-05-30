#!/usr/bin/env bash
#
# disasm.sh — print the real machine code of a target, for a chosen CPU arch,
# from ALREADY-COMPILED classes. You compile (javac) yourself; this tool only
# runs the JIT and disassembles.
#
# Usage:
#   scripts/disasm.sh <arch> <classes-dir> <main-class> <target>
#
#   <arch>         arm64 | x86_64
#   <classes-dir>  a directory that is the CLASSPATH ROOT of your .class files
#                  (i.e. package dirs live directly under it)
#   <main-class>   the class whose main() runs and EXERCISES the target
#                  (the JIT only compiles code that actually runs)
#   <target>       what to disassemble:
#                    FinalFieldHolder::<init>         a single constructor
#                    FinalFieldExperiment::testMethod a single method
#                    FinalFieldHolder::*              EVERY method of a class
#
# "Whole class" works because HotSpot's CompileCommand accepts a '*' wildcard in
# the method position. Caveat: only methods that actually RUN get compiled.
#
# How it works: runs `java` inside a GraalVM container of the requested arch
# (arm64 is emulated via qemu on x86 hosts), with your classes dir as the
# classpath. The JIT is pinned for DETERMINISM and told to compile + print only
# the target. hsdis turns the compiled bytes into text.
#
# Output: the full transcript is written to logs/disasm-<ts>-<arch>.log (the path
# is printed on a "###LOG### <path>" line). A "###RESULT### OK|FAIL" line states
# whether the target compiled. The disassembly is also streamed to stdout.
#
set -euo pipefail

ARCH="${1:-}"; CLASSES_DIR="${2:-}"; MAIN_CLASS="${3:-}"; TARGET="${4:-}"
if [[ -z "$ARCH" || -z "$CLASSES_DIR" || -z "$MAIN_CLASS" || -z "$TARGET" ]]; then
  echo "usage: $0 <arm64|x86_64> <classes-dir> <main-class> <Class::method | Class::*>" >&2
  exit 2
fi
if [[ ! -d "$CLASSES_DIR" ]]; then
  echo "classes-dir not found: $CLASSES_DIR" >&2; exit 2
fi
CLASSES_DIR="$(cd "$CLASSES_DIR" && pwd)"   # absolute, for the bind mount

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="ghcr.io/graalvm/native-image-community:21"
HSDIS_DIR="$ROOT_DIR/tools/hsdis"

# --- arch -> podman arch, hsdis filename, hsdis download URL ---
case "$ARCH" in
  arm64|aarch64)
    ARCH=arm64;  PODMAN_ARCH=arm64
    HSDIS_SO=hsdis-aarch64.so
    HSDIS_URL="https://chriswhocodes.com/hsdis/hsdis-aarch64.so"
    HSDIS_FILE_TAG="aarch64" ;;
  x86_64|amd64)
    ARCH=x86_64; PODMAN_ARCH=amd64
    HSDIS_SO=hsdis-amd64.so
    HSDIS_URL="https://chriswhocodes.com/hsdis/hsdis-amd64.so"
    HSDIS_FILE_TAG="x86-64" ;;
  *) echo "unknown arch: $ARCH (use arm64 or x86_64)" >&2; exit 2 ;;
esac

LOG_DIR="$ROOT_DIR/logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/disasm-$(date +%Y%m%d-%H%M%S)-$ARCH.log"
echo "###LOG### $LOG"   # consumed by the GUI; also handy on the CLI

# --- ensure the right hsdis is present (download + verify if missing) ---
HSDIS_PATH="$HSDIS_DIR/$HSDIS_SO"
if [[ ! -f "$HSDIS_PATH" ]]; then
  mkdir -p "$HSDIS_DIR"
  curl -sL --max-time 120 -o "$HSDIS_PATH" "$HSDIS_URL" || true
  if ! file "$HSDIS_PATH" 2>/dev/null | grep -q "$HSDIS_FILE_TAG"; then
    rm -f "$HSDIS_PATH"
    echo "ERROR: could not fetch a valid $HSDIS_SO" | tee "$LOG" >&2
    echo "###RESULT### FAIL"
    exit 1
  fi
fi

# Everything inside the braces is teed to the log. MAIN_CLASS / TARGET go via -e
# so '<init>', '::' and '*' need no quoting. Classes + hsdis are bind-mounted
# read-only (shared SELinux label 'z'); we never compile here.
{
  echo "## disasm: arch=$ARCH  classes=$CLASSES_DIR  main=$MAIN_CLASS  target=$TARGET ##"
  podman run --rm --arch "$PODMAN_ARCH" --entrypoint /bin/bash \
    -e MAIN_CLASS="$MAIN_CLASS" -e TARGET="$TARGET" \
    -v "$HSDIS_DIR":/hsdis:ro,z \
    -v "$CLASSES_DIR":/classes:ro,z \
    "$IMAGE" -c '
set -e
echo "[container] arch=$(uname -m)"
cp /hsdis/'"$HSDIS_SO"' "$JAVA_HOME/lib/server/" 2>/dev/null || true
cp /hsdis/'"$HSDIS_SO"' "$JAVA_HOME/lib/"        2>/dev/null || true

java \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+DebugNonSafepoints \
  -Xcomp -Xbatch \
  -XX:-TieredCompilation \
  "-XX:CompileCommand=compileonly,${TARGET}" \
  "-XX:CompileCommand=print,${TARGET}" \
  -cp /classes "${MAIN_CLASS}"
'
} 2>&1 | tee "$LOG"

# --- verdict (read from the log so it is authoritative) ---
echo
if grep -qiE "Compiled method|nmethod" "$LOG"; then
  echo "###RESULT### OK"
else
  echo "!! Target '$TARGET' was never compiled."
  echo "   The JIT only compiles methods that RUN. Make sure ${MAIN_CLASS}.main()"
  echo "   actually calls $TARGET (directly or transitively), the class is on the"
  echo "   classpath root you chose, and the spelling matches exactly"
  echo "   (constructors are '::<init>', whole class is '::*')."
  echo "###RESULT### FAIL"
fi
