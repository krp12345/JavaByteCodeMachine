# JavaByteCodeMachine — Internals

How JavaByteCodeMachine works under the hood: the exact toolchain and flags, the
source↔instruction mapping, the cross-architecture disassembly, the integrity contract
that keeps the trace honest, the data/UI model, and the design decisions (and dead ends)
behind it. For installing and using the tool, see the [README](README.md).

---

## 1. Project layout

```
README.md                       client-facing overview
INTERNALS.md                    this file (the engineering detail)
.gitignore                      marks transient artifacts
examples/FinalFieldExperiment.java  a specimen to study (final + non-final field; main() exercises it)
DisasmGui.java              the Swing GUI (single-file; runs via the JDK source launcher) — the tool's own source
scripts/                        the tool's shell scripts — the tool's own source
scripts/setup.sh                preflight: check prerequisites + pre-fetch downloads
scripts/disasm.sh               engine: JIT-compile + disassemble ONE target in a container
scripts/gui.sh                  launch the GUI
tools/hsdis/                    hsdis-aarch64.so + hsdis-amd64.so (disassembler plugins)
```

Transient (git-ignored, recreated on demand): `logs/` (run transcripts) and `.classes/`
(the GUI's `javac -g` output). The last-used source folder is remembered in the OS-managed
per-user prefs store (`java.util.prefs`), not in the project tree.

---

## 2. The exact toolchain & flags

JavaByteCodeMachine never reimplements a compiler or a disassembler — it orchestrates the
**real JDK tools** and reads their output back. Three programs do the work: `javac`
(source → bytecode), `java` with the JIT + `hsdis` (bytecode → machine code), and `javap`
(to read bytecode/metadata for the view). Every flag below is load-bearing.

### 2.1 Compiling your source — `javac`

Run on the **host** JDK by the GUI (`DisasmGui.compile`):

```
javac -g -d .classes <every .java under your source folder>
```

| Flag | Why it's required |
|---|---|
| **`-g`** | Emit **full debug info**. Critical: it produces the `LineNumberTable` (`(start_pc, line)` pairs, JVMS §4.7.12) that is the *only* bridge from a bytecode index back to your source line. Without `-g` the whole source↔machine mapping collapses. |
| `-d .classes` | Write classes to the managed `.classes/` dir (the classpath root the engine bind-mounts). |

The `final` keyword survives compilation only as the `ACC_FINAL` flag plus the
`LineNumberTable` — there is no "barrier" in the bytecode; that's emitted later, by the JIT.

### 2.2 JIT-compiling + disassembling — `java` (the engine)

Run **inside the container** by `scripts/disasm.sh`:

```
java \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+DebugNonSafepoints \
  -Xcomp -Xbatch \
  -XX:-TieredCompilation \
  -XX:CompileCommand=compileonly,<target> \
  -XX:CompileCommand=print,<target> \
  -cp /classes <main-class>
```

| Flag | Why it's required |
|---|---|
| **`-XX:+UnlockDiagnosticVMOptions`** | Gate that unlocks the diagnostic options below (`DebugNonSafepoints`, the `print` CompileCommand path). Without it the JVM rejects them. |
| **`-XX:+DebugNonSafepoints`** | Make the JIT record debug info (`PcDesc`) at **many** PCs, not just safepoints → dense, line-accurate `; - Class::m@bci (line N)` annotations. Without it, line coverage is sparse and many instructions show no source. |
| **`-Xcomp`** | Compile **on first call** — no interpreter warmup, no profiling-driven variability. The target is JIT-compiled the moment it runs. |
| **`-Xbatch`** | Compile **synchronously** on the calling thread, so the disassembly is fully flushed before the JVM exits (otherwise a background compile can be cut off). |
| **`-XX:-TieredCompilation`** | Use a **single top-tier compiler** (Graal here), eliminating tier-to-tier output differences. Deterministic code. |
| **`-XX:CompileCommand=compileonly,<target>`** | Compile **only** the target method (`Class::method`, `Class::<init>`, or `Class::*` for every method of a class) — nothing else clutters the output. |
| **`-XX:CompileCommand=print,<target>`** | **Disassemble** the target via `hsdis` (this is the `PrintAssembly` path scoped to one target) and print the machine code + HotSpot's own `;*bytecode` / `{reloc}` annotations. |
| `-cp /classes <main-class>` | Classpath = your bind-mounted classes; run `<main-class>.main()` so the target actually executes and thus gets compiled. |

**Determinism is a hard requirement** — `-Xcomp -Xbatch -XX:-TieredCompilation` together
guarantee the same input yields the same machine code every run, with no warmup artifacts.

### 2.3 The disassembler plugin — `hsdis`

JIT code lives in memory (it's never an on-disk ELF), so HotSpot needs the
`hsdis-<arch>.so` plugin to turn compiled bytes into text at runtime. The engine copies
the matching plugin into the container JDK's `lib/server/` (and `lib/`) before launching
`java`. If the plugin is absent in `tools/hsdis/`, `disasm.sh` downloads it from
`chriswhocodes.com/hsdis` and verifies it with `file` before use:

| Arch | Plugin | `file` tag checked |
|---|---|---|
| arm64 | `hsdis-aarch64.so` | `aarch64` |
| x86_64 | `hsdis-amd64.so` | `x86-64` |

**All network access lives in `scripts/disasm.sh`** — this `curl` (conditional + verified)
and the implicit `podman` image pull (§5). Nothing in `gui.sh` or the GUI downloads
anything. `scripts/setup.sh` pre-fetches both plugins and the image up front (reusing the
same URLs + `file` verification) so the first real disassembly isn't slow, and prints any
`sudo` step rather than running it.

### 2.4 Reading bytecode & metadata — `javap`

The GUI shells out to `javap` (host JDK) for the non-machine layers, never parsing class
files itself:

| Invocation | Used for |
|---|---|
| `javap -p -classpath .classes <Class>` | enumerate methods/constructors; detect `main()`; read the `Compiled from "X.java"` line to locate the source file |
| `javap -c -p -classpath .classes <Class>` | the **bytecode text** for each `(method, bci)` shown in the trace |

### 2.5 Launching the GUI — the source launcher

`scripts/gui.sh` runs `java DisasmGui.java <root>` via the **single-file source
launcher** (no separate compile step). It prefers a project GraalVM JDK 21 if present,
else `java` on `PATH`.

### 2.6 Host ↔ container Java-version coupling

The GUI compiles your source on the **host** JDK, then the engine loads those classes
inside the **JDK 21** container. `compile()` targets the host's class-file version, so a
host JDK **newer than 21** would emit classes the container can't load
(`UnsupportedClassVersionError`). Keep the host at **JDK 21**, or pin `javac --release
21`. The GUI *source itself* needs **JDK ≥ 14** to compile (it uses a switch expression);
everything else it uses is JDK 11-era, and the source launcher needs JDK ≥ 11.

---

## 3. The integrity contract — why the trace never lies

Because the tool owns the whole chain `source → javac -g → bytecode → JIT`, the mapping
is exact, from JDK tools only. The view avoids the usual misleading shortcuts — a hard
design contract, preserved across every UI change:

- **Machine-address order is the spine — instructions are NEVER reordered.** A source
  line the JIT scheduled non-contiguously appears as *more than one block* (truthful),
  instead of being faked contiguous. The same holds one level down: a *bytecode* whose
  machine instructions the JIT split recurs as more than one node rather than being
  regrouped.
- **Real PCs and BCIs are always shown**, so the ordering/grouping is auditable.
- A block's bytecodes are **exactly the BCIs its instructions implement** (from HotSpot's
  `PcDesc`), not "all bytecode of the line."
- Instructions HotSpot leaves **unscoped** (safepoint poll, `ret`, GC/▸ barriers,
  deopt/exception stubs) go under a **`runtime (no source)`** header — never pinned to a
  fake line.
- Anything unresolved is labelled **"(unavailable)"**, not invented.
- The view shows **only** what `javac` / `javap` / `hsdis` emit — no editorializing.

---

## 4. How the source ↔ instruction mapping works

It's a **recorded metadata chain**, not inference. The **bytecode index (BCI)** is the
join key:

```
machine PC ──(JIT debug info / PcDesc, recorded for DEOPT)──▶ BCI ──(javac LineNumberTable)──▶ source line
```

- **Source ↔ BCI:** `javac -g`'s `LineNumberTable`; a BCI's line = the entry with the
  greatest `start_pc ≤ BCI`.
- **BCI ↔ machine PC:** the JIT attaches `PcDesc`/`ScopeDesc` (PC → method, **BCI**, JVM
  state) so it can **deoptimize**. We piggyback on this safety-critical metadata. hsdis
  prints it as `; - Class::method@bci (line N)`, **after** the instruction it describes,
  so an instruction takes the **following** annotation's `(method, bci, line)`.
- **`-XX:+DebugNonSafepoints`** (see §2.2) makes that coverage dense.
- **Inlining** is handled: a `ScopeDesc` is a *chain* (caller@bci → callee@bci), so one
  instruction can map into another method's source.

**The JVM cannot print your source *text*** — it isn't in the `.class` (only line numbers
are). The source text comes from the `.java` the tool just compiled; the line *number* is
the JDK's, authoritative.

