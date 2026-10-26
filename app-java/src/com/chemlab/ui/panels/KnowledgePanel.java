package com.chemlab.ui.panels;

import com.chemlab.kb.Hit;
import com.chemlab.kb.HybridBackend;
import com.chemlab.kb.ImaConfig;
import com.chemlab.kb.ImaKbBackend;
import com.chemlab.kb.KbBook;
import com.chemlab.kb.KbChapter;
import com.chemlab.kb.LocalKbBackend;
import com.chemlab.ui.FormUtil;
import com.chemlab.ui.Theme;
import com.chemlab.util.Json;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 化学知识库：多书目浏览与跨来源全文检索。
 *
 * <p>浏览侧数据来自本地参考书（{@code resources/kb/}，见 {@link LocalKbBackend}）：
 * 按书目/章节导航、内联条目浏览、全文分页阅读。检索侧改走 {@link HybridBackend}
 * 抽象，来源下拉可选「全部来源 / 本地参考书 / ima 知识库」，默认全部来源。
 *
 * <p>未配置 ima 凭据时下拉不含 ima 项，检索行为与改造前逐字一致；ima 不可达时
 * 该路静默降级为空并在状态栏给出一行提示，本地结果照常展示。
 */
public class KnowledgePanel extends JPanel {

    private static final String[] SOURCE_LABELS = {"全部来源", "本地参考书", "ima 知识库"};
    private static final String[] SOURCE_IDS = {
            HybridBackend.SOURCE_ALL, HybridBackend.SOURCE_LOCAL, HybridBackend.SOURCE_IMA,
    };

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    /** 统一列表项：Dean's 条目 / 目录小节 / 检索命中。 */
    private static class Item {
        String kind;          // entry | toc | hit
        String bookId;
        int page;
        String label;
        String text;          // entry 正文或 hit 片段
        String section;       // Dean's 小节名
        String chapterId;     // Dean's 所属章节
        Hit hit;              // kind=hit 时的来源命中项

        @Override
        public String toString() {
            return label;
        }
    }

    // ------------------------------------------------------------------
    // 后端
    // ------------------------------------------------------------------

    private final LocalKbBackend local = new LocalKbBackend();
    private ImaKbBackend ima;
    private HybridBackend backends;
    private final Runnable openImaConfig;

    // ------------------------------------------------------------------
    // 控件
    // ------------------------------------------------------------------

    private final JComboBox<Object> bookBox = new JComboBox<>();
    private final JComboBox<String> sourceBox = new JComboBox<>();
    private final JTextField searchField = Theme.field(18);
    private final JLabel sourceLabel = Theme.muted("");

    private final DefaultListModel<KbChapter> chapterModel = new DefaultListModel<>();
    private final JList<KbChapter> chapterList = new JList<>(chapterModel);
    private final DefaultListModel<Item> itemModel = new DefaultListModel<>();
    private final JList<Item> itemList = new JList<>(itemModel);
    private final JTextArea detail = FormUtil.output(18);
    private final JLabel statusLabel = Theme.muted("就绪");

    private final JButton searchBtn = Theme.primary("检索");
    private final JButton prevPage = Theme.secondary("◀ 上一页");
    private final JButton nextPage = Theme.secondary("下一页 ▶");
    private final JLabel pageLabel = Theme.muted("");

    private String currentBook;
    private int currentPage;

    public KnowledgePanel() {
        this(null);
    }

    /** @param openImaConfig 切换到 ima 配置模块的动作；为 null 时按钮仅给出提示。 */
    public KnowledgePanel(Runnable openImaConfig) {
        this.openImaConfig = openImaConfig;

        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        reloadImaConfig();
        load();
    }

