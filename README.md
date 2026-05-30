# JavaByteCodeMachine

**A tool for studying how Java really works — your source code, the bytecode it
compiles to, and the real machine code the JIT emits — all aligned in one view.**

Pick any method. JavaByteCodeMachine compiles your source, JIT-compiles that method
for the CPU architecture you choose, and lines up the three layers for you:

```
your source line  ▸  the bytecode it became  ▸  the machine instructions the CPU runs
```

…in true execution order. Expand a line to descend through its layers, collapse it to
step back, switch between **arm64** and **x86_64**, and export the whole study as a
self-contained, browsable file.

It's built for learning and investigation: understanding the JVM and the JIT, seeing
what an optimization or a language feature *actually* costs in instructions, settling
"does this compile to what I think it does?" arguments, and teaching how high-level code
becomes silicon.

---

## Three layers, one view

Every row is one of your **source lines**. Fold it open and you see the **bytecode**
(`javac`'s output) that line compiled to, and under that the **real machine instructions**
the JIT generated — each with its true address, so nothing is reordered or faked. Layers
are collapsed by default, so you start with a clean read of your code and dig in only
where you're curious.

## See it in action: a CPU memory barrier conjured by one `final`

Here's the kind of thing you can discover. Java's final-field **"freeze"** — the rule
that makes a fully-built object with a `final` field safe to share across threads — is
invisible in your source and in the bytecode. On **x86** it's invisible in the machine
code too. Target **arm64**, though, and a real instruction appears: **`dmb ishst`**, a
hardware memory barrier.

```
┌ line 16   this.nonFinalField = 200;
│   bc  8  putfield nonFinalField
│   0x…7454  str w0,[x1,#16]      ;*putfield nonFinalField   ← ordinary store, NO barrier
┌ line 17   this.finalField = 100;
│   bc 14  putfield finalField
│   0x…745c  str w0,[x1,#12]      ;*putfield finalField
┌ line 18   }
│   bc 17  return
│   0x…7460  dmb ishst            ;*return                   ← THE FREEZE
```

The `final` field gets a barrier; the non-final one beside it doesn't. Same source, same
bytecode — **different silicon**. Switch the target to x86_64 and the barrier vanishes
to zero instructions. That's the sort of three-layer insight the tool is for.

---

## Features

- **Layered, collapsible study view.** Source only by default; click a line's right-side
  **▸** to reveal its bytecode and machine code.
- **2- or 3-level detail, on the fly.** `source ▸ bytecode ▸ machine` (fold each layer
  independently) or `source ▸ (bytecode + machine)`. Expand-all / collapse-all in a click.
- **Cross-architecture from one machine.** Disassemble for **arm64** or **x86_64** — no
  ARM hardware required.
- **Honest by construction.** Real machine-address order, never reordered; every address,
  bytecode index, and source line is the JDK's own — never guessed. (Details →
  [INTERNALS.md](INTERNALS.md).)
- **Self-contained HTML export.** One file, fully expanded yet still collapsible in any
  browser. No server.

---

## What you need to run it