**"Runtime" things with no source line** (own block): frame setup / stack banging,
safepoint polls, the nmethod entry barrier, inline-cache miss stubs, deopt/exception
stubs, GC write barriers, memory fences. Many are out-of-line with no BCI; we never force
them onto a source line.

### Line-attribution integrity bug (caught & fixed)

HotSpot prints the line annotation *after* its instruction, and trailing out-of-line code
is unscoped — so an instruction is attributed to the **following** annotation, with **no**
fallback to a preceding one. Unscoped code stays unattributed (`line = -1`) and renders as
a `runtime (no source)` block — never guessed onto a line.

---

## 5. How the disassembly works (cross-arch)

```
examples/*.java  (or any source)
  │  javac -g (host)              architecture-neutral bytecode
  ▼
.classes/*.class
  │  java (arch-specific JVM)     JIT compiles ONLY the target, pinned deterministic;
  ▼                              hsdis disassembles the in-memory code.
real machine code  ──▶  dmb ishst (arm64)  |  nothing (x86)
```

`scripts/disasm.sh` runs `java` inside a GraalVM container of the target arch via `podman
--arch`. On an x86 host, **x86_64 runs natively**; **arm64 is emulated by qemu-user**. The
JIT selects its backend from the arch the JVM *thinks* it's on, so an Intel box emits
genuine AArch64 (`os.arch = aarch64`, `x*/w*` registers — verified). Classes and the hsdis
plugin are bind-mounted **read-only** (`:ro,z`); `MAIN_CLASS`/`TARGET` are passed via `-e`
so `<init>`, `::`, and `*` need no shell quoting. The full transcript is teed to
`logs/disasm-<ts>-<arch>.log`; a `###RESULT### OK|FAIL` line (read back from the log) is
the authoritative verdict.

