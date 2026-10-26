package com.chemlab.kb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ima 知识库后端：把 ima OpenAPI 的条目结构适配为统一的 {@link Hit}。
 *
 * <p>文件的格式解析、分块、向量化、召回全部由 ima 负责，ELN 侧只取用结果，
 * 因此不需要自研 OCR / PDF 解析。ima 不可达时 {@link #search} 返回空列表并记录
 * 原因，由 {@link HybridBackend} 汇总为状态提示，不影响本地知识库。
 *
 * <p>{@link #available()} 只反映「凭据是否齐备」而不绑定最近一次连通：来源下拉项
 * 不应因一次网络抖动而消失，连通性失败在检索时逐次降级并提示。
 */
public final class ImaKbBackend implements KnowledgeBackend {

    private final ImaConfig cfg;
    private final ImaClient client;

    private volatile boolean connected;
    private volatile String lastError = "";

    public ImaKbBackend(ImaConfig cfg) {
        this.cfg = cfg;
        this.client = new ImaClient(cfg);
    }

    @Override
    public String id() {
        return "ima";
    }

    @Override
    public String name() {
        return "ima 知识库";
    }

    @Override
    public boolean available() {
        return cfg.configured();
    }

    public ImaConfig config() {
        return cfg;
    }

    /** 最近一次调用是否成功（配置页状态显示用）。 */
    public boolean connected() {
        return connected;
    }

    /** 最近一次失败原因；无失败时为空串。 */
    public String lastError() {
        return lastError;
    }

    public void clearError() {
        lastError = "";
    }

    /**
     * 连接测试：列出账号可见的知识库，成功即置为已连通。
     *
     * <p>用 {@code search_knowledge_base}（传空 query 即列出全部）而非
     * {@code get_addable_knowledge_base_list}：后者只覆盖「有写入权限」的知识库，
     * 权限面更窄，凭据有效也可能返回参数非法；前者只要凭据有效即 {@code code=0}，
     * 并顺带返回清单供界面选择检索范围。
     */
    public List<Map<String, Object>> testConnection() throws ImaClient.ImaException {
        List<Map<String, Object>> kbs = client.searchKnowledgeBases("", 20);
        connected = true;
        lastError = "";
        return kbs;
    }

    // ------------------------------------------------------------------

    @Override
    public List<Hit> search(String query, int limit) {
        List<Hit> out = new ArrayList<>();
        if (!available() || query == null || query.trim().isEmpty()) {
            return out;
        }
        if (cfg.knowledgeBaseId == null || cfg.knowledgeBaseId.isEmpty()) {
            connected = false;
            lastError = "尚未选择 ima 知识库：请到「ima 知识库」页测试连接并选定默认知识库"
                    + "（该接口不支持一次跨库检索）";
            return out;
        }
        List<Map<String, Object>> items;
        try {
            items = client.searchKnowledge(query.trim(), cfg.knowledgeBaseId);
            connected = true;
            lastError = "";
        } catch (ImaClient.ImaException e) {
            connected = false;
            lastError = e.getMessage();
            return out;
        }

        for (Map<String, Object> it : items) {
            Hit h = new Hit();
            h.source = "ima";
            h.title = firstOf(it, "title", "name", "file_name", "knowledge_name",
                    "media_name", "doc_name");
            h.body = firstOf(it, "highlight_content", "content", "text", "snippet",
                    "summary", "highlight", "description");
            h.kbName = firstNonEmpty(firstOf(it, "knowledge_base_name", "kb_name"),
                    cfg.knowledgeBaseName);
            h.snippet = flatten(h.body);
            h.score = num(firstOf(it, "score", "similarity", "relevance", "rank_score"));
            String mediaId = firstOf(it, "media_id", "knowledge_id", "content_id", "id");
            h.ref = cfg.knowledgeBaseId + "|" + mediaId;
            h.readable = !h.body.isEmpty();
            out.add(h);
        }

        if (limit > 0 && out.size() > limit) {
            out = new ArrayList<>(out.subList(0, limit));
        }
        normalizeByRank(out);
        return out;
    }

    /** 接口未返回分数时按名次给分，保证与本地结果融合时顺序可用。 */
    private static void normalizeByRank(List<Hit> hits) {
        double max = 0;
        for (Hit h : hits) {
            max = Math.max(max, h.score);
        }
        if (max > 0) {
            for (Hit h : hits) {
                h.score = h.score / max;
            }
            return;
        }
        int n = hits.size();
        for (int i = 0; i < n; i++) {
            hits.get(i).score = (double) (n - i) / n;
        }
    }

    @Override
    public String readText(Hit hit) {
        if (hit == null) {
            return null;
        }
        if (hit.body != null && !hit.body.isEmpty()) {
            return hit.body;
        }
        return hit.snippet;
    }

    private static String flatten(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private static String firstOf(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof String && !((String) v).isEmpty()) {
                return (String) v;
            }
            if (v instanceof Number) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    private static double num(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
