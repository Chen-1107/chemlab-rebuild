package com.chemlab.ui.panels;

import com.chemlab.data.CalibrationDao;
import com.chemlab.data.RecordDao;
import com.chemlab.service.ObsidianSync;
import com.chemlab.ui.FormUtil;
import com.chemlab.ui.Theme;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Obsidian 同步面板。
 *
 * <p>配置一个 Obsidian 库（vault）路径后，可把实验记录、标定曲线与知识库参考
 * 导出为 Markdown 笔记，也可把库内笔记合并回应用数据库。双向同步按 {@code updated}
 * 时间戳判定新旧，较新一侧覆盖较旧一侧。操作在后台线程执行，日志实时回显。
 */
public class ObsidianPanel extends JPanel {

    private final RecordDao recordDao;
    private final CalibrationDao calibrationDao;

    private final JTextField vaultField = Theme.field(34);
    private final JCheckBox kbCheck = new JCheckBox("同时导出知识库参考（单向）", true);
    private final JTextArea logArea = FormUtil.output(20);
    private final JLabel statusLabel = Theme.muted("就绪");
    private final JLabel vaultHint = Theme.muted("");

    private final JButton exportBtn = Theme.primary("导出到库");
    private final JButton importBtn = Theme.secondary("从库导入");
    private final JButton syncBtn = Theme.primary("双向同步");
    private final JButton openBtn = Theme.secondary("打开库目录");

    private boolean busy;

    public ObsidianPanel(RecordDao recordDao, CalibrationDao calibrationDao) {
        this.recordDao = recordDao;
        this.calibrationDao = calibrationDao;

        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        add(buildConfig(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        String saved = ObsidianSync.loadVaultPath();
        vaultField.setText(saved);
        refreshVaultHint();
    }

    // ------------------------------------------------------------------

    private JPanel buildConfig() {
        JPanel card = Theme.titledCard("Obsidian 库");

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);

        JLabel l = Theme.label("库路径");
        grid.add(l, gbc(0, 0, 0, GridBagConstraints.NONE));

        JPanel pathRow = new JPanel(new BorderLayout(8, 0));
        pathRow.setOpaque(false);
        pathRow.add(vaultField, BorderLayout.CENTER);

        JPanel pathBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pathBtns.setOpaque(false);
        JButton browse = Theme.secondary("选择…");
        browse.addActionListener(e -> chooseVault());
        JButton save = Theme.secondary("保存路径");
        save.addActionListener(e -> {
            ObsidianSync.saveVaultPath(vaultField.getText().trim());
            refreshVaultHint();
            appendLog("已保存库路径：" + vaultField.getText().trim());
        });
        pathBtns.add(browse);
        pathBtns.add(save);
        pathRow.add(pathBtns, BorderLayout.EAST);

        GridBagConstraints c = gbc(1, 0, 1, GridBagConstraints.HORIZONTAL);
        grid.add(pathRow, c);

        GridBagConstraints h = gbc(1, 1, 1, GridBagConstraints.HORIZONTAL);
        grid.add(vaultHint, h);

        JLabel note = Theme.muted("库内将生成 ChemLab/ 目录：实验记录、标定曲线、知识库；"
                + "记录与标定曲线双向同步，知识库为软件 → 库单向导出。");
        GridBagConstraints nc = gbc(1, 2, 1, GridBagConstraints.HORIZONTAL);
        grid.add(note, nc);

        card.add(grid, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildBody() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        kbCheck.setOpaque(false);
        kbCheck.setFont(Theme.base());
        kbCheck.setForeground(Theme.TEXT);

        exportBtn.addActionListener(e -> runSync("export"));
        importBtn.addActionListener(e -> runSync("import"));
        syncBtn.addActionListener(e -> runSync("sync"));
        openBtn.addActionListener(e -> openVaultDir());

        actions.add(exportBtn);
        actions.add(importBtn);
        actions.add(syncBtn);
        actions.add(new JLabel("  "));
        actions.add(kbCheck);
        actions.add(new JLabel("  "));
        actions.add(openBtn);

        JPanel logCard = Theme.titledCard("同步日志");
        logCard.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setOpaque(false);
        p.add(actions, BorderLayout.NORTH);
        p.add(logCard, BorderLayout.CENTER);
        return p;
    }

    private static GridBagConstraints gbc(int x, int y, double wx, int fill) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.weightx = wx;
        c.fill = fill;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 4);
        return c;
    }

