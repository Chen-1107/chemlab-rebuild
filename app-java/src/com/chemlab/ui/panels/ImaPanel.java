package com.chemlab.ui.panels;

import com.chemlab.kb.ImaClient;
import com.chemlab.kb.ImaConfig;
import com.chemlab.kb.ImaKbBackend;
import com.chemlab.ui.FormUtil;
import com.chemlab.ui.Theme;
import com.chemlab.util.AppPaths;
import com.chemlab.util.Json;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ima 知识库配置面板。
 *
 * <p>凭据在 ima 后台自助生成，API Key 只展示一次，因此这里提供「测试连接」与
 * 「更换凭据」入口：测试连通后从 {@code search_knowledge_base} 拉取账号可见的
 * 知识库填充下拉。凭据保存在 {@code data/ima.json}，读取优先级为
 * 环境变量 → 配置文件。
 *
 * <p>凭据只发往 {@code https://ima.qq.com}；日志与状态提示中的凭据一律经
 * {@link ImaConfig#mask(String)} 脱敏，仅显示末 4 位。
 */
public class ImaPanel extends JPanel {

    private final JTextField clientIdField = Theme.field(30);
    private final JPasswordField apiKeyField = new JPasswordField(30);
    private final JComboBox<String> kbBox = new JComboBox<>();
    private final JTextField folderField = Theme.field(30);
    private final JTextField timeoutField = Theme.field(6);
    private final JCheckBox autoSyncCheck = new JCheckBox("自动同步显式标记的记录", false);

    private final JTextArea logArea = FormUtil.output(16);
    private final JLabel statusLabel = Theme.muted("就绪");
    private final JLabel credentialHint = Theme.muted("");

    private final JButton testBtn = Theme.primary("测试连接");
    private final JButton saveBtn = Theme.primary("保存配置");
    private final JButton reloadBtn = Theme.secondary("重新读取凭据");

    private final List<String> kbIds = new ArrayList<>();
    private final Runnable onSaved;

    private boolean busy;

    public ImaPanel() {
        this(null);
    }

    /** @param onSaved 保存成功后通知知识库面板刷新来源下拉；可为 null。 */
    public ImaPanel(Runnable onSaved) {
        this.onSaved = onSaved;

        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        add(buildConfig(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        loadFromConfig();
    }

    // ------------------------------------------------------------------

    private JPanel buildConfig() {
        JPanel card = Theme.titledCard("ima 连接配置");

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);

        apiKeyField.setFont(Theme.base());
        apiKeyField.setBorder(Theme.inputBorder());

        FormUtil.row(grid, 0, "Client ID", clientIdField, "ima 后台生成");
        FormUtil.row(grid, 1, "API Key", apiKeyField, "只展示一次，丢失需撤销重发");
        FormUtil.row(grid, 2, "默认知识库", kbBox, "测试连接后自动填充");

        JPanel folderRow = new JPanel(new BorderLayout(8, 0));
        folderRow.setOpaque(false);
        folderRow.add(folderField, BorderLayout.CENTER);
        FormUtil.row(grid, 3, "默认文件夹", folderRow, "可选 folder_id，决定回流落点");

        JPanel timeoutRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        timeoutRow.setOpaque(false);
        autoSyncCheck.setOpaque(false);
        autoSyncCheck.setFont(Theme.base());
        autoSyncCheck.setForeground(Theme.TEXT);
        timeoutRow.add(timeoutField);
        timeoutRow.add(Theme.muted("毫秒"));
        timeoutRow.add(new JLabel("   "));
        timeoutRow.add(autoSyncCheck);
        FormUtil.row(grid, 4, "超时", timeoutRow, "默认 8000ms，失败自动重试 1 次");

        GridBagConstraints h = new GridBagConstraints();
        h.gridx = 1;
        h.gridy = 5;
        h.weightx = 1;
        h.gridwidth = 2;
        h.fill = GridBagConstraints.HORIZONTAL;
        h.anchor = GridBagConstraints.WEST;
        h.insets = new Insets(4, 4, 4, 4);
        grid.add(credentialHint, h);

        FormUtil.note(grid, 6, "凭据保存在 " + AppPaths.imaConfig()
                + "，不写入数据库、不进报告导出；读取优先级：环境变量 → 配置文件。");

        card.add(grid, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildBody() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        testBtn.addActionListener(e -> testConnection());
        saveBtn.addActionListener(e -> saveConfig());
        reloadBtn.addActionListener(e -> {
            loadFromConfig();
            appendLog("已重新读取凭据与配置。");
        });
        actions.add(testBtn);
        actions.add(saveBtn);
        actions.add(reloadBtn);

        JPanel logCard = Theme.titledCard("连接日志");
        logCard.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setOpaque(false);
        p.add(actions, BorderLayout.NORTH);
        p.add(logCard, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

    private void loadFromConfig() {
        ImaConfig c = ImaConfig.load();
        clientIdField.setText(c.clientId);
        apiKeyField.setText(c.apiKey);
        folderField.setText(c.folderId);
        timeoutField.setText(String.valueOf(c.timeoutMs));
        autoSyncCheck.setSelected(c.autoSync);
        kbBox.removeAllItems();
        kbIds.clear();
        if (!c.knowledgeBaseName.isEmpty()) {
            kbBox.addItem(c.knowledgeBaseName);
            kbIds.add(c.knowledgeBaseId);
            kbBox.setSelectedIndex(0);
        }
        updateCredentialHint(c);
    }

    private void updateCredentialHint(ImaConfig c) {
        boolean envId = System.getenv(ImaConfig.ENV_CLIENT_ID) != null;
        boolean envKey = System.getenv(ImaConfig.ENV_API_KEY) != null;
        if (c.configured()) {
            credentialHint.setForeground(Theme.OK);
            credentialHint.setText("已配置：Client ID " + ImaConfig.mask(c.clientId)
                    + " · API Key " + ImaConfig.mask(c.apiKey)
                    + (envId || envKey ? "（含环境变量覆盖）" : ""));
        } else {
            credentialHint.setForeground(Theme.WARN);
            credentialHint.setText("尚未配置凭据，知识库面板的来源下拉暂不包含 ima。");
        }
    }

    private ImaConfig collectConfig() {
        ImaConfig c = new ImaConfig();
        c.clientId = clientIdField.getText().trim();
        c.apiKey = new String(apiKeyField.getPassword()).trim();
        c.folderId = folderField.getText().trim();
        c.timeoutMs = parseTimeout();
        c.autoSync = autoSyncCheck.isSelected();
        int i = kbBox.getSelectedIndex();
        if (i >= 0 && i < kbIds.size()) {
            c.knowledgeBaseId = kbIds.get(i);
            c.knowledgeBaseName = kbBox.getItemAt(i);
        }
        return c;
    }

    private int parseTimeout() {
        try {
            int t = Integer.parseInt(timeoutField.getText().trim());
            return t > 0 ? t : ImaConfig.DEFAULT_TIMEOUT_MS;
        } catch (NumberFormatException e) {
            return ImaConfig.DEFAULT_TIMEOUT_MS;
        }
    }

    private void saveConfig() {
        ImaConfig c = collectConfig();
        if (!c.configured()) {
            statusLabel.setText("请先填写 Client ID 与 API Key");
            appendLog("保存失败：Client ID 与 API Key 均为必填。");
            return;
        }
        try {
            c.save();
        } catch (IOException e) {
            statusLabel.setText("保存失败");
            appendLog("保存失败：" + e.getMessage());
            return;
        }
        updateCredentialHint(c);
        statusLabel.setText("配置已保存");
        appendLog("已保存配置：" + AppPaths.imaConfig());
        appendLog("  Client ID " + ImaConfig.mask(c.clientId)
                + " · API Key " + ImaConfig.mask(c.apiKey)
                + " · 超时 " + c.timeoutMs + "ms"
                + (c.knowledgeBaseId.isEmpty() ? " · 未指定知识库" : " · 知识库 " + c.knowledgeBaseName));
        if (onSaved != null) {
            onSaved.run();
        }
    }

    // ------------------------------------------------------------------
    // 连接测试
    // ------------------------------------------------------------------

    private void testConnection() {
        if (busy) {
            return;
        }
        ImaConfig c = collectConfig();
        if (!c.configured()) {
            statusLabel.setText("请先填写 Client ID 与 API Key");
            appendLog("测试连接失败：Client ID 与 API Key 均为必填。");
            return;
        }

        setBusy(true);
        statusLabel.setText("连接测试中…");
        appendLog("正在请求 https://ima.qq.com/openapi/wiki/v1/search_knowledge_base …");

        ImaKbBackend backend = new ImaKbBackend(c);
        Thread worker = new Thread(() -> {
            List<Map<String, Object>> kbs;
            String error = null;
            try {
                kbs = backend.testConnection();
            } catch (ImaClient.ImaException e) {
                kbs = new ArrayList<>();
                error = e.isAuthError()
                        ? "凭据无效或权限不足（HTTP " + e.code() + "），请更换 Client ID / API Key"
                        : e.getMessage();
            }
            final List<Map<String, Object>> list = kbs;
            final String err = error;
            SwingUtilities.invokeLater(() -> {
                if (err != null) {
                    statusLabel.setText("连接失败");
                    appendLog("连接失败：" + err);
                } else {
                    statusLabel.setText("已连接 · 可见 " + list.size() + " 个知识库");
                    appendLog("连接成功，可见 " + list.size() + " 个知识库。");
                    fillKnowledgeBases(list);
                }
                setBusy(false);
            });
        }, "ima-conn-test");
        worker.setDaemon(true);
        worker.start();
    }

    private void fillKnowledgeBases(List<Map<String, Object>> kbs) {
        String keep = kbBox.getSelectedIndex() >= 0 ? kbBox.getItemAt(kbBox.getSelectedIndex()) : "";
        kbBox.removeAllItems();
        kbIds.clear();
        for (Map<String, Object> kb : kbs) {
            // 实测 search_knowledge_base 返回 kb_id / kb_name；官方文档写的 id / name 并不出现，
            // 因此把实测键名放前面，其余作为兼容候选。
            String name = firstOf(kb, "kb_name", "name", "knowledge_base_name", "title");
            String id = firstOf(kb, "kb_id", "id", "knowledge_base_id");
            if (name.isEmpty()) {
                name = id;
            }
            if (name.isEmpty()) {
                continue;
            }
            kbBox.addItem(name);
            kbIds.add(id);
            appendLog("  · " + name + "（" + ImaConfig.mask(id) + "）");
        }
        for (int i = 0; i < kbBox.getItemCount(); i++) {
            if (kbBox.getItemAt(i).equals(keep)) {
                kbBox.setSelectedIndex(i);
                break;
            }
        }
    }

    private static String firstOf(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof String && !((String) v).isEmpty()) {
                return (String) v;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------

    private void setBusy(boolean busy) {
        this.busy = busy;
        testBtn.setEnabled(!busy);
        saveBtn.setEnabled(!busy);
        reloadBtn.setEnabled(!busy);
    }

    private void appendLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    @Override
    public java.awt.Dimension getPreferredSize() {
        return new java.awt.Dimension(1100, 700);
    }
}
