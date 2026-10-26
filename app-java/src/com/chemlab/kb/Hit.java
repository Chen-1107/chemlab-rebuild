package com.chemlab.kb;

/**
 * 统一检索命中项。
 *
 * <p>本地参考书（页级文本）与 ima 知识库（条目级片段）的返回结构差异很大，
 * 两者都归一化为本结构后交给界面，界面因此不需要认识任何具体来源。
 */
public final class Hit {

    /** 来源标识：{@code local} | {@code ima}。 */
    public String source = "local";
    /** 书名 / 条目名。 */
    public String title = "";
    /** 命中片段（已做换行压平与截断）。 */
    public String snippet = "";
    /** 检索接口已返回的正文；为空时由后端按 {@link #ref} 惰性解析。 */
    public String body = "";
    /** 来源内归一化到 [0,1] 的相关度，越大越相关。 */
    public double score;
    /** 来源内定位串，格式由各后端自行解释。 */
    public String ref = "";
    /** 知识库名，仅 ima 来源有值。 */
    public String kbName = "";
    /** 本地页号；ima 条目为 0。 */
    public int page;
    /** 是否可取到正文（ima 的网页链接类条目可能只有外链）。 */
    public boolean readable = true;

    public boolean isLocal() {
        return "local".equals(source);
    }

    /** 列表显示文本：来源标签 + 标题 + 片段。 */
    public String label() {
        StringBuilder sb = new StringBuilder();
        sb.append(isLocal() ? "[本地] " : "[ima] ");
        if (isLocal() && page > 0) {
            sb.append("p.").append(page).append("  ");
        }
        if (!title.isEmpty()) {
            sb.append(title).append("  ");
        }
        String s = snippet == null ? "" : snippet;
        if (s.length() > 100) {
            s = s.substring(0, 100) + "…";
        }
        sb.append(s);
        return sb.toString();
    }

    @Override
    public String toString() {
        return label();
    }
}
