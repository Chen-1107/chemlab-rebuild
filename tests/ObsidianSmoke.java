import com.chemlab.data.CalibrationDao;
import com.chemlab.data.Database;
import com.chemlab.data.RecordDao;
import com.chemlab.model.Calibration;
import com.chemlab.model.Record;
import com.chemlab.service.ObsidianSync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Obsidian 双向同步离线自检。
 *
 * <p>覆盖：导出生成笔记、重复同步幂等、库内编辑回写数据库、手写新笔记导入、
 * 不产生重复记录、知识库单向导出。全部通过时打印 {@code OBSIDIAN OK}。
 */
public class ObsidianSmoke {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Path projectRoot = Path.of(args.length > 0 ? args[0] : ".");
        System.setProperty("chemlab.home", projectRoot.toString());

        Path tmp = Files.createTempDirectory("obsidian-smoke");
        Path vault = tmp.resolve("MyVault");
        Files.createDirectories(vault.resolve(".obsidian"));
        Path dbFile = tmp.resolve("test.db");

        try (Database db = Database.open(dbFile)) {
            RecordDao rd = new RecordDao(db);
            CalibrationDao cd = new CalibrationDao(db);

            Record r = new Record();
            r.title = "盐酸标定";
            r.type = "titration";
            r.date = "2026-10-11";
            r.purpose = "标定盐酸浓度";
            r.dataText = "0.1000 mol/L NaOH, V=25.00 mL";
            r.conclusion = "c(HCl)=0.1024 mol/L";
            r.tags = "标定,盐酸";
            r.resultValue = 0.1024;
            long rid = rd.insert(r);

            Calibration c = new Calibration();
            c.name = "邻二氮菲法";
            c.xJson = "[0.1,0.2,0.3]";
            c.yJson = "[0.023,0.045,0.067]";
            c.slope = 0.22;
            c.intercept = 0.001;
            c.r2 = 0.9999;
            c.syx = 0.0005;
            c.lod = 0.01;
            c.loq = 0.03;
            cd.insert(c);

            ObsidianSync engine = new ObsidianSync(vault, s -> System.out.println("  [log] " + s));

            // 1) 导出
            ObsidianSync.SyncReport rep1 = engine.exportAll(rd, cd, false);
            System.out.println("导出: " + rep1.summary());
            Path recDir = vault.resolve("ChemLab/实验记录");
            Path calDir = vault.resolve("ChemLab/标定曲线");
            check("记录笔记已生成", countMd(recDir) == 1);
            check("标定笔记已生成", countMd(calDir) == 1);
            Path recFile = firstMd(recDir);
            String recText = read(recFile);
            check("frontmatter 含 chemlab: record", recText.contains("chemlab: record"));
            check("frontmatter 含 id", recText.contains("id: " + rid));
            check("含标签列表", recText.contains("- 标定") && recText.contains("- 盐酸"));

            // 2) 幂等：再次双向同步不应新增笔记
            ObsidianSync.SyncReport rep2 = engine.syncAll(rd, cd, false);
            System.out.println("二次同步: " + rep2.summary());
            check("幂等：记录笔记仍为 1", countMd(recDir) == 1);
            check("幂等：无新增导出", rep2.recExport == 0 && rep2.recImport == 0);

            // 3) 库内编辑回写（正文变化、frontmatter 时间戳不变、mtime 后移）
            String edited = recText.replace("标定盐酸浓度", "标定盐酸浓度（Obsidian 修订）");
            Files.write(recFile, edited.getBytes(StandardCharsets.UTF_8));
            Files.setLastModifiedTime(recFile, FileTime.from(Instant.now().plusSeconds(120)));
            ObsidianSync.SyncReport rep3 = engine.importAll(rd, cd);
            System.out.println("导入(库内编辑): " + rep3.summary());
            Record after = rd.listAll().get(0);
            check("回写生效：目的含修订", after.purpose.contains("Obsidian 修订"));

            // 3b) 紧接着再同步：不应再次回写
            ObsidianSync.SyncReport rep3b = engine.syncAll(rd, cd, false);
            check("回写后稳定：无重复更新", rep3b.recUpdate == 0);

            // 4) 全新手写笔记 → 新增记录
            String newNote = "---\n"
                    + "chemlab: record\n"
                    + "title: \"NaOH 标定\"\n"
                    + "type: titration\n"
                    + "date: 2026-10-12\n"
                    + "tags:\n  - 标定\n"
                    + "---\n\n"
                    + "# NaOH 标定\n\n"
                    + "## 实验目的\n\n在 Obsidian 中新建的实验\n\n"
                    + "## 原始数据\n\nV=24.98 mL\n\n"
                    + "## 结论\n\nc=0.1001\n";
            Files.write(recDir.resolve("obsidian-new.md"), newNote.getBytes(StandardCharsets.UTF_8));
            ObsidianSync.SyncReport rep4 = engine.importAll(rd, cd);
            System.out.println("导入(新笔记): " + rep4.summary());
            List<Record> all = rd.listAll();
            check("新笔记导入成功", all.stream().anyMatch(x -> "NaOH 标定".equals(x.title)));
            Record imported = all.stream().filter(x -> "NaOH 标定".equals(x.title)).findFirst().get();
            check("新记录目的正确", "在 Obsidian 中新建的实验".equals(imported.purpose));
            check("新记录已回填 id", imported.id > 0);

            // 5) 再次双向同步：不应产生重复记录
            ObsidianSync.SyncReport rep5 = engine.syncAll(rd, cd, false);
            System.out.println("三次同步: " + rep5.summary());
            check("无重复记录", rd.listAll().size() == 2);
            check("记录笔记数 = 2", countMd(recDir) == 2);

            // 6) 知识库导出
            ObsidianSync.SyncReport rep6 = engine.exportAll(rd, cd, true);
            System.out.println("知识库导出: " + rep6.summary());
            Path kbDir = vault.resolve("ChemLab/知识库");
            check("知识库笔记已生成", countMd(kbDir) > 30);
            check("总索引已生成", Files.exists(vault.resolve("ChemLab/ChemLab 索引.md")));
            check("书目目录已生成", Files.list(kbDir)
                    .anyMatch(p -> p.getFileName().toString().endsWith("-目录.md")));
        }

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        deleteTree(tmp);
        if (fail == 0) {
            System.out.println("OBSIDIAN OK");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static long countMd(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md")).count();
        }
    }

    private static Path firstMd(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> l = s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .collect(Collectors.toList());
            return l.get(0);
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响自检结论
                }
            });
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("[ OK ] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }
}