JDK 21 `java`/`javac` tool specs: `docs.oracle.com/en/java/javase/21/docs/specs/man/`.

---

## 6. The trace model (`DisasmGui.java`)

- **`Instr`** — one decoded machine instruction: `text`, plus the `(method, bci, line)`
  from the following hsdis annotation (`line = -1` if unscoped).
- **`BcGroup`** — a maximal run of consecutive instructions sharing one **BCI**: the
  bytecode text (via `javap -c`) plus its machine instructions.
- **`Block`** — a maximal run of consecutive instructions sharing one `(method, line)`
  (or all-runtime, `line < 0`), holding its `BcGroup`s and the resolved source text.

Grouping is over **consecutive** instructions only, so a line or BCI the JIT split yields
multiple `Block`s / `BcGroup`s — the integrity contract (§3) falls out of the data
structure rather than being enforced after the fact.

---

## 7. The interactive view & HTML export

The trace renders as a custom collapsible tree (`TraceList`, a `Scrollable` `JPanel`),
**not** a flat `JTextPane`:

- **Source-only by default**; expanding a `Block` reveals its bytecode/machine. In
  3-level mode each `BcGroup` folds independently; in 2-level mode bytecode+machine show
  together.
- **Fold handle on the right** of every row (`▸`/`▾`). Stock `JTree` forces its handle on
  the left and can't move it, hence the custom widget. Rows fill a shared content width so
  handles align on one right edge while long `asm` lines stay horizontally scrollable
  rather than clipped.
