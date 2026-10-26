package com.chemlab.kb;

import com.chemlab.util.AppPaths;
import com.chemlab.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ima 知识库连接配置。
 *
 * <p>凭据落在 {@code data/ima.json}，与 {@code data/obsidian.json} 同级，不随工程或
 * 安装包分发。读取优先级：环境变量 → 配置文件，与 ima 官方 skill 的约定一致。
 *
 * <p>凭据只用于构造请求头，任何日志、异常与界面回显一律经 {@link #mask(String)} 脱敏。
 */
public final class ImaConfig {

    public static final String ENV_CLIENT_ID = "IMA_OPENAPI_CLIENTID";
    public static final String ENV_API_KEY = "IMA_OPENAPI_APIKEY";
    public static final int DEFAULT_TIMEOUT_MS = 8000;

    public String clientId = "";
    public String apiKey = "";
    public String knowledgeBaseId = "";
    /** 默认知识库显示名，仅用于界面与结果标注。 */
    public String knowledgeBaseName = "";
    public String folderId = "";
    public int timeoutMs = DEFAULT_TIMEOUT_MS;
    public boolean autoSync;

    public boolean configured() {
        return !clientId.isEmpty() && !apiKey.isEmpty();
    }

    // ------------------------------------------------------------------

    public static ImaConfig load() {
        ImaConfig c = new ImaConfig();
        Path f = AppPaths.imaConfig();
        if (Files.isRegularFile(f)) {
            try {
                Map<String, Object> m = Json.asObject(Json.parse(
                        new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
                c.clientId = Json.str(m.get("clientId"), "");
                c.apiKey = Json.str(m.get("apiKey"), "");
                c.knowledgeBaseId = Json.str(m.get("knowledgeBaseId"), "");
                c.knowledgeBaseName = Json.str(m.get("knowledgeBaseName"), "");
                c.folderId = Json.str(m.get("folderId"), "");
                c.timeoutMs = Json.intOf(m.get("timeoutMs"), DEFAULT_TIMEOUT_MS);
                c.autoSync = Json.bool(m.get("autoSync"), false);
            } catch (IOException | RuntimeException ignored) {
                // 配置损坏时退回默认值，不影响本地知识库
            }
        }
        String envId = System.getenv(ENV_CLIENT_ID);
        String envKey = System.getenv(ENV_API_KEY);
        if (envId != null && !envId.isEmpty()) {
            c.clientId = envId;
        }
        if (envKey != null && !envKey.isEmpty()) {
            c.apiKey = envKey;
        }
        if (c.timeoutMs <= 0) {
            c.timeoutMs = DEFAULT_TIMEOUT_MS;
        }
        return c;
    }

    public void save() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientId", clientId);
        m.put("apiKey", apiKey);
        m.put("knowledgeBaseId", knowledgeBaseId);
        m.put("knowledgeBaseName", knowledgeBaseName);
        m.put("folderId", folderId);
        m.put("timeoutMs", timeoutMs);
        m.put("autoSync", autoSync);
        Files.write(AppPaths.imaConfig(), Json.write(m).getBytes(StandardCharsets.UTF_8));
    }

    /** 脱敏：仅保留末 4 位，用于界面回显与日志。 */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.length() <= 4) {
            return "****";
        }
        return "****" + secret.substring(secret.length() - 4);
    }
}
