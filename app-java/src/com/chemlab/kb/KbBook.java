package com.chemlab.kb;

import java.util.ArrayList;
import java.util.List;

/** 本地参考书元数据：书目、目录与页数，供浏览模式使用。 */
public final class KbBook {

    public String id = "";
    public String title = "";
    public String author = "";
    public String edition = "";
    /** 全文文件名（相对 {@code resources/kb/}）。 */
    public String file = "";
    /** 是否为全文模式（非全文的只有内联条目）。 */
    public boolean fulltext;
    public int pages;
    public int chars;
    /** 目录项：{层级, 页码}，与 {@link #tocTitle} 按下标对应。 */
    public final List<int[]> toc = new ArrayList<>();
    public final List<String> tocTitle = new ArrayList<>();

    public String label() {
        String e = edition == null || edition.isEmpty() ? "" : " · " + edition;
        return title + e;
    }

    @Override
    public String toString() {
        return label();
    }
}
