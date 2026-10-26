package com.chemlab.service;

import com.chemlab.data.CalibrationDao;
import com.chemlab.data.RecordDao;
import com.chemlab.model.Calibration;
import com.chemlab.model.Record;
import com.chemlab.util.AppPaths;
import com.chemlab.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Obsidian 双向同步引擎。
 *
 * <p>把应用数据与一个 Obsidian 库（vault）互相同步：
 * <ul>
 *   <li>实验记录、标定曲线：双向同步。笔记以 YAML frontmatter 承载结构化字段，
 *       正文按固定小标题分段；以 {@code id} 匹配记录，用 {@code updated} 时间戳
 *       判定哪一侧更新，较新者覆盖较旧者。</li>
 *   <li>知识库参考：单向导出（软件 → 库），每章一篇笔记，另生成书目目录与总索引。</li>
 * </ul>
 *
 * <p>库内固定目录结构：
 * <pre>
 *   &lt;vault&gt;/ChemLab/实验记录/*.md
 *   &lt;vault&gt;/ChemLab/标定曲线/*.md
 *   &lt;vault&gt;/ChemLab/知识库/*.md
 *   &lt;vault&gt;/ChemLab/.chemlab-sync.json     # 同步清单（id → 文件名，防止重复建笔记）
 *   &lt;vault&gt;/ChemLab/ChemLab 索引.md
 * </pre>
 *
 * <p>删除不做传播：应用内删除记录不会删除笔记，库内删除笔记也不会删除记录，
 * 只会被视为「缺失」而在下次导出时重新生成（记录）或忽略（标定曲线）。
 */
public final class ObsidianSync {

    public static final String DIR_RECORDS = "实验记录";
    public static final String DIR_CALIB = "标定曲线";
    public static final String DIR_KB = "知识库";
    private static final String ROOT_DIR = "ChemLab";
    private static final String MANIFEST = ".chemlab-sync.json";
    private static final int KB_NOTE_LIMIT = 24000;
    /** 判定「库内编辑」的 mtime 容差（秒），避免导出写入造成的秒级抖动误判。 */
    private static final long EDIT_TOLERANCE_SECONDS = 3;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path vault;
    private final Consumer<String> log;

    public ObsidianSync(Path vault, Consumer<String> log) {
        this.vault = vault;
        this.log = log == null ? s -> { } : log;
    }

    // ------------------------------------------------------------------
    // 库路径配置
    // ------------------------------------------------------------------