    // ------------------------------------------------------------------

    private void chooseVault() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 Obsidian 库目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        String cur = vaultField.getText().trim();
        if (!cur.isEmpty()) {
            File f = new File(cur);
            if (f.isDirectory()) {
                chooser.setCurrentDirectory(f);
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            vaultField.setText(chooser.getSelectedFile().getAbsolutePath());
            refreshVaultHint();
        }
    }

    private void refreshVaultHint() {
        String vp = vaultField.getText().trim();
        if (vp.isEmpty()) {
            vaultHint.setForeground(Theme.MUTED);
            vaultHint.setText("尚未配置库路径");
            return;
        }
        Path vault = Paths.get(vp);
        if (!Files.isDirectory(vault)) {
            vaultHint.setForeground(Theme.DANGER);
            vaultHint.setText("目录不存在：" + vp);
            return;
        }
        ObsidianSync engine = new ObsidianSync(vault, null);
        if (engine.looksLikeVault()) {
            vaultHint.setForeground(Theme.OK);
            vaultHint.setText("已识别为 Obsidian 库（含 .obsidian 目录）");
        } else {
            vaultHint.setForeground(Theme.WARN);
            vaultHint.setText("目录存在，但未发现 .obsidian（仍可同步，Obsidian 中重新打开该文件夹即可）");
        }
    }

    private void openVaultDir() {
        String vp = vaultField.getText().trim();
        if (vp.isEmpty()) {
            appendLog("请先配置库路径。");
            return;
        }
        Path dir = Paths.get(vp).resolve("ChemLab");
        if (!Files.isDirectory(dir)) {
            dir = Paths.get(vp);
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                appendLog("当前系统不支持自动打开目录：" + dir);
            }
        } catch (IOException e) {
            appendLog("打开目录失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private void runSync(String mode) {
        if (busy) {
            return;
        }
        String vp = vaultField.getText().trim();
        if (vp.isEmpty()) {
            appendLog("请先配置 Obsidian 库路径。");
            statusLabel.setText("未配置库路径");
            return;
        }
        Path vault = Paths.get(vp);
        if (!Files.isDirectory(vault)) {
            appendLog("库目录不存在：" + vp);
            statusLabel.setText("库目录不存在");
            return;
        }
        ObsidianSync.saveVaultPath(vp);
        boolean kb = kbCheck.isSelected();

        setBusy(true);
        logArea.setText("");
        statusLabel.setText("同步中…");

        ObsidianSync engine = new ObsidianSync(vault, this::appendLog);
        Thread worker = new Thread(() -> {
            ObsidianSync.SyncReport rep;
            if ("export".equals(mode)) {
                rep = engine.exportAll(recordDao, calibrationDao, kb);
            } else if ("import".equals(mode)) {
                rep = engine.importAll(recordDao, calibrationDao);
            } else {
                rep = engine.syncAll(recordDao, calibrationDao, kb);
            }
            final ObsidianSync.SyncReport r = rep;
            SwingUtilities.invokeLater(() -> {
                appendLog("── " + r.summary());
                statusLabel.setText(r.errors > 0 ? ("完成（含 " + r.errors + " 个错误）") : "完成");
                setBusy(false);
            });
        }, "obsidian-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        exportBtn.setEnabled(!busy);
        importBtn.setEnabled(!busy);
        syncBtn.setEnabled(!busy);
        openBtn.setEnabled(!busy);
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