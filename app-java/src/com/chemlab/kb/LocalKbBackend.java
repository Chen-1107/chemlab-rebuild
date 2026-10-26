package com.chemlab.kb;

import com.chemlab.util.AppPaths;
import com.chemlab.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地参考书后端。
 *
 * <p>从 {@code app-java/resources/kb/index.json} 与各书全文 txt 加载书目、章节与
 * 内联条目，提供页级正文与子串检索。加载与检索逻辑自 {@code KnowledgePanel}
 * 原样迁出，行为不变：全文书按页子串匹配，非全文书按内联条目匹配。
 *
 * <p>全文按需惰性加载并缓存；索引缺失时 {@link #available()} 返回 false，
 * 界面据此降级为提示信息。
 */
public final class LocalKbBackend implements KnowledgeBackend {

    private final List<KbBook> books = new ArrayList<>();
    private final Map<String, KbBook> bookById = new LinkedHashMap<>();
    private final List<KbChapter> chapters = new ArrayList<>();
    private final Map<String, KbChapter> chapterById = new HashMap<>();
    private final List<Map<String, Object>> deanEntries = new ArrayList<>();
    private final Map<String, String[]> pageCache = new HashMap<>();

    private boolean loaded;
    private String loadError;

    public LocalKbBackend() {
        load();
    }

    // ------------------------------------------------------------------
    // 元信息
    // ------------------------------------------------------------------

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String name() {
        return "本地参考书";
    }

    @Override
    public boolean available() {
        return loaded && !books.isEmpty();
    }

    /** 索引缺失或解析失败时的原因；成功时为 null。 */
    public String loadError() {
        return loadError;
    }

    public List<KbBook> books() {
        return books;
    }

    public Map<String, KbBook> bookById() {
        return bookById;
    }

    public List<KbChapter> chapters() {
        return chapters;
    }

    public Map<String, KbChapter> chapterById() {
        return chapterById;
    }

    public List<Map<String, Object>> deanEntries() {
        return deanEntries;
    }

    public int totalPages() {
        int n = 0;
        for (KbBook b : books) {
            n += b.pages;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private void load() {
        Path idx = AppPaths.kbIndex();
        if (idx == null) {
            loadError = "未找到知识库索引 resources/kb/index.json";
            return;
        }
        try {
            String text = new String(Files.readAllBytes(idx), StandardCharsets.UTF_8);
            Map<String, Object> root = Json.asObject(Json.parse(text));

            for (Object o : Json.asArray(root.get("books"))) {
                Map<String, Object> b = Json.asObject(o);
                KbBook bk = new KbBook();
                bk.id = Json.str(b.get("id"), "");
                bk.title = Json.str(b.get("title"), "");
                bk.author = Json.str(b.get("author"), "");
                bk.edition = Json.str(b.get("edition"), "");
                bk.file = Json.str(b.get("file"), "");
                bk.fulltext = "fulltext".equals(Json.str(b.get("mode"), ""));
                bk.pages = Json.intOf(b.get("pages"), 0);
                bk.chars = Json.intOf(b.get("chars"), 0);
                for (Object t : Json.asArray(b.get("toc"))) {
                    List<Object> e = Json.asArray(t);
                    int lvl = e.size() > 0 ? Json.intOf(e.get(0), 1) : 1;
                    String title = e.size() > 1 ? Json.str(e.get(1), "") : "";
                    int page = e.size() > 2 ? Json.intOf(e.get(2), 0) : 0;
                    bk.toc.add(new int[]{lvl, page});
                    bk.tocTitle.add(title);
                }
                books.add(bk);
                bookById.put(bk.id, bk);
            }
            for (Object o : Json.asArray(root.get("ch"))) {
                Map<String, Object> c = Json.asObject(o);
                KbChapter ch = new KbChapter();
                ch.id = Json.str(c.get("id"), "");
                ch.bookId = Json.str(c.get("book"), "");
                ch.title = Json.str(c.get("t"), "");
                ch.category = Json.str(c.get("cat"), "其他");
                ch.page = Json.intOf(c.get("p"), 0);
                chapters.add(ch);
                chapterById.put(ch.id, ch);
            }
            for (Object o : Json.asArray(root.get("e"))) {
                deanEntries.add(Json.asObject(o));
            }
            loaded = true;
        } catch (IOException | RuntimeException e) {
            loadError = e.getMessage();
        }
    }

    /** 某本书的分页正文；非全文书或文件缺失时返回 null。 */
    public String[] pagesOf(String bookId) {
        KbBook b = bookById.get(bookId);
        if (b == null || !b.fulltext) {
            return null;
        }
        String[] cached = pageCache.get(bookId);
        if (cached != null) {
            return cached;
        }
        Path p = AppPaths.kbText(b.file);
        if (p == null) {
            return null;
        }
        try {
            String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            String[] pages = raw.split("\f", -1);
            pageCache.put(bookId, pages);
            return pages;
        } catch (IOException e) {
            return null;
        }
    }

    /** 该章节的结束页（即下一章节的起始页）。 */
    public int nextChapterPage(KbBook b, int page) {
        int end = b.pages + 1;
        for (KbChapter c : chapters) {
            if (c.bookId.equals(b.id) && c.page > page && c.page < end) {
                end = c.page;
            }
        }
        return end;
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    @Override
    public List<Hit> search(String query, int limit) {
        return search(query, limit, null);
    }

    /**
     * 检索，可限定在单本书内。
     *
     * @param bookScope 书目 id；为 null 或空表示全部书目
     */
    public List<Hit> search(String query, int limit, String bookScope) {
        List<Hit> out = new ArrayList<>();
        if (!available() || query == null) {
            return out;
        }
        String kw = query.trim();
        if (kw.isEmpty()) {
            return out;
        }
        String lower = kw.toLowerCase(Locale.ROOT);
        int cap = limit <= 0 ? 400 : limit;

        for (KbBook b : books) {
            if (out.size() >= cap) {
                break;
            }
            if (bookScope != null && !bookScope.isEmpty() && !bookScope.equals(b.id)) {
                continue;
            }
            if (!b.fulltext) {
                for (int i = 0; i < deanEntries.size() && out.size() < cap; i++) {
                    Map<String, Object> e = deanEntries.get(i);
                    String text = Json.str(e.get("t"), "");
                    String sec = Json.str(e.get("s"), "");
                    String hay = (text + " " + sec).toLowerCase(Locale.ROOT);
                    if (!hay.contains(lower)) {
                        continue;
                    }
                    KbChapter c = chapterById.get(Json.str(e.get("c"), ""));
                    Hit h = new Hit();
                    h.source = "local";
                    h.title = c == null ? b.title : c.title;
                    h.page = Json.intOf(e.get("p"), 0);
                    h.ref = b.id + "|e|" + i;
                    h.snippet = snippet(text, text.toLowerCase(Locale.ROOT).indexOf(lower), kw.length());
                    h.score = countOccurrences(hay, lower);
                    out.add(h);
                }
            } else {
                String[] pages = pagesOf(b.id);
                if (pages == null) {
                    continue;
                }
                for (int p = 1; p <= pages.length && out.size() < cap; p++) {
                    String text = pages[p - 1];
                    int at = text.toLowerCase(Locale.ROOT).indexOf(lower);
                    if (at < 0) {
                        continue;
                    }
                    Hit h = new Hit();
                    h.source = "local";
                    h.title = b.title;
                    h.page = p;
                    h.ref = b.id + "|" + p;
                    h.snippet = snippet(text, at, kw.length());
                    h.score = countOccurrences(text.toLowerCase(Locale.ROOT), lower);
                    out.add(h);
                }
            }
        }

        normalize(out);
        // 稳定排序：分数相同时保持书目 / 页序，与迁出前逐条一致
        out.sort((a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    private static void normalize(List<Hit> hits) {
        double max = 0;
        for (Hit h : hits) {
            max = Math.max(max, h.score);
        }
        if (max <= 0) {
            return;
        }
        for (Hit h : hits) {
            h.score = h.score / max;
        }
    }

    private static double countOccurrences(String hay, String needle) {
        int n = 0;
        int at = hay.indexOf(needle);
        while (at >= 0 && n < 20) {
            n++;
            at = hay.indexOf(needle, at + needle.length());
        }
        return Math.max(1, n);
    }

    private static String snippet(String text, int at, int len) {
        if (at < 0) {
            at = 0;
        }
        int from = Math.max(0, at - 60);
        int to = Math.min(text.length(), at + len + 60);
        String s = text.substring(from, to).replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return (from > 0 ? "…" : "") + s + (to < text.length() ? "…" : "");
    }

    @Override
    public String readText(Hit hit) {
        if (hit == null || hit.ref == null || hit.ref.isEmpty()) {
            return null;
        }
        String[] parts = hit.ref.split("\\|");
        if (parts.length == 3 && "e".equals(parts[1])) {
            int i;
            try {
                i = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (i < 0 || i >= deanEntries.size()) {
                return null;
            }
            return Json.str(deanEntries.get(i).get("t"), "");
        }
        if (parts.length != 2) {
            return null;
        }
        String[] pages = pagesOf(parts[0]);
        if (pages == null) {
            return null;
        }
        int page;
        try {
            page = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (page < 1 || page > pages.length) {
            return null;
        }
        return pages[page - 1];
    }
}
