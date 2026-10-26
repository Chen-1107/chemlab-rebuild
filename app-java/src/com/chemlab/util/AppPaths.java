package com.chemlab.util;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 应用路径解析。
 *
 * <p>统一以 {@code -Dchemlab.home=<项目根>} 指定工作根目录；未指定时退回
 * 当前工作目录。启动脚本会显式传入该参数，保证从任意位置启动都能定位到
 * 数据目录、Python 分析服务与知识库文件。
 */
public final class AppPaths {

    private AppPaths() {
    }

    public static Path home() {
        String h = System.getProperty("chemlab.home");
        if (h == null || h.isEmpty()) {
            h = System.getenv("CHEMLAB_HOME");
        }
        if (h == null || h.isEmpty()) {
            h = System.getProperty("user.dir");
        }
        return Paths.get(h).toAbsolutePath().normalize();
    }

    public static Path dataDir() {
        File dir = home().resolve("data").toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("[chemlab] 无法创建数据目录：" + dir);
        }
        return dir.toPath();
    }

    public static Path database() {
        String custom = System.getProperty("chemlab.db");
        if (custom != null && !custom.isEmpty()) {
            return Paths.get(custom).toAbsolutePath().normalize();
        }
        return dataDir().resolve("chemlab.db");
    }

    /** Python 分析服务脚本路径，找不到时返回 null。 */
    public static Path pythonService() {
        String custom = System.getProperty("chemlab.pythonService");
        if (custom != null && !custom.isEmpty() && new File(custom).isFile()) {
            return Paths.get(custom).toAbsolutePath().normalize();
        }
        Path p = home().resolve("analysis-python").resolve("service.py");
        return p.toFile().isFile() ? p : null;
    }

    /** 化学知识库文件路径，找不到时返回 null。 */
    public static Path knowledgeBase() {
        Path[] candidates = {
                home().resolve("app-java").resolve("resources").resolve("kb.json"),
                home().resolve("electron-app").resolve("assets").resolve("kb.json"),
        };
        for (Path p : candidates) {
            if (p.toFile().isFile()) {
                return p;
            }
        }
        return null;
    }

    /** 多书目知识库目录（含 index.json 与各书全文 txt）。 */
    public static Path kbDir() {
        Path p = home().resolve("app-java").resolve("resources").resolve("kb");
        return p.toFile().isDirectory() ? p : null;
    }

    /** 多书目知识库索引 index.json，找不到时返回 null。 */
    public static Path kbIndex() {
        Path d = kbDir();
        if (d == null) {
            return null;
        }
        Path idx = d.resolve("index.json");
        return idx.toFile().isFile() ? idx : null;
    }

    /** Obsidian 同步配置（记录库路径等），固定落在数据目录下。 */
    public static Path obsidianConfig() {
        return dataDir().resolve("obsidian.json");
    }

    /** ima 知识库连接配置（凭据等），固定落在数据目录下，与 obsidian.json 同级。 */
    public static Path imaConfig() {
        return dataDir().resolve("ima.json");
    }

    /** 某本书的全文 txt 路径（相对文件名），找不到时返回 null。 */
    public static Path kbText(String file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        Path d = kbDir();
        if (d == null) {
            return null;
        }
        Path p = d.resolve(file);
        return p.toFile().isFile() ? p : null;
    }
}