    /** 读取已保存的 Obsidian 库路径，未配置时返回空串。 */
    public static String loadVaultPath() {
        Path f = AppPaths.obsidianConfig();
        if (!Files.isRegularFile(f)) {
            return "";
        }
        try {
            Map<String, Object> m = Json.asObject(
                    Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
            return Json.str(m.get("vault"), "");
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** 保存 Obsidian 库路径。 */
    public static void saveVaultPath(String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vault", path == null ? "" : path);
        try {
            Files.write(AppPaths.obsidianConfig(),
                    Json.write(m).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[chemlab] 保存 Obsidian 配置失败：" + e.getMessage());
        }
    }

    public Path vault() {
        return vault;
    }

    /** 同步根目录 {@code <vault>/ChemLab}。 */
    public Path root() {
        return vault.resolve(ROOT_DIR);
    }

    /** 库是否看起来像一个 Obsidian 库（存在 .obsidian 目录）。 */
    public boolean looksLikeVault() {
        return Files.isDirectory(vault.resolve(".obsidian"));
    }

    // ------------------------------------------------------------------
    // 同步清单（id → 文件名，防止重复建笔记）
    // ------------------------------------------------------------------

    private Map<String, Object> loadManifest() {
        Path f = root().resolve(MANIFEST);
        if (!Files.isRegularFile(f)) {
            return new LinkedHashMap<>();
        }
        try {
            return Json.asObject(Json.parse(
                    new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private void saveManifest(Map<String, Object> manifest) {
        try {
            write(root().resolve(MANIFEST), Json.write(manifest));
        } catch (IOException e) {
            note(null, "写入同步清单失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 双向同步：记录与标定曲线互相同步，可选一并导出知识库参考。 */
    public SyncReport syncAll(RecordDao recDao, CalibrationDao calDao, boolean includeKb) {
        SyncReport rep = new SyncReport();
        run(rep, () -> {
            Files.createDirectories(root());
            syncRecords(recDao, rep, true, true);
            syncCalibrations(calDao, rep, true, true);
            if (includeKb) {
                exportKnowledge(rep);
            }
            writeMoc(rep);
        });
        return rep;
    }

    /** 仅导出：把应用数据写入库（记录/标定曲线不覆盖较新的库内编辑）。 */
    public SyncReport exportAll(RecordDao recDao, CalibrationDao calDao, boolean includeKb) {
        SyncReport rep = new SyncReport();
        run(rep, () -> {
            Files.createDirectories(root());
            syncRecords(recDao, rep, true, false);
            syncCalibrations(calDao, rep, true, false);
            if (includeKb) {
                exportKnowledge(rep);
            }
            writeMoc(rep);
        });
        return rep;
    }

    /** 仅导入：把库内笔记合并回应用数据库。 */
    public SyncReport importAll(RecordDao recDao, CalibrationDao calDao) {
        SyncReport rep = new SyncReport();
        run(rep, () -> {
            if (!Files.isDirectory(root())) {
                throw new IOException("库内尚无 ChemLab 目录，请先执行一次导出");
            }
            syncRecords(recDao, rep, false, true);
            syncCalibrations(calDao, rep, false, true);
        });
        return rep;
    }

    private interface Body {
        void run() throws Exception;
    }

    private void run(SyncReport rep, Body body) {
        try {
            body.run();
            note(rep, "完成：" + rep.summary());
        } catch (Exception e) {
            rep.errors++;
            note(rep, "中止：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 实验记录
    // ------------------------------------------------------------------

    private void syncRecords(RecordDao dao, SyncReport rep, boolean push, boolean pull)
            throws IOException, java.sql.SQLException {
        Path dir = root().resolve(DIR_RECORDS);
        Map<String, Object> manifest = loadManifest();
        Map<String, Object> recMan = Json.asObject(manifest.get("records"));
        boolean dirty = false;

        List<Record> dbRecords = dao.listAll();
        Map<Long, Record> byId = new LinkedHashMap<>();
        for (Record r : dbRecords) {
            byId.put(r.id, r);
        }

        List<Note> notes = scan(dir);
        Map<Long, Note> noteById = new LinkedHashMap<>();
        List<Note> freshNotes = new ArrayList<>();
        for (Note n : notes) {
            if (!MARK_RECORD.equals(n.mark)) {
                continue;
            }
            if (n.id > 0 && byId.containsKey(n.id)) {
                noteById.put(n.id, n);
            } else {
                freshNotes.add(n);
            }
        }

        // 逐条判定：库内被编辑过 → 回写数据库；否则数据库更新 → 覆盖笔记。
        // Obsidian 改笔记只更新文件 mtime，不会改 frontmatter 的 updated，
        // 因此以「文件 mtime 明显晚于笔记自带的 updated 时间戳」判定库内编辑。
        for (Record r : dbRecords) {
            Note n = noteById.get(r.id);
            if (n == null) {
                if (push) {
                    Files.createDirectories(dir);
                    Path f = targetFile(dir, recMan, r.id, r.title, "record-" + r.id);
                    writeRecord(f, r);
                    recMan.put(String.valueOf(r.id), f.getFileName().toString());
                    rep.recExport++;
                    dirty = true;
                    note(rep, "导出记录 #" + r.id + " → " + rel(f));
                }
                continue;
            }
            if (pull && noteEditedInVault(n)) {
                applyNoteToRecord(n, r);
                dao.update(r);
                writeRecord(n.file, r);
                rep.recUpdate++;
                note(rep, "回写记录 #" + r.id + " ← " + rel(n.file));
            } else if (push && cmp(r.updatedAt, n.updated) > 0) {
                writeRecord(n.file, r);
                rep.recUpdate++;
                note(rep, "更新笔记 #" + r.id + " → " + rel(n.file));
            }
        }

        if (pull) {
            Files.createDirectories(dir);
            for (Note n : freshNotes) {
                Record r = new Record();
                applyNoteToRecord(n, r);
                if (r.title.isEmpty()) {
                    r.title = stripExt(n.file.getFileName().toString());
                }
                if (r.date.isEmpty()) {
                    r.date = LocalDate.now().toString();
                }
                long id = dao.insert(r);
                n.id = id;
                writeRecord(n.file, r);
                recMan.put(String.valueOf(id), n.file.getFileName().toString());
                rep.recImport++;
                dirty = true;
                note(rep, "导入新记录 → #" + id + " " + r.title + "（" + rel(n.file) + "）");
            }
        }

        if (dirty) {
            manifest.put("records", recMan);
            saveManifest(manifest);
        }
    }

    private void applyNoteToRecord(Note n, Record r) {
        r.title = firstNonEmpty(Json.str(n.fm.get("title"), ""), firstHeading(n.body), r.title);
        r.type = firstNonEmpty(Json.str(n.fm.get("type"), ""), r.type);
        r.date = firstNonEmpty(Json.str(n.fm.get("date"), ""), r.date);
        Object rv = n.fm.get("result");
        if (rv instanceof Number) {
            r.resultValue = ((Number) rv).doubleValue();
        } else if (rv instanceof String && !((String) rv).isEmpty()) {
            try {
                r.resultValue = Double.parseDouble(((String) rv).trim());
            } catch (NumberFormatException ignored) {
                // 非数值结果，保留原值
            }
        }
        r.tags = joinTags(n.fm.get("tags"));
        String purpose = section(n.body, "实验目的");
        String data = section(n.body, "原始数据");
        String conclusion = section(n.body, "结论");
        if (purpose.isEmpty() && data.isEmpty() && conclusion.isEmpty()) {
            r.dataText = stripTitleLine(n.body);
        } else {
            r.purpose = purpose;
            r.dataText = data;
            r.conclusion = conclusion;
        }
    }

    private void writeRecord(Path f, Record r) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("chemlab: record\n");
        sb.append("id: ").append(r.id).append('\n');
        sb.append("title: ").append(yaml(r.title)).append('\n');
        sb.append("type: ").append(yaml(r.type)).append('\n');
        sb.append("date: ").append(yaml(r.date)).append('\n');
        if (r.resultValue != null) {
            sb.append("result: ").append(fmt(r.resultValue)).append('\n');
        }
        List<String> tags = splitTags(r.tags);
        if (!tags.isEmpty()) {
            sb.append("tags:\n");
            for (String t : tags) {
                sb.append("  - ").append(yaml(t)).append('\n');
            }
        }
        sb.append("created: ").append(yaml(r.createdAt)).append('\n');
        sb.append("updated: ").append(yaml(effectiveUpdated(r.updatedAt))).append('\n');
        sb.append("---\n\n");

        sb.append("# ").append(r.title.isEmpty() ? "未命名实验" : r.title).append("\n\n");
        sb.append("**实验类型**：").append(Record.labelOf(r.type));
        if (!r.date.isEmpty()) {
            sb.append("　｜　**日期**：").append(r.date);
        }
        if (r.resultValue != null) {
            sb.append("　｜　**结果值**：").append(fmt(r.resultValue));
        }
        sb.append("\n\n## 实验目的\n\n").append(blank(r.purpose));
        sb.append("\n\n## 原始数据\n\n").append(blank(r.dataText));
        sb.append("\n\n## 结论\n\n").append(blank(r.conclusion));
        if (!r.tags.isEmpty()) {
            sb.append("\n\n## 标签\n\n");
            for (String t : tags) {
                sb.append("#").append(t.replace(' ', '-')).append(' ');
            }
        }
        sb.append('\n');
        String stamp = effectiveUpdated(r.updatedAt);
        write(f, sb.toString());
        // 让文件 mtime 与笔记自带的时间戳一致：此后若库内被编辑，mtime 会晚于该戳，
        // 从而被 noteEditedInVault 识别为「库内编辑」。
        setFileTime(f, stamp);
    }

    // ------------------------------------------------------------------
    // 标定曲线
    // ------------------------------------------------------------------

    private void syncCalibrations(CalibrationDao dao, SyncReport rep, boolean push, boolean pull)
            throws IOException, java.sql.SQLException {
        Path dir = root().resolve(DIR_CALIB);
        Map<String, Object> manifest = loadManifest();
        Map<String, Object> calMan = Json.asObject(manifest.get("calibrations"));
        boolean dirty = false;

        List<Calibration> list = dao.listAll();
        Set<Long> ids = new HashSet<>();
        for (Calibration c : list) {
            ids.add(c.id);
        }

        if (pull) {
            Files.createDirectories(dir);
            for (Note n : scan(dir)) {
                if (!MARK_CALIB.equals(n.mark)) {
                    continue;
                }
                if (n.id > 0 && ids.contains(n.id)) {
                    continue;
                }
                Calibration c = noteToCalibration(n);
                long id = dao.insert(c);
                n.id = id;
                writeCalibration(n.file, c);
                calMan.put(String.valueOf(id), n.file.getFileName().toString());
                rep.calImport++;
                dirty = true;
                note(rep, "导入新标定曲线 → #" + id + " " + c.name + "（" + rel(n.file) + "）");
            }
        }

        if (push) {
            Files.createDirectories(dir);
            for (Calibration c : list) {
                String fn = Json.str(calMan.get(String.valueOf(c.id)), "");
                Path f = fn.isEmpty()
                        ? dir.resolve(safeName(c.name, "calib-" + c.id) + ".md")
                        : dir.resolve(fn);
                if (!Files.exists(f)) {
                    writeCalibration(f, c);
                    calMan.put(String.valueOf(c.id), f.getFileName().toString());
                    rep.calExport++;
                    dirty = true;
                    note(rep, "导出标定曲线 #" + c.id + " → " + rel(f));
                } else {
                    calMan.put(String.valueOf(c.id), f.getFileName().toString());
                }
            }
        }

        if (dirty) {
            manifest.put("calibrations", calMan);
            saveManifest(manifest);
        }
    }

    private Calibration noteToCalibration(Note n) {
        Calibration c = new Calibration();
        c.name = firstNonEmpty(Json.str(n.fm.get("name"), ""), firstHeading(n.body),
                stripExt(n.file.getFileName().toString()));
        c.xJson = numberArrayJson(n.fm.get("x"));
        c.yJson = numberArrayJson(n.fm.get("y"));
        c.slope = Json.num(n.fm.get("slope"), 0);
        c.intercept = Json.num(n.fm.get("intercept"), 0);
        c.r2 = Json.num(n.fm.get("r2"), 0);
        c.syx = Json.num(n.fm.get("syx"), 0);
        c.lod = Json.num(n.fm.get("lod"), 0);
        c.loq = Json.num(n.fm.get("loq"), 0);
        c.createdAt = Json.str(n.fm.get("created"), "");
        return c;
    }

    private void writeCalibration(Path f, Calibration c) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("chemlab: calibration\n");
        sb.append("id: ").append(c.id).append('\n');
        sb.append("name: ").append(yaml(c.name)).append('\n');
        sb.append("slope: ").append(fmt(c.slope)).append('\n');
        sb.append("intercept: ").append(fmt(c.intercept)).append('\n');
        sb.append("r2: ").append(fmt(c.r2)).append('\n');
        sb.append("syx: ").append(fmt(c.syx)).append('\n');
        sb.append("lod: ").append(fmt(c.lod)).append('\n');
        sb.append("loq: ").append(fmt(c.loq)).append('\n');
        sb.append("x: ").append(compactArray(c.xJson)).append('\n');
        sb.append("y: ").append(compactArray(c.yJson)).append('\n');
        sb.append("created: ").append(yaml(c.createdAt)).append('\n');
        sb.append("---\n\n");

        sb.append("# ").append(c.name.isEmpty() ? "未命名标定曲线" : c.name).append("\n\n");
        sb.append("**回归方程**：y = ").append(fmt(c.slope)).append("x + ").append(fmt(c.intercept))
                .append("　｜　**R²** = ").append(fmt(c.r2)).append("\n\n");
        sb.append("**S(y/x)** = ").append(fmt(c.syx))
                .append("　｜　**LOD** = ").append(fmt(c.lod))
                .append("　｜　**LOQ** = ").append(fmt(c.loq)).append("\n\n");

        double[] xs = parseArray(c.xJson);
        double[] ys = parseArray(c.yJson);
        int n = Math.min(xs.length, ys.length);
        if (n > 0) {
            sb.append("## 数据点\n\n");
            sb.append("| x | y |\n| --- | --- |\n");
            for (int i = 0; i < n; i++) {
                sb.append("| ").append(fmt(xs[i])).append(" | ").append(fmt(ys[i])).append(" |\n");
            }
            sb.append('\n');
        }
        sb.append("> 数据点以 frontmatter 中的 `x`/`y` 数组为准；上表仅为阅读展示。\n");
        write(f, sb.toString());
    }

    // ------------------------------------------------------------------
    // 知识库导出（单向）
    // ------------------------------------------------------------------

    private void exportKnowledge(SyncReport rep) throws IOException {
        Path idx = AppPaths.kbIndex();
        if (idx == null) {
            note(rep, "未找到知识库索引 resources/kb/index.json，跳过知识库导出");
            return;
        }
        Map<String, Object> rootJ = Json.asObject(
                Json.parse(new String(Files.readAllBytes(idx), StandardCharsets.UTF_8)));

        Map<String, Map<String, Object>> bookById = new LinkedHashMap<>();
        for (Object o : Json.asArray(rootJ.get("books"))) {
            Map<String, Object> b = Json.asObject(o);
            bookById.put(Json.str(b.get("id"), ""), b);
        }

        List<Map<String, Object>> chapters = new ArrayList<>();
        for (Object o : Json.asArray(rootJ.get("ch"))) {
            chapters.add(Json.asObject(o));
        }

        Path dir = root().resolve(DIR_KB);
        Files.createDirectories(dir);

        Map<String, List<String>> chaptersOfBook = new LinkedHashMap<>();
        Set<String> usedNames = new HashSet<>();
        int seq = 0;
        for (Map<String, Object> c : chapters) {
            String bookId = Json.str(c.get("book"), "");
            String chId = Json.str(c.get("id"), "");
            String title = Json.str(c.get("t"), "");
            int page = Json.intOf(c.get("p"), 0);
            Map<String, Object> book = bookById.get(bookId);
            if (book == null) {
                continue;
            }
            seq++;
            String body = chapterBody(book, chapters, page);
            // 章节 id 可能含 ":" 等非法文件名字符，统一清洗后作为笔记名与 wikilink 目标
            String noteName = safeName(bookId + "-" + chId, bookId + "-ch" + seq) + ".md";
            if (!usedNames.add(noteName)) {
                noteName = safeName(bookId + "-" + chId, bookId + "-ch" + seq) + "-" + seq + ".md";
                usedNames.add(noteName);
            }
            try {
                writeKbNote(dir.resolve(noteName), book, chId, title, page, body);
            } catch (IOException e) {
                rep.errors++;
                note(rep, "跳过章节笔记 " + noteName + "：" + e.getMessage());
                continue;
            }
            chaptersOfBook.computeIfAbsent(bookId, k -> new ArrayList<>()).add(noteName);
            rep.kbNotes++;
        }

        for (Map.Entry<String, List<String>> e : chaptersOfBook.entrySet()) {
            Map<String, Object> book = bookById.get(e.getKey());
            writeKbBookIndex(dir, e.getKey(), book, e.getValue());
            rep.kbNotes++;
        }
        note(rep, "导出知识库参考 " + chapters.size() + " 章 → " + rel(dir));
    }

    private String chapterBody(Map<String, Object> book, List<Map<String, Object>> chapters, int page) {
        String mode = Json.str(book.get("mode"), "");
        String bookId = Json.str(book.get("id"), "");
        StringBuilder sb = new StringBuilder();

        if ("fulltext".equals(mode)) {
            Path txt = AppPaths.kbText(Json.str(book.get("file"), ""));
            if (txt == null) {
                return "（全文文件缺失）";
            }
            try {
                String[] pages = new String(Files.readAllBytes(txt), StandardCharsets.UTF_8).split("\f", -1);
                int end = pages.length;
                for (Map<String, Object> c : chapters) {
                    if (bookId.equals(Json.str(c.get("book"), ""))) {
                        int p = Json.intOf(c.get("p"), 0);
                        if (p > page && p < end) {
                            end = p;
                        }
                    }
                }
                for (int p = Math.max(1, page); p <= end; p++) {
                    if (p - 1 < pages.length) {
                        sb.append(pages[p - 1].trim()).append("\n\n");
                    }
                    if (sb.length() >= KB_NOTE_LIMIT) {
                        sb.append("…（本章内容超出笔记上限，已截断，完整内容见软件知识库）\n");
                        break;
                    }
                }
            } catch (IOException e) {
                return "（全文读取失败：" + e.getMessage() + "）";
            }
        } else {
            for (Object o : Json.asArray(book.get("entries"))) {
                Map<String, Object> en = Json.asObject(o);
                sb.append("### ").append(Json.str(en.get("s"), "")).append('\n');
                sb.append("> 页码 p.").append(Json.intOf(en.get("p"), 0)).append("\n\n");
                sb.append(Json.str(en.get("t"), "")).append("\n\n");
            }
            if (sb.length() == 0) {
                sb.append("（本手册条目已在软件内按章节浏览，此处不重复导出）\n");
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? "（本章无可提取文本）" : s;
    }

    private void writeKbNote(Path f, Map<String, Object> book, String chId, String title,
                             int page, String body) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("chemlab: kb\n");
        sb.append("book: ").append(yaml(Json.str(book.get("id"), ""))).append('\n');
        sb.append("chapter: ").append(yaml(chId)).append('\n');
        sb.append("page: ").append(page).append('\n');
        sb.append("source: ").append(yaml(Json.str(book.get("title"), ""))).append('\n');
        sb.append("tags:\n  - 知识库\n  - ").append(Json.str(book.get("id"), "")).append('\n');
        sb.append("---\n\n");
        sb.append("# ").append(Json.str(book.get("title"), "")).append(" — ").append(title).append("\n\n");
        sb.append("> 起始页 p.").append(page).append("　｜　书目：")
                .append(Json.str(book.get("title"), "")).append("\n\n");
        sb.append(body).append('\n');
        write(f, sb.toString());
    }

    private void writeKbBookIndex(Path dir, String bookId, Map<String, Object> book,
                                  List<String> noteNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("---\nchemlab: kb-index\nbook: ").append(yaml(bookId))
                .append("\ntags:\n  - 知识库\n---\n\n");
        sb.append("# ").append(Json.str(book.get("title"), "")).append("\n\n");
        sb.append("> ").append(Json.str(book.get("author"), ""))
                .append("　｜　共 ").append(noteNames.size()).append(" 章\n\n");
        for (String name : noteNames) {
            sb.append("- [[").append(stripExt(name)).append("]]\n");
        }
        sb.append('\n');
        write(dir.resolve(bookId + "-目录.md"), sb.toString());
    }

    // ------------------------------------------------------------------
    // 总索引（MOC）
    // ------------------------------------------------------------------

    private void writeMoc(SyncReport rep) throws IOException {
        Path f = root().resolve("ChemLab 索引.md");
        StringBuilder sb = new StringBuilder();
        sb.append("---\nchemlab: moc\n---\n\n");
        sb.append("# ChemLab 索引\n\n");
        sb.append("本笔记由 ChemLab Analyzer 自动生成，用于串联库内各模块。\n\n");
        sb.append("## 模块\n\n");
        sb.append("- [[").append(DIR_RECORDS).append("]]（实验记录笔记目录）\n");
        sb.append("- [[").append(DIR_CALIB).append("]]（标定曲线笔记目录）\n");
        sb.append("- [[").append(DIR_KB).append("]]（知识库参考目录）\n\n");
        sb.append("## 知识库书目\n\n");
        for (Path p : listMarkdown(root().resolve(DIR_KB))) {
            String n = stripExt(p.getFileName().toString());
            if (n.endsWith("-目录")) {
                sb.append("- [[").append(n).append("]]\n");
            }
        }
        sb.append('\n');
        write(f, sb.toString());
    }

    // ------------------------------------------------------------------
    // 笔记模型与解析
    // ------------------------------------------------------------------

    private static final String MARK_RECORD = "record";
    private static final String MARK_CALIB = "calibration";

    private static final class Note {
        Path file;
        String mark = "";
        long id;
        String updated = "";
        Map<String, Object> fm = new LinkedHashMap<>();
        String body = "";
    }

    private List<Note> scan(Path dir) throws IOException {
        List<Note> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        for (Path p : listMarkdown(dir)) {
            try {
                out.add(parseNote(p));
            } catch (IOException | RuntimeException e) {
                note(null, "跳过无法解析的笔记 " + p.getFileName() + "：" + e.getMessage());
            }
        }
        return out;
    }

    private static List<Path> listMarkdown(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.md")) {
            for (Path p : ds) {
                out.add(p);
            }
        }
        return out;
    }

    private static Note parseNote(Path f) throws IOException {
        Note n = new Note();
        n.file = f;
        String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) {
                int firstNl = text.indexOf('\n');
                n.fm = parseYaml(text.substring(firstNl + 1, end));
                int bodyStart = text.indexOf('\n', end + 1);
                n.body = bodyStart > 0 ? text.substring(bodyStart + 1) : "";
            } else {
                n.body = text;
            }
        } else {
            n.body = text;
        }
        n.mark = Json.str(n.fm.get("chemlab"), "");
        n.id = (long) Json.num(n.fm.get("id"), 0);
        n.updated = Json.str(n.fm.get("updated"), "");
        return n;
    }

    private static Map<String, Object> parseYaml(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        String curKey = null;
        List<String> curList = null;
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("- ") || t.equals("-")) {
                if (curKey != null) {
                    if (curList == null) {
                        curList = new ArrayList<>();
                        m.put(curKey, curList);
                    }
                    curList.add(unquote(t.length() > 2 ? t.substring(2).trim() : ""));
                }
                continue;
            }
            int c = line.indexOf(':');
            if (c < 0) {
                continue;
            }
            String key = line.substring(0, c).trim();
            String val = line.substring(c + 1).trim();
            if (val.isEmpty()) {
                curKey = key;
                curList = null;
                m.put(key, "");
            } else {
                curKey = null;
                curList = null;
                m.put(key, parseScalar(val));
            }
        }
        return m;
    }

    private static Object parseScalar(String v) {
        if (v.startsWith("[") && v.endsWith("]")) {
            List<String> l = new ArrayList<>();
            String inner = v.substring(1, v.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (String p : inner.split(",")) {
                    l.add(unquote(p.trim()));
                }
            }
            return l;
        }
        return unquote(v);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    private static String section(String body, String heading) {
        String marker = "## " + heading;
        int at = body.indexOf(marker);
        if (at < 0) {
            return "";
        }
        int start = body.indexOf('\n', at);
        if (start < 0) {
            return "";
        }
        int next = body.indexOf("\n## ", start);
        String s = next < 0 ? body.substring(start) : body.substring(start, next);
        return s.trim();
    }

    private static String firstHeading(String body) {
        for (String line : body.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("# ")) {
                return t.substring(2).trim();
            }
        }
        return "";
    }

    private static String stripTitleLine(String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("# ") || t.startsWith("**实验类型**")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /** 判定笔记是否在 Obsidian 侧被编辑：文件 mtime 明显晚于笔记自带的 updated 时间戳。 */
    private static boolean noteEditedInVault(Note n) {
        if (n.updated.isEmpty()) {
            return true;
        }
        String modified = fileTime(n.file);
        if (cmp(modified, n.updated) <= 0) {
            return false;
        }
        return secondsBetween(n.updated, modified) > EDIT_TOLERANCE_SECONDS;
    }

    private static long secondsBetween(String a, String b) {
        try {
            LocalDateTime ta = LocalDateTime.parse(a, TS);
            LocalDateTime tb = LocalDateTime.parse(b, TS);
            return Math.abs(java.time.Duration.between(ta, tb).getSeconds());
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    /** 记录侧时间戳：为空时回落到当前时间，保证导出后与库侧一致。 */
    private static String effectiveUpdated(String updated) {
        return updated == null || updated.isEmpty() ? nowTs() : updated;
    }

    private static String nowTs() {
        return LocalDateTime.now().format(TS);
    }

    private static void setFileTime(Path f, String ts) {
        try {
            LocalDateTime t = LocalDateTime.parse(ts, TS);
            Files.setLastModifiedTime(f,
                    FileTime.from(t.atZone(ZoneId.systemDefault()).toInstant()));
        } catch (RuntimeException | IOException ignored) {
            // 时间戳不可解析或文件不可写时忽略，仅影响编辑判定的精度
        }
    }

    private static String fileTime(Path f) {
        try {
            FileTime ft = Files.getLastModifiedTime(f);
            return LocalDateTime.ofInstant(ft.toInstant(), ZoneId.systemDefault()).format(TS);
        } catch (IOException e) {
            return "";
        }
    }

    private static int cmp(String a, String b) {
        return nullToEmpty(a).compareTo(nullToEmpty(b));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static String blank(String s) {
        return s == null || s.trim().isEmpty() ? "（未填写）" : s.trim();
    }

    private static List<String> splitTags(String tags) {
        List<String> out = new ArrayList<>();
        if (tags == null || tags.trim().isEmpty()) {
            return out;
        }
        for (String t : tags.split("[,，]")) {
            String v = t.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private static String joinTags(Object tags) {
        if (tags instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) tags) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(String.valueOf(o));
            }
            return sb.toString();
        }
        return Json.str(tags, "");
    }

    private static String fmt(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static String yaml(String s) {
        if (s == null) {
            s = "";
        }
        boolean needQuote = s.isEmpty()
                || s.startsWith(" ") || s.endsWith(" ")
                || s.matches(".*[:#\\[\\]{},&*!|>'\"%@`\\r\\n\\t].*")
                || s.matches("-?\\d+(\\.\\d+)?")
                || "true".equals(s) || "false".equals(s) || "null".equals(s) || "~".equals(s);
        if (!needQuote) {
            return s;
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String safeName(String title, String fallback) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        t = t.replaceAll("\\s+", " ");
        if (t.length() > 60) {
            t = t.substring(0, 60).trim();
        }
        return t.isEmpty() ? fallback : t;
    }

    private static String stripExt(String name) {
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private Path targetFile(Path dir, Map<String, Object> manifest, long id, String title,
                            String fallback) {
        String mapped = Json.str(manifest.get(String.valueOf(id)), "");
        if (!mapped.isEmpty()) {
            return dir.resolve(mapped);
        }
        Path f = dir.resolve(safeName(title, fallback) + ".md");
        if (Files.exists(f)) {
            return dir.resolve(fallback + ".md");
        }
        return f;
    }

    private String rel(Path p) {
        try {
            return vault.relativize(p).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString();
        }
    }

    private static void write(Path f, String content) throws IOException {
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String numberArrayJson(Object v) {
        if (v instanceof List) {
            List<Double> nums = new ArrayList<>();
            for (Object o : (List<?>) v) {
                nums.add(Json.num(o, 0));
            }
            return Json.write(nums);
        }
        String s = Json.str(v, "[]").trim();
        return s.isEmpty() ? "[]" : s;
    }

    private static double[] parseArray(String json) {
        try {
            List<Object> list = Json.asArray(Json.parse(json));
            double[] out = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = Json.num(list.get(i), 0);
            }
            return out;
        } catch (RuntimeException e) {
            return new double[0];
        }
    }

    private static String compactArray(String json) {
        try {
            List<Object> list = Json.asArray(Json.parse(json));
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(fmt(Json.num(list.get(i), 0)));
            }
            return sb.append(']').toString();
        } catch (RuntimeException e) {
            return "[]";
        }
    }

    private void note(SyncReport rep, String msg) {
        log.accept(msg);
        if (rep != null) {
            rep.lines.add(msg);
        }
    }

    // ------------------------------------------------------------------

    /** 同步结果统计与日志。 */
    public static class SyncReport {
        public int recExport;
        public int recUpdate;
        public int recImport;
        public int calExport;
        public int calImport;
        public int kbNotes;
        public int errors;
        public final List<String> lines = new ArrayList<>();

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("记录 导出").append(recExport)
                    .append("/更新").append(recUpdate)
                    .append("/导入").append(recImport);
            sb.append("，标定 导出").append(calExport).append("/导入").append(calImport);
            if (kbNotes > 0) {
                sb.append("，知识库笔记 ").append(kbNotes);
            }
            if (errors > 0) {
                sb.append("，错误 ").append(errors);
            }
            return sb.toString();
        }
    }
}