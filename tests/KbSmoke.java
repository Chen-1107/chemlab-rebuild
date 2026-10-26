import com.chemlab.kb.Hit;
import com.chemlab.kb.HybridBackend;
import com.chemlab.kb.ImaConfig;
import com.chemlab.kb.ImaKbBackend;
import com.chemlab.kb.LocalKbBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 知识库后端离线自检。
 *
 * <p>覆盖：本地索引加载、子串检索与分数归一、命中项正文回读、书目限定、
 * 凭据脱敏与持久化往返、ima 未配置时的降级、多来源融合的权重与排序稳定性。
 * 全程不发起任何网络请求（ima 未配置时 {@code search} 直接短路返回）。
 * 全部通过时打印 {@code KB OK}。
 */
public class KbSmoke {

    private static int pass;
    private static int fail;

    private static final String KW = "chromatograph";

    public static void main(String[] args) throws Exception {
        Path projectRoot = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        System.setProperty("chemlab.home", projectRoot.toString());

        // ---- 1. 本地后端加载 ------------------------------------------
        LocalKbBackend local = new LocalKbBackend();
        check("本地索引可加载", local.available());
        check("书目数 >= 4", local.books().size() >= 4);
        check("总页数 > 0", local.totalPages() > 0);
        check("章节数 > 0", !local.chapters().isEmpty());
        check("内联条目数 > 0", !local.deanEntries().isEmpty());

        // ---- 2. 本地检索 ----------------------------------------------
        List<Hit> hits = local.search(KW, 5, null);
        check("本地检索有结果", !hits.isEmpty());
        check("结果数不超过 limit", hits.size() <= 5);

        boolean allLocal = true;
        boolean allScored = true;
        boolean descending = true;
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            allLocal &= h.isLocal() && "local".equals(h.source);
            allScored &= h.score > 0 && h.score <= 1.0 + 1e-9;
            if (i > 0) {
                descending &= hits.get(i - 1).score >= h.score - 1e-9;
            }
        }
        check("来源均为 local", allLocal);
        check("分数归一化到 (0,1]", allScored);
        check("结果按分数降序", descending);
        check("首位分数归一到 1.0", Math.abs(hits.get(0).score - 1.0) < 1e-9);
        check("列表文本含本地标签", hits.get(0).label().startsWith("[本地]"));

        // ---- 3. 书目限定与正文回读 --------------------------------------
        List<Hit> lange = local.search(KW, 50, "lange");
        check("书目限定有结果", !lange.isEmpty());
        boolean scopeOk = true;
        for (Hit h : lange) {
            scopeOk &= h.ref.startsWith("lange|");
        }
        check("限定结果仅来自指定书目", scopeOk);

        Hit fulltext = lange.isEmpty() ? null : lange.get(0);
        check("命中项含全文书页级结果", fulltext != null);
        if (fulltext != null) {
            check("页级 ref 格式正确", fulltext.ref.matches("lange\\|[0-9]+"));
            check("页号为正整数", fulltext.page > 0);
            String body = local.readText(fulltext);
            check("页级正文可回读", body != null && !body.isEmpty());
            check("页级正文含关键词", body != null && body.toLowerCase().contains(KW));
        }

        List<Hit> deans = local.search(KW, 50, "deans");
        check("非全文书目限定有结果", !deans.isEmpty());
        boolean inlineOk = true;
        for (Hit h : deans) {
            inlineOk &= h.ref.contains("|e|");
        }
        check("非全文书目命中内联条目", inlineOk);

        Hit inline = deans.isEmpty() ? null : deans.get(0);
        check("命中项含内联条目结果", inline != null);
        if (inline != null) {
            String body = local.readText(inline);
            check("内联条目正文可回读", body != null && !body.isEmpty());
        }
        check("非法 ref 返回 null", local.readText(new Hit()) == null);

        // ---- 5. 边界 --------------------------------------------------
        check("空查询返回空列表", local.search("   ", 10, null).isEmpty());
        check("无匹配词返回空列表", local.search("zzzqqqxxx", 10, null).isEmpty());
        check("limit 生效", local.search(KW, 2, null).size() <= 2);

        // ---- 6. 凭据脱敏 ----------------------------------------------
        check("空凭据脱敏为空串", ImaConfig.mask("").isEmpty());
        check("短凭据整体脱敏", "****".equals(ImaConfig.mask("ab")));
        check("长凭据仅留末四位", "****efgh".equals(ImaConfig.mask("key-abcdefgh")));
        check("null 脱敏不抛异常", ImaConfig.mask(null).isEmpty());