    // ------------------------------------------------------------------
    // 界面搭建
    // ------------------------------------------------------------------

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);

        JButton clear = Theme.secondary("清除");
        JButton config = Theme.secondary("ima 配置");
        searchBtn.addActionListener(e -> doSearch());
        clear.addActionListener(e -> {
            searchField.setText("");
            applyBook();
        });
        config.addActionListener(e -> {
            if (openImaConfig != null) {
                openImaConfig.run();
            } else {
                statusLabel.setText("请从左侧「ima 知识库」进入配置");
            }
        });
        searchField.addActionListener(e -> doSearch());
        bookBox.addActionListener(e -> applyBook());

        bar.add(new JLabel("书目"));
        bar.add(bookBox);
        bar.add(new JLabel("  来源"));
        bar.add(sourceBox);
        bar.add(new JLabel("  关键字"));
        bar.add(searchField);
        bar.add(searchBtn);
        bar.add(clear);
        bar.add(new JLabel("  "));
        bar.add(config);
        bar.add(new JLabel("  "));
        bar.add(sourceLabel);
        return bar;
    }

    private JPanel buildBody() {
        chapterList.setFont(Theme.base());
        chapterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chapterList.setFixedCellHeight(24);
        chapterList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showChapterItems();
            }
        });
        JPanel chapterCard = Theme.titledCard("章节目录");
        chapterCard.add(new JScrollPane(chapterList), BorderLayout.CENTER);

        itemList.setFont(Theme.base());
        itemList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        itemList.setFixedCellHeight(24);
        itemList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showItemDetail();
            }
        });
        JPanel itemCard = Theme.titledCard("条目 / 小节 / 检索结果");
        itemCard.add(new JScrollPane(itemList), BorderLayout.CENTER);

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        nav.setOpaque(false);
        prevPage.addActionListener(e -> showPage(currentBook, currentPage - 1));
        nextPage.addActionListener(e -> showPage(currentBook, currentPage + 1));
        nav.add(prevPage);
        nav.add(nextPage);
        nav.add(pageLabel);

        JPanel detailCard = Theme.titledCard("内容");
        detailCard.add(nav, BorderLayout.NORTH);
        detailCard.add(new JScrollPane(detail), BorderLayout.CENTER);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, itemCard, detailCard);
        rightSplit.setResizeWeight(0.40);
        rightSplit.setBorder(null);
        rightSplit.setOpaque(false);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chapterCard, rightSplit);
        split.setResizeWeight(0.32);
        split.setBorder(null);
        split.setOpaque(false);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(split, BorderLayout.CENTER);
        updateNavButtons();
        return p;
    }

    // ------------------------------------------------------------------
    // 后端装配
    // ------------------------------------------------------------------

    /** 重新读取 ima 凭据并重建后端与来源下拉，配置保存后由外部调用。 */
    public void reloadImaConfig() {
        ima = new ImaKbBackend(ImaConfig.load());
        backends = new HybridBackend(local, ima);

        Object previous = sourceBox.getItemCount() > 0 ? sourceBox.getSelectedItem() : null;
        sourceBox.removeAllItems();
        for (int i = 0; i < SOURCE_LABELS.length; i++) {
            if (SOURCE_IDS[i].equals(HybridBackend.SOURCE_IMA) && !backends.imaConfigured()) {
                continue;
            }
            sourceBox.addItem(SOURCE_LABELS[i]);
        }
        if (previous != null) {
            sourceBox.setSelectedItem(previous);
        }
        if (sourceBox.getSelectedIndex() < 0) {
            sourceBox.setSelectedIndex(0);
        }
    }

    private String currentSourceId() {
        int i = sourceBox.getSelectedIndex();
        String label = sourceBox.getItemAt(i < 0 ? 0 : i);
        for (int k = 0; k < SOURCE_LABELS.length; k++) {
            if (SOURCE_LABELS[k].equals(label)) {
                return SOURCE_IDS[k];
            }
        }
        return HybridBackend.SOURCE_ALL;
    }

    // ------------------------------------------------------------------
    // 数据加载
    // ------------------------------------------------------------------

    private void load() {
        if (!local.available()) {
            statusLabel.setText("未找到知识库索引 resources/kb/index.json");
            sourceLabel.setText("本地参考书不可用");
            detail.setForeground(Theme.WARN);
            detail.setText("未找到知识库索引。\n\n"
                    + "请将 resources/kb/index.json 与各书全文放入 app-java/resources/kb/ 后重启。\n\n"
                    + "若只想使用 ima 知识库，可先在「ima 知识库」页完成配置，"
                    + "来源切换为「ima 知识库」后仍可检索。");
            bookBox.addItem("（无）");
            return;
        }

        bookBox.addItem("全部书目");
        for (KbBook b : local.books()) {
            bookBox.addItem(b);
        }
        bookBox.setSelectedIndex(0);

        refreshSourceLabel();
        statusLabel.setText("本地知识库就绪：共 " + local.totalPages() + " 页参考内容"
                + (backends.imaConfigured() ? "；ima 知识库已配置" : "；ima 未配置"));
        applyBook();
    }

    private void refreshSourceLabel() {
        sourceLabel.setText("书目 " + local.books().size() + " 本 · 章节 "
                + local.chapters().size() + " 个 · Dean's 条目 "
                + local.deanEntries().size() + " 条"
                + (backends.imaConfigured() ? " · ima 已配置" : ""));
    }

    private String[] pagesOf(String bookId) {
        String[] pages = local.pagesOf(bookId);
        if (pages == null) {
            statusLabel.setText("该书的全文文件不可用。");
        }
        return pages;
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    private KbBook selectedBook() {
        Object o = bookBox.getSelectedItem();
        return o instanceof KbBook ? (KbBook) o : null;
    }

    private void applyBook() {
        KbBook b = selectedBook();
        chapterModel.clear();
        itemModel.clear();
        detail.setText("");
        detail.setForeground(Theme.TEXT);
        for (KbChapter c : local.chapters()) {
            if (b == null || b.id.equals(c.bookId)) {
                chapterModel.addElement(c);
            }
        }
        if (b == null) {
            statusLabel.setText("全部书目：共 " + chapterModel.size() + " 个章节");
        } else {
            statusLabel.setText("《" + b.title + "》共 " + chapterModel.size() + " 个章节");
        }
        updateNavButtons();
    }

    private void showChapterItems() {
        KbChapter c = chapterList.getSelectedValue();
        if (c == null) {
            return;
        }
        KbBook b = local.bookById().get(c.bookId);
        itemModel.clear();
        if (b != null && b.fulltext) {
            int end = local.nextChapterPage(b, c.page);
            boolean any = false;
            for (int i = 0; i < b.toc.size(); i++) {
                int[] t = b.toc.get(i);
                if (t[0] >= 2 && t[1] >= c.page && t[1] < end) {
                    Item it = new Item();
                    it.kind = "toc";
                    it.bookId = b.id;
                    it.page = t[1];
                    it.label = "p." + t[1] + "  " + b.tocTitle.get(i);
                    itemModel.addElement(it);
                    any = true;
                }
            }
            if (!any) {
                int from = Math.max(1, c.page);
                int to = Math.min(b.pages, end - 1);
                for (int p = from; p <= to && p - from < 300; p++) {
                    Item it = new Item();
                    it.kind = "toc";
                    it.bookId = b.id;
                    it.page = p;
                    it.label = "第 " + p + " 页";
                    itemModel.addElement(it);
                }
            }
        } else {
            for (Map<String, Object> e : local.deanEntries()) {
                if (c.id.equals(Json.str(e.get("c"), ""))) {
                    Item it = new Item();
                    it.kind = "entry";
                    it.bookId = c.bookId;
                    it.page = Json.intOf(e.get("p"), 0);
                    it.section = Json.str(e.get("s"), "");
                    it.chapterId = c.id;
                    it.text = Json.str(e.get("t"), "");
                    String snip = it.text.replace('\n', ' ');
                    if (snip.length() > 90) {
                        snip = snip.substring(0, 90) + "…";
                    }
                    it.label = "p." + it.page + "  " + snip;
                    itemModel.addElement(it);
                }
            }
        }
        detail.setForeground(Theme.TEXT);
        detail.setText("《" + c.title + "》\n分类：" + c.category
                + "    起始页：p." + c.page + "\n条目数：" + itemModel.size()
                + "\n\n（在右侧列表中选择查看内容）");
        statusLabel.setText("章节：" + c.title + "，条目 " + itemModel.size() + " 条");
        updateNavButtons();
    }

    private void showItemDetail() {
        Item it = itemList.getSelectedValue();
        if (it == null) {
            return;
        }
        if ("entry".equals(it.kind)) {
            KbChapter c = local.chapterById().get(it.chapterId);
            StringBuilder sb = new StringBuilder();
            if (c != null) {
                sb.append("章节：").append(c.title).append("（").append(c.category).append("）\n");
            }
            sb.append("页码：p.").append(it.page).append('\n');
            if (!it.section.isEmpty()) {
                sb.append("小节：").append(it.section).append('\n');
            }
            sb.append('\n').append(it.text);
            detail.setForeground(Theme.TEXT);
            detail.setText(sb.toString());
            detail.setCaretPosition(0);
            updateNavButtons();
        } else if (it.hit != null) {
            showHitDetail(it.hit);
        } else {
            showPage(it.bookId, it.page);
        }
    }

    /** 显示某本书的某一页全文，并更新翻页控件状态。 */
    private void showPage(String bookId, int page) {
        KbBook b = local.bookById().get(bookId);
        String[] pages = pagesOf(bookId);
        if (b == null || pages == null) {
            detail.setForeground(Theme.WARN);
            detail.setText("该书的全文文件不可用。");
            return;
        }
        page = Math.max(1, Math.min(pages.length, page));
        currentBook = bookId;
        currentPage = page;
        String body = page <= pages.length ? pages[page - 1].trim() : "";
        StringBuilder sb = new StringBuilder();
        sb.append("《").append(b.title).append("》  第 ").append(page)
                .append(" / ").append(pages.length).append(" 页\n");
        sb.append("─".repeat(48)).append("\n\n");
        sb.append(body.isEmpty() ? "（本页无可提取文本，可能为图表页）" : body);
        detail.setForeground(Theme.TEXT);
        detail.setText(sb.toString());
        detail.setCaretPosition(0);
        updateNavButtons();
    }

    /** 显示检索命中项详情：来源、定位信息与正文。 */
    private void showHitDetail(Hit h) {
        StringBuilder sb = new StringBuilder();
        sb.append("来源：").append(h.isLocal() ? "本地参考书" : "ima 知识库").append('\n');
        if (!h.kbName.isEmpty()) {
            sb.append("知识库：").append(h.kbName).append('\n');
        }
        if (h.isLocal() && h.page > 0) {
            sb.append("页码：p.").append(h.page).append('\n');
        }
        if (!h.title.isEmpty()) {
            sb.append("标题：").append(h.title).append('\n');
        }
        sb.append(String.format("相关度：%.2f", h.score)).append('\n');
        sb.append("─".repeat(48)).append("\n\n");

        String body = backends.readText(h);
        if (body == null || body.trim().isEmpty()) {
            sb.append(h.snippet).append("\n\n");
            sb.append("（该条目未返回正文，可复制标题到 ima 中打开原文）");
            detail.setForeground(Theme.WARN);
        } else {
            sb.append(body.trim());
            detail.setForeground(Theme.TEXT);
        }
        detail.setText(sb.toString());
        detail.setCaretPosition(0);
        updateNavButtons();
    }

    private void updateNavButtons() {
        Item it = itemList.getSelectedValue();
        KbBook b = currentBook == null ? null : local.bookById().get(currentBook);
        boolean localPage = it != null && ("toc".equals(it.kind)
                || (it.hit != null && it.hit.isLocal() && it.hit.page > 0));
        boolean show = localPage && b != null && b.fulltext;
        prevPage.setEnabled(show && currentPage > 1);
        nextPage.setEnabled(show && currentPage < b.pages);
        pageLabel.setText(show ? ("第 " + currentPage + " / " + b.pages + " 页") : "");
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    private void doSearch() {
        String kw = searchField.getText().trim();
        if (kw.isEmpty()) {
            applyBook();
            return;
        }
        String source = currentSourceId();
        KbBook scope = selectedBook();
        String scopeId = HybridBackend.SOURCE_LOCAL.equals(source) && scope != null
                ? scope.id : null;

        searchBtn.setEnabled(false);
        statusLabel.setText("检索中…");
        Thread worker = new Thread(() -> {
            List<Hit> hits;
            String note;
            try {
                hits = backends.search(source, kw, 400, scopeId);
                note = backends.lastNote();
            } catch (RuntimeException e) {
                hits = new ArrayList<>();
                note = "检索失败：" + e.getMessage();
            }
            final List<Hit> h = hits;
            final String n = note;
            SwingUtilities.invokeLater(() -> renderHits(kw, h, n));
        }, "kb-search");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderHits(String kw, List<Hit> hits, String note) {
        itemModel.clear();
        for (Hit h : hits) {
            Item it = new Item();
            it.kind = "hit";
            it.hit = h;
            it.bookId = null;
            it.page = h.page;
            it.label = h.label();
            it.text = h.snippet;
            itemModel.addElement(it);
        }
        detail.setForeground(hits.isEmpty() ? Theme.WARN : Theme.TEXT);
        detail.setText(hits.isEmpty()
                ? "未找到包含「" + kw + "」的内容。"
                : "检索到 " + hits.size() + " 条包含「" + kw + "」的内容，"
                + "请在右上列表中选择查看；本地结果可继续用翻页按钮前后阅读。");

        int localN = 0;
        int imaN = 0;
        for (Hit h : hits) {
            if (h.isLocal()) {
                localN++;
            } else {
                imaN++;
            }
        }
        statusLabel.setText("检索「" + kw + "」命中 " + hits.size() + " 条（本地 "
                + localN + " · ima " + imaN + "）"
                + (note.isEmpty() ? "" : "　" + note));
        searchBtn.setEnabled(true);
        updateNavButtons();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(1100, 700);
    }
}