JavaByteCodeMachine drives the **real JDK toolchain inside a container**, so it needs a
few things on the host. All of it is mainstream, and the last two rows are handled for
you automatically. To check everything at once, run **`./scripts/setup.sh`** (see
[Setup](#setup) below).

| Requirement | Why it's needed | Where / how to get it |
|---|---|---|
| **Linux on x86_64** | host platform the tool is built for | other hosts are untested |
| **JDK 21** (GraalVM build recommended) | runs the GUI; `javac`/`javap` compile and inspect your source | GraalVM: [graalvm.org/downloads](https://www.graalvm.org/downloads/) · or any JDK 21 (e.g. [Adoptium Temurin 21](https://adoptium.net/temurin/releases/?version=21)). **Keep the host at 21** — a newer host JDK can emit classes the JDK 21 engine can't load. `gui.sh` auto-uses `~/.jdks/graalvm-jdk-21` if present, else `java` on `PATH`. |
| **[podman](https://podman.io/)** (rootless) | runs the JIT/disassembler in a pinned GraalVM image | Fedora/RHEL: `sudo dnf install podman` · Debian/Ubuntu: `sudo apt install podman` · others: [podman.io/docs/installation](https://podman.io/docs/installation). **Docker is not required.** |
| **`qemu-user-static`** | emulates arm64 on an x86 host (binfmt) | **Only for arm64 targets** (x86_64 runs natively). Fedora/RHEL: `sudo dnf install qemu-user-static` · Debian/Ubuntu: `sudo apt install qemu-user-static binfmt-support`. Verify: `ls /proc/sys/fs/binfmt_misc/ \| grep aarch64`. |
| **`curl`** and **`file`** | fetch + verify the `hsdis` plugin on first use | Pre-installed on most distros; otherwise `sudo dnf install curl file` (or `apt`). |
| **A graphical display** (Wayland/X11) | the GUI is a Swing window | Already present on any desktop Linux. The CLI engine (`scripts/disasm.sh`) needs **no** display. |
| Container image + **hsdis** plugin | the JIT backend and the disassembler | **Automatic** — fetched on first run (needs internet once). See below. |

### Fetched for you on first run

You don't install these by hand:

- **Container image** `ghcr.io/graalvm/native-image-community:21` — pulled by `podman` the
  first time you disassemble (cached afterward).
- **hsdis disassembler plugin** — `scripts/disasm.sh` downloads the right `hsdis-<arch>.so`
  from [chriswhocodes.com/hsdis](https://chriswhocodes.com/hsdis/) into `tools/hsdis/` and
  verifies it with `file`. It runs **inside the container**, so you never install hsdis into
  your own JDK. If your network blocks the download, drop it in manually:
  ```bash
  curl -L -o tools/hsdis/hsdis-aarch64.so https://chriswhocodes.com/hsdis/hsdis-aarch64.so  # arm64
  curl -L -o tools/hsdis/hsdis-amd64.so   https://chriswhocodes.com/hsdis/hsdis-amd64.so    # x86_64
  file tools/hsdis/hsdis-*.so   # must report "aarch64" / "x86-64"
  ```

Also created on demand (and git-ignored): the transient `logs/` and `.classes/` folders.

---

## Setup

Run the preflight once — it checks every prerequisite above, makes the scripts
executable, pre-fetches + verifies both `hsdis` plugins, and pre-pulls the container
image so your first disassembly isn't slow:

```bash
./scripts/setup.sh                 # check + prepare everything
./scripts/setup.sh --skip-image    # same, but don't pre-pull the (large) image
```

It reports each item as ✓ / ! / ✗, and **never runs `sudo` itself** — if something needs
elevated install (e.g. `qemu-user-static`), it prints the exact command for you to run.
It exits non-zero when a required prerequisite is missing, so it doubles as a readiness
check. Setup is optional — the tool still fetches what it needs lazily on first run — but
it's the smoothest start.

---

## Running it

**GUI:**

```bash
./scripts/gui.sh
```

1. **Browse** to a folder of `.java` source (try the bundled **`examples/`** to start).
   It's compiled with debug info, and your classes/methods appear as a tree.
2. Pick a **Target** method and a **Run class** (one with `main()`) whose `main()`
   exercises that target.
3. Choose **arm64** or **x86_64**, click **Disassemble**, expand lines to explore, and
   **Export HTML…** to save a shareable report.

**CLI** (engine only, on already-compiled classes):

```bash
./scripts/disasm.sh arm64  .classes FinalFieldExperiment 'FinalFieldHolder::<init>'
./scripts/disasm.sh x86_64 .classes FinalFieldExperiment 'FinalFieldHolder::<init>'  # freeze vanishes
./scripts/disasm.sh arm64  .classes FinalFieldExperiment 'FinalFieldHolder::*'       # whole class
```

### The one rule worth knowing

The JIT only compiles code that actually **runs** — there's no "compile this method in
isolation" switch. So the run-class `main()` must call your target (directly or
indirectly); constructors run on `new`. If the tool reports a target was *"never
compiled,"* that's the cause.

---

## Learn more

The full design — the exact `javac`/`java` flags and why each is needed, how a machine
instruction is mapped back to *your* source line, the cross-architecture trick, and the
integrity rules that keep the trace honest — is in **[INTERNALS.md](INTERNALS.md)**.