        ImaConfig blank = new ImaConfig();
        check("空配置未就绪", !blank.configured());
        blank.clientId = "cid";
        check("仅 clientId 未就绪", !blank.configured());
        blank.apiKey = "key";
        check("凭据齐备即就绪", blank.configured());

        // ---- 7. 凭据持久化往返（隔离到临时工作根） ---------------------
        Path tmp = Files.createTempDirectory("kb-smoke");
        try {
            System.setProperty("chemlab.home", tmp.toString());
            ImaConfig cfg = new ImaConfig();
            cfg.clientId = "cid-1234567890";
            cfg.apiKey = "key-abcdefgh";
            cfg.knowledgeBaseId = "kb-001";
            cfg.knowledgeBaseName = "我的分析化学知识库";
            cfg.folderId = "folder-9";
            cfg.timeoutMs = 5000;
            cfg.autoSync = true;
            cfg.save();

            ImaConfig back = ImaConfig.load();
            check("配置文件已落盘", Files.isRegularFile(tmp.resolve("data").resolve("ima.json")));
            // 环境变量优先级高于配置文件，存在时跳过凭据字段往返断言
            boolean envOverride = notBlank(System.getenv(ImaConfig.ENV_CLIENT_ID))
                    || notBlank(System.getenv(ImaConfig.ENV_API_KEY));
            if (envOverride) {
                System.out.println("  [note] 检测到 ima 环境变量，跳过凭据字段往返断言");
            } else {
                check("clientId 往返一致", "cid-1234567890".equals(back.clientId));
                check("apiKey 往返一致", "key-abcdefgh".equals(back.apiKey));
            }
            check("知识库 id 往返一致", "kb-001".equals(back.knowledgeBaseId));
            check("知识库名往返一致", "我的分析化学知识库".equals(back.knowledgeBaseName));
            check("目录 id 往返一致", "folder-9".equals(back.folderId));
            check("超时往返一致", back.timeoutMs == 5000);
            check("自动同步开关往返一致", back.autoSync);
            check("往返后凭据仍就绪", back.configured());

            // 损坏配置应静默退回默认值
            Files.write(tmp.resolve("data").resolve("ima.json"),
                    "{ not json".getBytes("UTF-8"));
            ImaConfig broken = ImaConfig.load();
            check("损坏配置退回默认值", broken.timeoutMs == ImaConfig.DEFAULT_TIMEOUT_MS);
            check("损坏配置视为未就绪", !broken.configured());
        } finally {
            System.setProperty("chemlab.home", projectRoot.toString());
            deleteTree(tmp);
        }

        // ---- 8. ima 未配置时的降级 ------------------------------------
        ImaConfig empty = new ImaConfig();
        ImaKbBackend ima = new ImaKbBackend(empty);
        check("ima 后端未就绪", !ima.available());
        check("ima 空凭据检索不抛异常", ima.search(KW, 5).isEmpty());
        check("未配置时不产生错误信息", ima.lastError().isEmpty());
        check("ima 后端标识正确", "ima".equals(ima.id()) && "local".equals(local.id()));

        // ---- 9. 多来源融合与降级 --------------------------------------
        HybridBackend hybrid = new HybridBackend(local, ima);
        check("未配置时 imaConfigured 为 false", !hybrid.imaConfigured());

        List<Hit> localOnly = hybrid.search(HybridBackend.SOURCE_LOCAL, KW, 5, null);
        List<Hit> allSources = hybrid.search(HybridBackend.SOURCE_ALL, KW, 5, null);
        check("local 来源有结果", !localOnly.isEmpty());
        check("all 来源含本地结果", !allSources.isEmpty());
        check("all 与 local 结果数一致（ima 未配置）", allSources.size() == localOnly.size());
        check("未配置时不产生降级提示", hybrid.lastNote().isEmpty());

        double top = allSources.get(0).score;
        check("融合后按 0.4 权重缩放", Math.abs(top - 0.4) < 1e-9);

        boolean sameOrder = true;
        for (int i = 0; i < allSources.size(); i++) {
            sameOrder &= allSources.get(i).ref.equals(localOnly.get(i).ref);
        }
        check("缩放不改变来源内顺序", sameOrder);

        List<Hit> imaOnly = hybrid.search(HybridBackend.SOURCE_IMA, KW, 5, null);
        check("ima 来源未配置时返回空", imaOnly.isEmpty());
        check("ima 来源未配置时给出提示",
                hybrid.lastNote().contains("尚未配置"));

        Hit first = allSources.get(0);
        check("融合层可回读本地正文", hybrid.readText(first) != null);
        check("融合层空命中返回 null", hybrid.readText(null) == null);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail == 0) {
            System.out.println("KB OK");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
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
