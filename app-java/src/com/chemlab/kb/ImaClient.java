package com.chemlab.kb;

import com.chemlab.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ima OpenAPI 客户端。
 *
 * <p>只使用 JDK 原生 {@link HttpURLConnection} 与项目自带的 {@link Json}，不引入任何
 * 第三方 HTTP / JSON 库，符合项目零第三方依赖的约定。所有请求为 {@code POST}，
 * 统一发往 {@code https://ima.qq.com/openapi/<module>/v1/<action>}，携带
 * {@code ima-openapi-clientid} 与 {@code ima-openapi-apikey} 两个头。
 *
 * <p>接口名与参数以官方 skill 包 {@code knowledge-base/references/api.md} 为准：
 * <ul>
 *   <li>{@code search_knowledge_base}：列/搜知识库，必填 {@code query/cursor/limit}（limit 1–20）。
 *       返回 {@code data.info_list}，条目字段实测为 {@code kb_id}/{@code kb_name}——
 *       官方文档记作 {@code id}/{@code name}，与线上实际返回不符，故取值时两者都试。</li>
 *   <li>{@code search_knowledge}：库内检索，必填 {@code query/cursor/knowledge_base_id}，
 *       缺 {@code knowledge_base_id} 会返回 {@code 220004}。返回 {@code data.info_list}，
 *       条目字段 {@code media_id}/{@code title}/{@code parent_folder_id}/{@code highlight_content}，
 *       其中 {@code highlight_content} 实测恒为空，因此 ima 只给出「资料在哪」。</li>
 *   <li>{@code get_media_info}：取原文访问链接，必填 {@code media_id}。返回
 *       {@code data.url_info.url} 与随行 header。</li>
 * </ul>
 *
 * <p>重试策略按官方错误码表区分：仅 {@code 110010}（下游网络错误）与
 * {@code 110013}（客户端取消/超时）以及传输层 IO 故障重试 1 次；参数类
 * （{@code 110001}/{@code 110002}/{@code 220004}）与权限类（{@code 110030}）不重试。
 */
public final class ImaClient {

    public static final String HOST = "https://ima.qq.com";

    /** 调用失败：区分网络故障与接口错误码，便于界面给出可操作提示。 */
    public static class ImaException extends Exception {
        private final int code;
        private final boolean retryable;

        public ImaException(String message) {
            this(message, 0, false);
        }

        /** 传输层故障（连接失败 / 超时 / 读取中断），可重试。 */
        public ImaException(String message, boolean retryable) {
            this(message, 0, retryable);
        }

        public ImaException(String message, int code) {
            this(message, code, code == 110010 || code == 110013);
        }

        private ImaException(String message, int code, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public int code() {
            return code;
        }

        public boolean isRetryable() {
            return retryable;
        }

        /** 凭据或权限问题：换凭据才能解决，重试无意义。 */
        public boolean isAuthError() {
            return code == 401 || code == 403 || code == 110030;
        }
    }

    private final ImaConfig cfg;

    public ImaClient(ImaConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------
    // 业务接口
    // ------------------------------------------------------------------

    /**
     * 列 / 搜知识库。传空 {@code query} 即列出当前账号可见的全部知识库。
     *
     * <p>这也是「连接测试」所用的接口：只要返回 {@code code=0} 即说明凭据有效，
     * 且顺带拿到知识库清单供界面选择检索范围。
     */
    public List<Map<String, Object>> searchKnowledgeBases(String query, int limit)
            throws ImaException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query == null ? "" : query);
        body.put("cursor", "");
        body.put("limit", clamp(limit, 1, 20));
        Map<String, Object> resp = call("wiki", "search_knowledge_base", body);
        return extractList(resp, "info_list", "knowledge_base_list",
                "knowledge_base_infos", "knowledge_bases", "list", "items");
    }

    /**
     * 库内检索。
     *
     * <p>{@code knowledge_base_id} 为必填——官方接口不支持一次跨库检索，缺参直接
     * 返回 {@code 220004}。应用侧每次只检索 {@link ImaConfig#knowledgeBaseId} 指定的
     * 那一个知识库。
     */
    public List<Map<String, Object>> searchKnowledge(String query, String kbId)
            throws ImaException {
        if (kbId == null || kbId.isEmpty()) {
            throw new ImaException("ima 检索需要指定知识库 ID（该接口不支持跨库检索）", 110001);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("cursor", "");
        body.put("knowledge_base_id", kbId);
        Map<String, Object> resp = call("wiki", "search_knowledge", body);
        return extractList(resp, "info_list", "knowledge_list",
                "knowledge_infos", "results", "list", "items");
    }

    /**
     * 取媒体访问信息（{@code data.url_info.url} + 随行 header），是「打开原文」的入口。
     *
     * <p>当前界面尚未接入：ima 检索只返回 {@code media_id}，需要此接口换出签名下载链接。
     * 实测笔记类（{@code media_type=11}）不返回 {@code url_info}，只能到 ima 客户端查看。
     */
    public Map<String, Object> mediaInfo(String mediaId) throws ImaException {
        if (mediaId == null || mediaId.isEmpty()) {
            throw new ImaException("media_id 不可为空", 110001);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("media_id", mediaId);
        Map<String, Object> resp = call("wiki", "get_media_info", body);
        return Json.asObject(resp.get("data"));
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    public Map<String, Object> call(String module, String action, Map<String, Object> body)
            throws ImaException {
        if (!cfg.configured()) {
            throw new ImaException("尚未配置 ima 凭据");
        }
        String url = HOST + "/openapi/" + module + "/v1/" + action;
        String payload = Json.write(body);
        ImaException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return request(url, payload);
            } catch (ImaException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                last = e;
            }
        }
        throw last;
    }

    private Map<String, Object> request(String url, String payload) throws ImaException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(cfg.timeoutMs);
            conn.setReadTimeout(cfg.timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("ima-openapi-clientid", cfg.clientId.trim());
            conn.setRequestProperty("ima-openapi-apikey", cfg.apiKey.trim());

            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream()
                    : conn.getErrorStream();
            String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);

            if (code < 200 || code >= 300) {
                throw new ImaException("ima 返回 HTTP " + code + "：" + brief(text), code);
            }
            Map<String, Object> resp = Json.asObject(Json.parse(text));
            checkEnvelope(resp);
            return resp;
        } catch (ImaException e) {
            throw e;
        } catch (IOException e) {
            throw new ImaException("网络不可达或超时：" + e.getMessage(), true);
        } catch (Json.JsonException e) {
            throw new ImaException("ima 响应不是合法 JSON：" + e.getMessage(), true);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 业务层错误码校验：官方约定 {@code code=0} 为成功、非 0 时 {@code msg} 可直接展示。
     */
    private static void checkEnvelope(Map<String, Object> resp) throws ImaException {
        Object code = firstOf(resp, "code", "err_code", "errCode", "retcode", "ret_code");
        if (code != null) {
            int c = Json.intOf(code, 0);
            if (c != 0) {
                throw new ImaException("ima 接口错误码 " + c + "：" + messageOf(resp), c);
            }
        }
        Object ok = firstOf(resp, "success", "ok");
        if (ok instanceof Boolean && !((Boolean) ok)) {
            throw new ImaException("ima 接口返回失败：" + messageOf(resp));
        }
    }

    private static String messageOf(Map<String, Object> resp) {
        Object m = firstOf(resp, "msg", "message", "error_msg", "err_msg", "error");
        return m == null ? "无附加信息" : String.valueOf(m);
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static Object firstOf(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 从响应中提取条目列表。
     *
     * <p>列表既可能在顶层，也可能在 {@code data} 下，因此按候选键名在两层里
     * 逐个尝试，命中即返回，避免因字段改名而整体失效。
     */
    private static List<Map<String, Object>> extractList(Map<String, Object> resp, String... keys) {
        List<Map<String, Object>> out = new ArrayList<>();
        collect(resp, keys, out);
        if (out.isEmpty()) {
            Object data = resp.get("data");
            if (data instanceof Map) {
                collect(Json.asObject(data), keys, out);
            }
        }
        return out;
    }

    private static void collect(Object node, String[] keys, List<Map<String, Object>> out) {
        if (!(node instanceof Map)) {
            return;
        }
        Map<String, Object> m = Json.asObject(node);
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof List) {
                for (Object o : Json.asArray(v)) {
                    if (o instanceof Map) {
                        out.add(Json.asObject(o));
                    }
                }
            }
        }
    }
}
