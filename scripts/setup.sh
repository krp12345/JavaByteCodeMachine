#!/usr/bin/env bash
#
# setup.sh — one-time preflight for JavaByteCodeMachine.
#
# Does three things:
#   1. CHECKS host prerequisites (JDK 21, podman, curl, file, qemu binfmt) and
#      reports each as ✓ ok / ! warn / ✗ missing.
#   2. DOES the safe, unprivileged setup: makes scripts executable, pre-fetches +
#      verifies both hsdis plugins, and (unless --skip-image) pre-pulls the
#      container image so your first disassembly isn't slow.
#   3. HANDS YOU (prints, never runs) any command that needs sudo — e.g. installing
#      qemu-user-static. This script NEVER runs sudo itself.
#
# Exit code: 0 if all REQUIRED prerequisites are present, 1 otherwise (so it also
# works as a CI / readiness check). Missing arm64 emulation is a warning, not a
# failure (it's only needed for arm64 targets).
#
# Usage:  scripts/setup.sh [--skip-image]
#
set -uo pipefail   # deliberately NOT -e: we want to run every check and report all

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="ghcr.io/graalvm/native-image-community:21"
HSDIS_DIR="$ROOT_DIR/tools/hsdis"
HSDIS_BASE="https://chriswhocodes.com/hsdis"
GRAALVM_JDK="$HOME/.jdks/graalvm-jdk-21"

SKIP_IMAGE=0
for a in "$@"; do
  case "$a" in
    --skip-image) SKIP_IMAGE=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $a (try --help)" >&2; exit 2 ;;
  esac
done

# --- pretty output (degrades gracefully without a TTY/colors) ---
if [[ -t 1 ]] && command -v tput >/dev/null 2>&1 && [[ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]]; then
  G=$(tput setaf 2); Y=$(tput setaf 3); R=$(tput setaf 1); B=$(tput bold); N=$(tput sgr0)
else G=""; Y=""; R=""; B=""; N=""; fi
FAILS=0; WARNS=0
ok()   { echo "  ${G}✓${N} $*"; }
warn() { echo "  ${Y}!${N} $*"; WARNS=$((WARNS+1)); }
miss() { echo "  ${R}✗${N} $*"; FAILS=$((FAILS+1)); }
info() { echo "  ${B}…${N} $*"; }
hand() { echo "      ${B}run:${N} $*"; }

echo "${B}JavaByteCodeMachine — setup${N}"

# ====================================================================
# 1. CHECKS
# ====================================================================
echo
echo "${B}Checking prerequisites${N}"

# OS / arch
OS="$(uname -s)"; MACH="$(uname -m)"
if [[ "$OS" == "Linux" && "$MACH" == "x86_64" ]]; then ok "host: Linux x86_64"
else warn "host is $OS/$MACH — only Linux x86_64 is tested"; fi

# Java (prefer the JDK gui.sh prefers)
JAVA_BIN="java"
[[ -x "$GRAALVM_JDK/bin/java" ]] && JAVA_BIN="$GRAALVM_JDK/bin/java"
if command -v "$JAVA_BIN" >/dev/null 2>&1 || [[ -x "$JAVA_BIN" ]]; then
  JV="$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/{print $2; exit}')"
  MAJOR="${JV%%.*}"; [[ "$MAJOR" == "1" ]] && MAJOR="$(echo "$JV" | cut -d. -f2)"
  if [[ "$MAJOR" == "21" ]]; then ok "JDK $JV  ($JAVA_BIN)"
  elif [[ -n "$MAJOR" && "$MAJOR" -gt 21 ]] 2>/dev/null; then
    warn "JDK $JV is newer than 21 — it can emit classes the JDK 21 engine can't load"
    hand "use JDK 21, or we pin 'javac --release 21' (see INTERNALS §2.6)"
  elif [[ -n "$MAJOR" && "$MAJOR" -ge 14 ]] 2>/dev/null; then
    warn "JDK $JV works to build the GUI, but the project targets JDK 21"
  else
    miss "JDK $JV is too old — the GUI needs JDK ≥ 14 (project targets 21)"
    hand "install GraalVM 21: https://www.graalvm.org/downloads/"
  fi
