// DisasmGui.java — Swing front-end for scripts/disasm.sh.
//
// UNIFIED TRACE as an INTERACTIVE TREE, read top-to-bottom: SOURCE ▸ BYTECODE ▸ MACHINE.
//
// The screen view collapses by default to SOURCE lines only; you expand to reveal
// the bytecode and the real machine instructions. A live toggle switches between:
//   * 3-level:  source ▸ bytecode ▸ machine   (independent fold at each level)
//   * 2-level:  source ▸ (bytecode + machine together)
// The expand/collapse handle sits on the RIGHT of each row (a proper tree view,
// just mirrored), so the left edge stays a clean code listing.
//
// EXPORT is always the full, truthful picture: a self-contained .html that is
// FULLY EXPANDED by default (source + bytecode + machine, machine-address order),
// yet still collapsible in any browser via native <details> — no server, no JS.
//
// The tool owns the whole chain (source → javac -g → bytecode → JIT), so the
// mapping is authoritative, not guessed. Integrity rules (no misleading output):
//   * MACHINE ORDER is the spine — instructions are NEVER reordered. A source
//     line the JIT scheduled non-contiguously simply appears as more than one
//     block (truthful), instead of being faked into one. Same for a bytecode whose
//     instructions the JIT split: its node recurs rather than being regrouped.
//   * Every instruction shows its real PC; every bytecode its real BCI; so the
//     ordering/grouping is always auditable.
//   * A block's bytecodes are EXACTLY the BCIs its instructions implement (from
//     HotSpot's PcDesc), not "all bytecode of the line".
//   * Instructions with no source mapping (frame setup, safepoint polls, GC/▸
//     barriers, stubs) are shown under a `runtime (no source)` header built from
//     HotSpot's own reloc comment — never attached to a fake line.
//   * Anything unresolved is labelled "(unavailable)" rather than invented.
//   * The view shows only what javac/javap/hsdis emit — no editorial commentary.
//
// The bridge is the BCI: javac's LineNumberTable maps source line ↔ BCI, the
// JIT's debug info (which HotSpot prints as `; - C::m@bci (line N)`) maps machine
// instruction ↔ BCI.  HotSpot prints that annotation AFTER the instruction it
// describes, so an instruction takes the FOLLOWING annotation's (method,bci,line).
//
// Run with the JDK source launcher:  java DisasmGui.java [projectRoot]

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DisasmGui {

    static final Path ROOT = Paths.get(System.getProperty("disasm.root",
            System.getProperty("user.dir"))).toAbsolutePath();
    final Path classesDir = ROOT.resolve(".classes");
    // Last-used source folder is remembered via the OS-managed per-user prefs store
    // (no file in the project tree). See INTERNALS.
    final Preferences prefs = Preferences.userRoot().node("javabytecodemachine");
    static final String PREF_SRC = "lastSourceFolder";
    final String jdkBin = Paths.get(System.getProperty("java.home"), "bin").toString();

    public static void main(String[] args) {
        Path root = (args.length > 0) ? Paths.get(args[0]).toAbsolutePath() : ROOT;
        SwingUtilities.invokeLater(() -> new DisasmGui().build(root));
    }

    // ---- palette (VS Code dark) ----
    static final Color BG     = new Color(0x1e1e1e);
    static final Color ASM    = new Color(0xd4d4d4);
    static final Color SRC    = new Color(0xdcdcaa); // source line header
    static final Color BC     = new Color(0x9aa0a6); // bytecode (secondary)
    static final Color RT     = new Color(0x6a8aa0); // runtime (no source)
    static final Color HEAD   = new Color(0x4ec9b0);
    static final Color LEGEND = new Color(0x808080);
    static final Color ERR    = new Color(0xff9d00);
    static final Color TOG    = new Color(0x6e7681); // fold handle

    // ---- controls ----
    JTextField srcFolderField, target;
    JButton browse, run, rescan, export, expandAll, collapseAll;
    JComboBox<String> runClass;
    JTree tree;
    JRadioButton arm64, x86, lvl2, lvl3;
    JLabel status;

    // ---- output (interactive tree) ----
    TraceList listPanel;
    JScrollPane outScroll;

    boolean busy, ready, threeLevel = true;
    Path root;

    // the current trace model (machine-address order)
    String titleText = "", legendTextStr = "", logPathStr = null;
    List<String[]> infoRows = new ArrayList<>();  // header/legend/error rows (no fold)
    List<String[]> footRows = new ArrayList<>();  // transcript pointer
    List<Block> model = new ArrayList<>();

    // per-run caches (class -> ...)
    final Map<String, Map<String, Map<Integer, String>>> bcCache = new LinkedHashMap<>();
    final Map<String, List<String>> srcCache = new LinkedHashMap<>();

    void build(Path root) {
        this.root = root;
        JFrame f = new JFrame("JavaByteCodeMachine  —  study: source ▸ bytecode ▸ machine");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        srcFolderField = new JTextField(34);
        browse = new JButton("Browse…");
        browse.addActionListener(e -> chooseFolder());
        rescan = new JButton("↻");
        rescan.setToolTipText("Re-compile + re-read the source folder");
        rescan.addActionListener(e -> scan());
        runClass = new JComboBox<>();
        runClass.setEditable(true);
        arm64 = new JRadioButton("arm64", true);
        x86   = new JRadioButton("x86_64");
        ButtonGroup g = new ButtonGroup(); g.add(arm64); g.add(x86);
        run = new JButton("Disassemble");
        run.setEnabled(false);
        run.addActionListener(e -> disassemble());

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row1.add(new JLabel("Source folder (I compile it with -g):")); row1.add(srcFolderField);
        row1.add(browse); row1.add(rescan);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row2.add(new JLabel("Run class (main):")); row2.add(runClass);
        row2.add(new JLabel("arch:")); row2.add(arm64); row2.add(x86);
        row2.add(run);
        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(row1); top.add(row2);

        tree = new JTree(new DefaultMutableTreeNode("(choose a source folder)"));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (n == null) return;
            Object uo = n.getUserObject();
            if (uo instanceof Node && ((Node) uo).target != null) target.setText(((Node) uo).target);
        });
        target = new JTextField("", 20);
        JPanel left = new JPanel(new BorderLayout());
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new Dimension(250, 200));
        JPanel tgtPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        tgtPanel.add(new JLabel("Target:")); tgtPanel.add(target);
        left.add(treeScroll, BorderLayout.CENTER);
        left.add(tgtPanel, BorderLayout.SOUTH);

        // ---- the interactive trace view ----
        listPanel = new TraceList();
        outScroll = new JScrollPane(listPanel);
        outScroll.getViewport().setBackground(BG);
        outScroll.getVerticalScrollBar().setUnitIncrement(16);

        // ---- view controls strip (right pane header) ----
        lvl3 = new JRadioButton("3-level (bytecode layer)", true);
        lvl2 = new JRadioButton("2-level");
        ButtonGroup lg = new ButtonGroup(); lg.add(lvl3); lg.add(lvl2);
        lvl3.addActionListener(e -> { threeLevel = true;  render(false); });
        lvl2.addActionListener(e -> { threeLevel = false; render(false); });
        expandAll   = new JButton("Expand all");
        collapseAll = new JButton("Collapse all");
        expandAll.addActionListener(e -> setAllExpanded(true));
        collapseAll.addActionListener(e -> setAllExpanded(false));
        export = new JButton("Export HTML…");
        export.setToolTipText("Write the full, expanded trace as a self-contained, collapsible .html");
        export.addActionListener(e -> exportHtml());
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        strip.add(new JLabel("Detail:")); strip.add(lvl3); strip.add(lvl2);
        strip.add(Box.createHorizontalStrut(8));
        strip.add(expandAll); strip.add(collapseAll);
        strip.add(Box.createHorizontalStrut(8));
        strip.add(export);

        JPanel right = new JPanel(new BorderLayout());
        right.add(strip, BorderLayout.NORTH);
        right.add(outScroll, BorderLayout.CENTER);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        main.setDividerLocation(250);

        status = new JLabel(" choose a source folder — I compile it with -g, then build the unified trace");

        f.setLayout(new BorderLayout());
        f.add(top, BorderLayout.NORTH);
        f.add(main, BorderLayout.CENTER);
        f.add(status, BorderLayout.SOUTH);
        f.setSize(1200, 760);
        f.setLocationRelativeTo(null);
        f.setVisible(true);

        try {
            String s = prefs.get(PREF_SRC, "").trim();
            if (!s.isEmpty() && Files.isDirectory(Paths.get(s))) { srcFolderField.setText(s); scan(); }
        } catch (Exception ignored) {}
    }

    void chooseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Select your SOURCE folder (.java root)");
        String cur = srcFolderField.getText().trim();
        if (!cur.isEmpty() && Files.isDirectory(Paths.get(cur))) fc.setCurrentDirectory(new File(cur));
        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            srcFolderField.setText(fc.getSelectedFile().getAbsolutePath());
            scan();
        }
    }

    static class Node {
        final String label, target;
        Node(String label, String target) { this.label = label; this.target = target; }
        @Override public String toString() { return label; }
    }

    // ==================================================================
    // SCAN: compile the source folder with -g, then list classes.
    // ==================================================================
    void scan() {
        String srcFolder = srcFolderField.getText().trim();
        if (srcFolder.isEmpty() || !Files.isDirectory(Paths.get(srcFolder))) {
            status.setText(" not a folder: " + srcFolder); return;
        }
        prefs.put(PREF_SRC, srcFolder);
        bcCache.clear(); srcCache.clear();
        status.setText(" compiling " + srcFolder + " with javac -g …");
        ready = false; setBusy(true);
        new SwingWorker<Object[], Void>() {
            String err = null;
            @Override protected Object[] doInBackground() {
                try {
                    String c = compile(Paths.get(srcFolder));
                    if (c != null) { err = c; return null; }
                    return enumerate(classesDir);
                } catch (Exception ex) { err = ex.toString(); return null; }
            }
            @Override @SuppressWarnings("unchecked")
            protected void done() {
                Object[] res; try { res = get(); } catch (Exception ex) { res = null; }
                if (res == null) {
                    ready = false; setBusy(false);
                    status.setText(" compile failed — see output");
                    showMessage("javac -g failed:", err == null ? "(unknown)" : err);
                    return;
                }
                DefaultMutableTreeNode r = (DefaultMutableTreeNode) res[0];
                List<String> mains = (List<String>) res[1];
                int n = (Integer) res[2];
                tree.setModel(new DefaultTreeModel(r));
                for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
                runClass.setModel(new DefaultComboBoxModel<>(mains.toArray(new String[0])));
                runClass.setEditable(mains.isEmpty());
                ready = n > 0; setBusy(false);
                status.setText(" compiled " + n + " class(es) (-g), " + mains.size()
                        + " with main() — pick a target + run-class");
            }
        }.execute();
    }

    String compile(Path srcFolder) throws Exception {
        List<Path> javaFiles;
        try (Stream<Path> w = Files.walk(srcFolder)) {
            javaFiles = w.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        if (javaFiles.isEmpty()) return "no .java files found under " + srcFolder;
        deleteTree(classesDir);
        Files.createDirectories(classesDir);
        // --release 21 pins the class-file version to the JDK 21 engine, so a host JDK
        // newer than 21 can't emit classes the container can't load. See INTERNALS §2.6.
        List<String> cmd = new ArrayList<>(List.of(jdkBin + File.separator + "javac", "-g", "--release", "21", "-d", classesDir.toString()));
        for (Path p : javaFiles) cmd.add(p.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = br.readLine()) != null) sb.append(l).append('\n');
        }
        return p.waitFor() == 0 ? null : sb.toString();
    }

    void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    Object[] enumerate(Path folder) throws Exception {
        DefaultMutableTreeNode r = new DefaultMutableTreeNode(srcFolderField.getText().trim());
        List<Path> classFiles;
        try (Stream<Path> w = Files.walk(folder)) {
            classFiles = w.filter(p -> p.toString().endsWith(".class"))
                          .filter(p -> !p.getFileName().toString().matches(".*\\$\\d+\\.class"))
                          .sorted().collect(Collectors.toList());
        }
        List<String> mains = new ArrayList<>();
        int count = 0;
        for (Path cf : classFiles) {
            String fqn = folder.relativize(cf).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", "");
            ClassMembers cm = members(fqn);
            count++;
            if (cm.hasMain) mains.add(fqn);
            DefaultMutableTreeNode classNode = new DefaultMutableTreeNode(new Node(fqn, null));
            classNode.add(new DefaultMutableTreeNode(new Node("⛬ whole class  (::*)", fqn + "::*")));
            for (String m : cm.methods)
                classNode.add(new DefaultMutableTreeNode(new Node(m, fqn + "::" + m.substring(0, m.indexOf('(')))));
            r.add(classNode);
        }
        return new Object[]{r, mains, count};
    }

    static class ClassMembers { final List<String> methods = new ArrayList<>(); boolean hasMain; }
    static final Pattern SIG = Pattern.compile("([\\w$<>]+)\\s*\\([^)]*\\)\\s*;");

    ClassMembers members(String fqn) throws Exception {
        ClassMembers cm = new ClassMembers();
        String simple = simpleName(fqn);
        for (String line : javap("-p", fqn)) {
            Matcher mt = SIG.matcher(line.trim());
            if (!mt.find()) continue;
            String name = mt.group(1);
            String display = name.equals(simple) ? "<init>()" : name + "()";
            if (name.equals("main")) cm.hasMain = true;
            if (!cm.methods.contains(display)) cm.methods.add(display);
        }
        return cm;
    }

    static String simpleName(String fqn) {
        String s = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        return s.contains("$") ? s.substring(s.lastIndexOf('$') + 1) : s;
    }

    /** javap with the last arg = the class; flags precede it, then -classpath, then the class. */
    List<String> javap(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(); cmd.add(jdkBin + File.separator + "javap");
        for (int i = 0; i < args.length - 1; i++) cmd.add(args[i]);
        cmd.add("-classpath"); cmd.add(classesDir.toString());
        cmd.add(args[args.length - 1]);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = br.readLine()) != null) out.add(l);
        }
        p.waitFor();
        return out;
    }

    // ==================================================================
    // DISASSEMBLE + build the unified trace model.
    // ==================================================================
    void disassemble() {
        String arch = arm64.isSelected() ? "arm64" : "x86_64";
        String mc = String.valueOf(runClass.getEditor().getItem()).trim();
        String tg = target.getText().trim();
        if (!ready) { status.setText(" compile a source folder first"); return; }
        if (mc.isEmpty()) { status.setText(" pick a run-class (a class with main())"); return; }
        if (tg.isEmpty() || !tg.contains("::")) { status.setText(" pick a target in the tree"); return; }

        model = new ArrayList<>(); infoRows = new ArrayList<>(); footRows = new ArrayList<>();
        render(true);
        setBusy(true);
        status.setText(" running (" + arch + ") — arm64 is emulated, first run can take a bit…");

        new SwingWorker<Void, Void>() {
            final List<String> buf = new ArrayList<>();
            String statusMsg = "";
            boolean ok = false;
            String logPath = null;
            @Override protected Void doInBackground() throws Exception {
                Process p = new ProcessBuilder("bash",
                        root.resolve("scripts/disasm.sh").toString(), arch, classesDir.toString(), mc, tg)
                        .directory(root.toFile()).redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("###LOG###")) logPath = line.substring(9).trim();
                        else if (line.startsWith("###RESULT###")) ok = line.contains("OK");
                        else buf.add(line);
                    }
                }
                int code = p.waitFor();
                logPathStr = logPath;
                if (ok) {
                    buildModel(buf, tg, arch, logPath);
                    statusMsg = " done" + (logPath != null ? "   ·   full log: " + logPath : "");
                } else {
                    buildFailure(buf, logPath, code);
                    statusMsg = " FAILED" + (logPath != null ? "   ·   see log: " + logPath : "   (exit " + code + ")");
                }
                return null;
            }
            @Override protected void done() { setBusy(false); render(true); status.setText(statusMsg); }
        }.execute();
    }

    // ----- one decoded machine instruction -----
    static class Instr { String text; String method; int bci = -1, line = -1; }
    static final Pattern ASM_ANN = Pattern.compile("; - (.+?)::([^@]+)@(\\d+) \\(line (\\d+)\\)");
    static final Pattern PADDING = Pattern.compile("0x[0-9a-fA-F]+:\\s*(0x[0-9a-fA-F]+\\s*)+");

    /** Decode the machine instructions, attaching (method,bci,line) from the FOLLOWING annotation. */
    List<Instr> machineInstrs(List<String> buf) {
        int start = 0;
        for (int i = 0; i < buf.size(); i++) {
            String l = buf.get(i).toLowerCase();
            if (l.contains("compiled method") || l.contains("{method}") || l.contains("c2-compiled")) { start = i; break; }
        }
        List<String> seg = buf.subList(start, buf.size());
        int n = seg.size();
        boolean[] isAnn = new boolean[n];
        String[] aM = new String[n]; int[] aB = new int[n], aL = new int[n];
        for (int i = 0; i < n; i++) {
            Matcher m = ASM_ANN.matcher(seg.get(i));
            if (m.find()) { isAnn[i] = true; aM[i] = m.group(1) + "::" + m.group(2).trim();
                            aB[i] = Integer.parseInt(m.group(3)); aL[i] = Integer.parseInt(m.group(4)); }
        }
        // HotSpot prints `; - @bci (line N)` AFTER the instruction it describes, so an
        // instruction's scope is the FOLLOWING annotation. There is deliberately NO
        // fallback to a preceding one: trailing/out-of-line code (epilogue, safepoint
        // poll, deopt/exception stubs) that HotSpot left unscoped stays unattributed
        // (line = -1) and is shown as `runtime (no source)` — never guessed onto a line.
        int[] next = new int[n];
        for (int i = n - 1, r = -1; i >= 0; i--) { next[i] = r; if (isAnn[i]) r = i; }
        List<Instr> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (isAnn[i]) continue;
            String tt = seg.get(i).trim();
            if (!tt.startsWith("0x") || PADDING.matcher(tt).matches()) continue;
            int a = next[i];
            Instr ins = new Instr(); ins.text = tt;
            if (a >= 0) { ins.method = aM[a]; ins.bci = aB[a]; ins.line = aL[a]; }
            out.add(ins);
        }
        return out;
    }

    // ----- the trace model -----
    static class BcGroup {
        int bci = -1;          // -1 for runtime
        String bcText;         // null for runtime / unavailable
        final List<String> asm = new ArrayList<>();
        boolean expanded;      // 3-level: machine instrs revealed
    }
    static class Block {
        boolean runtime;
        String method;         // non-runtime
        int line = -1;         // non-runtime
        String srcText;        // resolved source text (may be null)
        boolean showMethod;    // prefix the method (whole-class / inlined callee)
        final List<BcGroup> groups = new ArrayList<>();
        boolean expanded;      // bytecode/machine revealed
    }

    /** Build the machine-order trace model. Consecutive instrs with the same
     *  (method,line) form a Block; within it, consecutive instrs with the same BCI
     *  form a BcGroup. A line/BCI the JIT split simply yields more than one
     *  Block/BcGroup — machine order is never broken to regroup. */
    void buildModel(List<String> buf, String tg, String arch, String logPath) {
        List<Instr> ins = machineInstrs(buf);
        infoRows = new ArrayList<>(); footRows = new ArrayList<>(); model = new ArrayList<>();
        titleText = " " + tg + "    (" + arch + ")";
        legendTextStr = " machine order is the real layout · addresses/BCIs are real · a source"
                + " line (or bytecode) may recur if the JIT split it · 'runtime' = VM-injected, no source";
        infoRows.add(new String[]{titleText, "head"});
        infoRows.add(new String[]{legendTextStr, "legend"});
        if (ins.isEmpty()) {
            infoRows.add(new String[]{"(no decodable instructions; see log: " + logPath + ")", "err"});
            return;
        }
        boolean wildcard = tg.endsWith("*");
        int i = 0;
        while (i < ins.size()) {
            Instr first = ins.get(i);
            boolean rt = first.line < 0;
            int j = i + 1;
            while (j < ins.size() && sameGroup(ins.get(j), first)) j++;
            List<Instr> grp = ins.subList(i, j);
            Block b = new Block();
            if (rt) {
                b.runtime = true;
                BcGroup gp = new BcGroup();
                for (Instr x : grp) gp.asm.add(x.text);
                b.groups.add(gp);
            } else {
                b.method = first.method;
                b.line = first.line;
                String cls = first.method.substring(0, first.method.indexOf("::"));
                String name = first.method.substring(first.method.indexOf("::") + 2);
                b.srcText = sourceLine(cls, first.line);
                b.showMethod = wildcard || !first.method.equals(tg);
                BcGroup cur = null;
                for (Instr x : grp) {
                    if (cur == null || x.bci != cur.bci) {
                        cur = new BcGroup();
                        cur.bci = x.bci;
                        cur.bcText = bytecodeText(cls, name, x.bci);
                        b.groups.add(cur);
                    }
                    cur.asm.add(x.text);
                }
            }
            model.add(b);
            i = j;
        }
        if (logPath != null) footRows.add(new String[]{" full transcript: " + logPath, "legend"});
    }

    boolean sameGroup(Instr a, Instr first) {
        if (first.line < 0) return a.line < 0;
        return a.line == first.line && first.method.equals(a.method);
    }

    // ----- bytecode text for (class, method, bci), via javap -c -l, cached -----
    static final Pattern BC_INSTR = Pattern.compile("^\\s+(\\d+):\\s+(.*)$");
    String bytecodeText(String cls, String method, int bci) {
        Map<String, Map<Integer, String>> byMethod = bcCache.computeIfAbsent(cls, c -> {
            Map<String, Map<Integer, String>> m = new LinkedHashMap<>();
            try {
                List<String> L = javap("-c", "-p", c);
                String simple = simpleName(c);
                String curName = null; Map<Integer, String> cur = null;
                for (String line : L) {
                    Matcher hs = SIG.matcher(line.trim());
                    if (hs.find() && !line.trim().startsWith("//")) {
                        String nm = hs.group(1);
                        curName = nm.equals(simple) ? "<init>" : nm;
                        cur = m.computeIfAbsent(curName, k -> new LinkedHashMap<>());
                        continue;
                    }
                    Matcher im = BC_INSTR.matcher(line);
                    if (cur != null && im.find()) cur.put(Integer.parseInt(im.group(1)), im.group(2).trim());
                }
            } catch (Exception ignored) {}
            return m;
        });
        Map<Integer, String> bm = byMethod.get(method);
        return bm == null ? null : bm.get(bci);
    }

    // ----- source line text for (class, line), cached -----
    String sourceLine(String cls, int line) {
        List<String> lines = srcCache.computeIfAbsent(cls, c -> {
            try {
                String fileName = null;
                for (String l : javap("-p", c)) {
                    int q = l.indexOf("Compiled from \"");
                    if (q >= 0) { fileName = l.substring(q + 15, l.length() - 1); break; }
                }
                if (fileName == null) fileName = simpleName(c) + ".java";
                int dot = c.lastIndexOf('.');
                Path srcRoot = Paths.get(srcFolderField.getText().trim());
                Path dir = (dot < 0) ? srcRoot : srcRoot.resolve(c.substring(0, dot).replace('.', File.separatorChar));
                return Files.readAllLines(dir.resolve(fileName));
            } catch (Exception e) { return null; }
        });
        if (lines == null || line < 1 || line > lines.size()) return null;
        return lines.get(line - 1);
    }

    void buildFailure(List<String> buf, String logPath, int code) {
        infoRows = new ArrayList<>(); footRows = new ArrayList<>(); model = new ArrayList<>();
        infoRows.add(new String[]{"!! Run did not produce compiled machine code for the target.", "err"});
        infoRows.add(new String[]{"", "asm"});
        for (String l : buf) if (l.startsWith("!!") || l.trim().startsWith("(") || l.startsWith("   ")) infoRows.add(new String[]{l, "asm"});
        infoRows.add(new String[]{"", "asm"});
        if (logPath != null) {
            infoRows.add(new String[]{"Full log (what ran, and why it failed):", "head"});
            infoRows.add(new String[]{"   " + logPath, "asm"});
        }
    }

    void showMessage(String head, String body) {
        infoRows = new ArrayList<>(); footRows = new ArrayList<>(); model = new ArrayList<>();
        infoRows.add(new String[]{head, "err"});
        for (String l : body.split("\n")) infoRows.add(new String[]{l, "asm"});
        render(true);
    }

    // ==================================================================
    // RENDERING — interactive collapsible rows (fold handle on the RIGHT).
    // ==================================================================
    static Color colorFor(String key) {
        return switch (key) {
            case "head" -> HEAD; case "legend" -> LEGEND; case "err" -> ERR;
            case "src" -> SRC; case "bc" -> BC; case "rt" -> RT; default -> ASM;
        };
    }

    void setAllExpanded(boolean v) {
        for (Block b : model) { b.expanded = v; for (BcGroup g : b.groups) g.expanded = v; }
        render(false);
    }

    void render(boolean resetScroll) {
        listPanel.removeAll();
        for (String[] r : infoRows)
            listPanel.add(makeRow(r[0], colorFor(r[1]), r[1].equals("head") || r[1].equals("err"), 0, null, null));
        if (!infoRows.isEmpty() && !model.isEmpty()) listPanel.add(spacer());
        for (Block b : model) renderBlock(b);
        if (!footRows.isEmpty()) { listPanel.add(spacer()); for (String[] r : footRows) listPanel.add(makeRow(r[0], colorFor(r[1]), false, 0, null, null)); }
        listPanel.revalidate(); listPanel.repaint();
        if (resetScroll) SwingUtilities.invokeLater(() -> outScroll.getVerticalScrollBar().setValue(0));
    }

    void renderBlock(Block b) {
        if (b.runtime) {
            listPanel.add(makeRow("runtime (no source)", RT, false, 0, b.expanded,
                    () -> { b.expanded = !b.expanded; render(false); }));
            if (b.expanded) for (BcGroup gp : b.groups) for (String a : gp.asm) listPanel.add(makeRow(a, RT, false, 1, null, null));
            return;
        }
        String src = b.srcText == null ? "(source unavailable)" : b.srcText.strip();
        String hdr = b.showMethod ? (b.method + "   line " + b.line + "   " + src) : ("line " + b.line + "   " + src);
        listPanel.add(makeRow(hdr, SRC, true, 0, b.expanded, () -> { b.expanded = !b.expanded; render(false); }));
        if (!b.expanded) return;
        for (BcGroup gp : b.groups) {
            String bc = "bc " + gp.bci + "  " + (gp.bcText == null ? "(bytecode unavailable)" : gp.bcText);
            if (threeLevel) {
                listPanel.add(makeRow(bc, BC, false, 1, gp.expanded, () -> { gp.expanded = !gp.expanded; render(false); }));
                if (gp.expanded) for (String a : gp.asm) listPanel.add(makeRow(a, ASM, false, 2, null, null));
            } else {
                listPanel.add(makeRow(bc, BC, false, 1, null, null));
                for (String a : gp.asm) listPanel.add(makeRow(a, ASM, false, 1, null, null));
            }
        }
    }

    JComponent spacer() {
        JPanel p = new JPanel(); p.setBackground(BG);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    static final Font MONO_B = new Font(Font.MONOSPACED, Font.BOLD, 13);

    /** One row: indented text on the left, an optional fold handle on the RIGHT.
     *  expanded == null → no handle (a leaf / header). Otherwise show ▸/▾ and
     *  make the whole row a click target running onToggle. */
    JComponent makeRow(String text, Color fg, boolean bold, int indent, Boolean expanded, Runnable onToggle) {
        JPanel rowp = new JPanel(new BorderLayout());
        rowp.setBackground(BG);
        rowp.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel lab = new JLabel(text.isEmpty() ? " " : text);
        lab.setForeground(fg);
        lab.setFont(bold ? MONO_B : MONO);
        lab.setBorder(BorderFactory.createEmptyBorder(2, 10 + indent * 22, 2, 14));
        rowp.add(lab, BorderLayout.CENTER);
        if (expanded != null) {
            JLabel tog = new JLabel(expanded ? "▾" : "▸");
            tog.setForeground(TOG);
            tog.setFont(MONO_B);
            tog.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 14));
            rowp.add(tog, BorderLayout.EAST);
            rowp.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            rowp.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { onToggle.run(); }
                @Override public void mouseEntered(MouseEvent e) { rowp.setBackground(new Color(0x2a2d2e)); lab.setOpaque(false); }
                @Override public void mouseExited(MouseEvent e) { rowp.setBackground(BG); }
            });
        }
        int h = rowp.getPreferredSize().height;
        rowp.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        return rowp;
    }

    /** Vertical list that allows horizontal scrolling (long asm lines never clip)
     *  while every fold handle aligns to the same right edge. */
    static class TraceList extends JPanel implements Scrollable {
        TraceList() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setBackground(BG); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return d == SwingConstants.VERTICAL ? r.height : r.width; }
        public boolean getScrollableTracksViewportWidth() { return false; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    void setBusy(boolean b) {
        busy = b;
        browse.setEnabled(!b); rescan.setEnabled(!b);
        run.setEnabled(!b && ready);
    }

    // ==================================================================
    // EXPORT — a self-contained, fully-EXPANDED, collapsible .html.
    // ==================================================================
    void exportHtml() {
        if (model.isEmpty()) { status.setText(" nothing to export yet — run a disassembly first"); return; }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export the full trace as a collapsible HTML file");
        fc.setSelectedFile(new File("trace.html"));
        if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;
        File dest = fc.getSelectedFile();
        if (!dest.getName().toLowerCase().endsWith(".html") && !dest.getName().toLowerCase().endsWith(".htm"))
            dest = new File(dest.getParentFile(), dest.getName() + ".html");
        try {
            Files.writeString(dest.toPath(), buildHtml());
            status.setText(" exported → " + dest.getAbsolutePath());
        } catch (IOException ex) { status.setText(" export failed: " + ex.getMessage()); }
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Fully expanded (all <details open>), 3-level, so the exported file shows the
     *  complete truthful picture at a glance — yet stays collapsible in any browser. */
    String buildHtml() {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n");
        b.append("<title>").append(esc(titleText.trim())).append(" — unified trace</title>\n");
        b.append("<style>\n").append(CSS).append("\n</style></head>\n<body>\n");
        b.append("<h1>").append(esc(titleText.trim())).append("</h1>\n");
        b.append("<p class=\"legend\">").append(esc(legendTextStr.trim())).append("</p>\n");
        for (Block blk : model) {
            if (blk.runtime) {
                b.append("<details open class=\"rt\"><summary>runtime (no source)</summary>\n");
                for (BcGroup gp : blk.groups) for (String a : gp.asm)
                    b.append("  <div class=\"asm\">").append(esc(a)).append("</div>\n");
                b.append("</details>\n");
            } else {
                String src = blk.srcText == null ? "(source unavailable)" : blk.srcText.strip();
                String hdr = blk.showMethod ? (blk.method + "   line " + blk.line + "   " + src)
                                            : ("line " + blk.line + "   " + src);
                b.append("<details open class=\"src\"><summary>").append(esc(hdr)).append("</summary>\n");
                for (BcGroup gp : blk.groups) {
                    String bc = "bc " + gp.bci + "  " + (gp.bcText == null ? "(bytecode unavailable)" : gp.bcText);
                    b.append("  <details open class=\"bc\"><summary>").append(esc(bc)).append("</summary>\n");
                    for (String a : gp.asm)
                        b.append("    <div class=\"asm\">").append(esc(a)).append("</div>\n");
                    b.append("  </details>\n");
                }
                b.append("</details>\n");
            }
        }
        if (logPathStr != null)
            b.append("<p class=\"foot\">full transcript: ").append(esc(logPathStr)).append("</p>\n");
        b.append("</body></html>\n");
        return b.toString();
    }

    // Dark theme; native <details>/<summary> folding with the marker on the RIGHT
    // (display:flex + space-between), mirroring the in-app tree.
    static final String CSS = String.join("\n",
        "body{background:#1e1e1e;color:#d4d4d4;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:13px;margin:16px;line-height:1.5;}",
        "h1{color:#4ec9b0;font-size:15px;font-weight:bold;margin:0 0 4px;}",
        ".legend{color:#808080;margin:0 0 14px;white-space:pre-wrap;}",
        ".foot{color:#808080;margin-top:14px;}",
        "details{border-left:1px solid #2d2d2d;}",
        "summary{cursor:pointer;list-style:none;display:flex;justify-content:space-between;align-items:baseline;",
            "padding:2px 10px;white-space:pre;border-radius:3px;}",
        "summary:hover{background:#2a2d2e;}",
        "summary::-webkit-details-marker{display:none;}",
        "summary::after{content:\"\\25B8\";color:#6e7681;margin-left:18px;}",
        "details[open]>summary::after{content:\"\\25BE\";}",
        "details.src>summary{color:#dcdcaa;font-weight:bold;}",
        "details.rt>summary{color:#6a8aa0;}",
        "details.bc>summary{color:#9aa0a6;}",
        "details.src>*,details.rt>*{padding-left:18px;}",
        "details.bc{padding-left:18px;}",
        ".asm{color:#d4d4d4;white-space:pre;padding:1px 10px;}");
}