- **Detail toggle (2-/3-level)** and **Expand all / Collapse all**.
- **Last source folder is remembered** via `java.util.prefs.Preferences`
  (`userRoot().node("javabytecodemachine")`), not a file in the project tree — so a
  clone stays clean and there's nothing to git-ignore for it.
- **HTML export** walks the same model **fully expanded** (the complete, non-misleading
  picture), emitting nested native `<details>`/`<summary>` — collapsible in any browser,
  no server, no JavaScript. The fold marker sits on the right there too
  (`summary { display:flex; justify-content:space-between }` + a `::after` triangle). The
  exporter is unit-checked headlessly (build a model, call `buildHtml()`, assert the
  `<details>` tags balance and source/bytecode/machine all appear).

---

## 8. Environment

- Host: **x86_64 Fedora**, rootless **podman** (no docker).
- arm64 emulation: `sudo dnf install qemu-user-static` registers the binfmt handlers
  (verify: `ls /proc/sys/fs/binfmt_misc/ | grep aarch64`).
- Image: `ghcr.io/graalvm/native-image-community:21` (GraalVM CE, JDK 21; Graal JIT).
  Minimal image — **no `find`** (scripts use bash globs).
- Host JDK for the GUI + enumeration: a GraalVM JDK 21 (Swing chosen for **zero
  installs**). Display: Wayland + XWayland.
- **Shell quirk:** this environment has intermittently injected fabricated output — trust
  only hard signals (`EXIT=$?`, `file`, `stat -c%s`, grep counts).

---

## 9. Key decisions & lessons (condensed history)

- **Smallest tool that produces the artifact.** Early attempts used the heaviest
  machinery (HotSpot `develop` IR flags → absent in prod; GraalVM Native-Image AOT + IGV →
  showed the freeze only as an IR node, builds 10–20 min under emulation). The thing that
  worked is the *lightest*: JIT-compile one method and print it (~20× faster).
- **Target the constructor `<init>` directly,** not a caller. Under `-Xcomp`, a caller's
  `new` deoptimizes before the class is initialized, so the freeze never appears; `<init>`
  runs only after allocation → freeze emitted.
- **The tool compiles from source itself, with `-g`.** Owning compilation guarantees debug
  info and makes the reverse source-mapping authoritative instead of guesswork.
- **Don't reconstruct/parse source heuristically.** The mapping must come from JDK
  metadata (LineNumberTable + JIT debug info), shown verbatim — integrity over cleverness.
  A 3-pane click-to-sync view was rejected in favor of the single always-in-sync trace.
- **Integrity bug caught & fixed:** attribute to the *following* annotation with **no**
  fallback; unscoped code → `runtime` block.
- **The flat dump became an interactive tree** without weakening §3: grouping over
  consecutive instructions means a JIT-split line *or bytecode* recurs as separate nodes.
  The HTML export is the full expansion, so the shared artifact can't mislead even though
  the live view starts collapsed.
- Verified headlessly throughout (compile, model-building, line attribution, HTML export)
  since the Swing window can't run in the sandbox.

---

## 10. Possible next steps

A left line-number gutter; jump-to-the-freeze / search-filter; optional auto-folding of
the long entry/prologue block; an address-column toggle; side-by-side architecture
comparison. **Keep the integrity rules (§3) intact when restyling** — don't reorder or
relabel data. The Swing window still hasn't been visually eyeballed in-sandbox; the HTML
export can be previewed at `logs/sample-export.html`.