else
  miss "no 'java' found"
  hand "install GraalVM 21 (https://www.graalvm.org/downloads/) or Temurin 21"
fi

# javac (needed to compile your source)
if command -v javac >/dev/null 2>&1 || [[ -x "$GRAALVM_JDK/bin/javac" ]]; then ok "javac present"
else miss "no 'javac' — needed to compile your source (install a full JDK, not just a JRE)"; fi

# podman
if command -v podman >/dev/null 2>&1; then ok "podman present  ($(podman --version 2>/dev/null))"
else
  miss "podman not found — runs the JIT/disassembler container"
  hand "Fedora/RHEL: sudo dnf install podman   ·   Debian/Ubuntu: sudo apt install podman"
fi

# curl + file (for hsdis fetch/verify)
command -v curl >/dev/null 2>&1 && ok "curl present" || { miss "curl not found"; hand "sudo dnf install curl  (or apt)"; }
command -v file >/dev/null 2>&1 && ok "file present" || { miss "file not found"; hand "sudo dnf install file  (or apt)"; }

# qemu aarch64 binfmt (only needed for arm64 targets) — a WARNING, not a failure
if ls /proc/sys/fs/binfmt_misc/ 2>/dev/null | grep -qi aarch64; then
  ok "arm64 emulation registered (qemu-aarch64 binfmt)"
else
  warn "no arm64 emulation — needed only for arm64 targets (x86_64 works without it)"
  hand "Fedora/RHEL: sudo dnf install qemu-user-static   ·   Debian/Ubuntu: sudo apt install qemu-user-static binfmt-support"
fi

# ====================================================================
# 2. DO the safe, unprivileged setup
# ====================================================================
echo
echo "${B}Preparing the project${N}"

# make scripts executable
chmod +x "$ROOT_DIR"/scripts/*.sh 2>/dev/null && ok "scripts are executable"

# pre-fetch + verify both hsdis plugins
fetch_hsdis() {
  local so="$1" tag="$2" path="$HSDIS_DIR/$1"
  if [[ -f "$path" ]] && file "$path" 2>/dev/null | grep -q "$tag"; then ok "hsdis present: $so"; return; fi
  command -v curl >/dev/null 2>&1 || { warn "skipping $so (no curl)"; return; }
  mkdir -p "$HSDIS_DIR"
  info "downloading $so …"
  curl -sL --max-time 120 -o "$path" "$HSDIS_BASE/$so" || true
  if file "$path" 2>/dev/null | grep -q "$tag"; then ok "fetched + verified: $so"
  else
    rm -f "$path"
    warn "could not fetch a valid $so"
    hand "curl -L -o tools/hsdis/$so $HSDIS_BASE/$so   (then: file tools/hsdis/$so)"
  fi
}
fetch_hsdis hsdis-amd64.so   "x86-64"
fetch_hsdis hsdis-aarch64.so "aarch64"

# pre-pull the container image (large, one-time) unless skipped
if [[ "$SKIP_IMAGE" == 1 ]]; then
  info "skipping image pull (--skip-image); first run will pull $IMAGE"
elif command -v podman >/dev/null 2>&1; then
  if podman image exists "$IMAGE" 2>/dev/null; then ok "container image present: $IMAGE"
  else
    info "pulling container image (large, one-time): $IMAGE"
    if podman pull "$IMAGE"; then ok "pulled $IMAGE"
    else warn "image pull failed — it will be pulled on first disassembly"; fi
  fi
else
  info "podman missing — image will be pulled on first run once podman is installed"
fi

# ====================================================================
# 3. VERDICT
# ====================================================================
echo
if [[ "$FAILS" -eq 0 ]]; then
  echo "${G}${B}Ready.${N} ${WARNS} warning(s). Launch the GUI:  ./scripts/gui.sh"
  exit 0
else
  echo "${R}${B}Not ready:${N} ${FAILS} required item(s) missing, ${WARNS} warning(s). Fix the ✗ above and re-run."
  exit 1
fi
