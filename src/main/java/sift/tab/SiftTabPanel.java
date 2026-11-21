package sift.tab;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import sift.tracker.TrafficHistory;

import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SiftTabPanel extends JPanel {
    private SettingsPanel settings;

    private static final Color COLOR_BATCH = new Color(45,  75, 110);
    private static final Color COLOR_MARK  = new Color(140, 50,  50);
    private static final Color COLOR_NEW   = new Color(180, 180, 180);
    private static final Color COLOR_DUP   = new Color(35,  35,  35);

    private static final String STATIC_PAT =
        ".*\\.(js|css|png|jpg|jpeg|gif|ico|woff|woff2|ttf|eot|svg|mp4|mp3|pdf|map)(\\?.*)?$";

    public SiftTabPanel(MontoyaApi api, TrafficHistory history) {
        super(new BorderLayout());
        settings = new SettingsPanel(api);
        add(buildHistoryTab(api, history), BorderLayout.CENTER);
    }

    private Component buildHistoryTab(MontoyaApi api, TrafficHistory history) {
        history.setSiftMode(true);
        HttpRequestEditor  reqEditor  = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        HttpResponseEditor respEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        TableRowSorter<TrafficHistory> sorter = new TableRowSorter<>(history);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));

        JTable table = buildTable(api, history, reqEditor, respEditor, sorter);
        applyAllColWidths(table, history.getMergeLevel(), true);

        String[]  hostFilter = {"全部"};
        boolean[] bizOnly    = {true};

        Runnable applyFilter = () -> sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends TrafficHistory, ? extends Integer> e) {
                int mr = e.getIdentifier();
                TrafficHistory.Entry row = history.getEntry(mr);
                if (!history.isNew(mr) && !history.isMarked(mr) && !history.isBatchStart(mr))
                    return false;
                if (!"全部".equals(hostFilter[0]) && !hostFilter[0].equals(row.host))
                    return false;
                if (bizOnly[0] && row.url.toLowerCase().matches(STATIC_PAT))
                    return false;
                return true;
            }
        });
        applyFilter.run();

        boolean[] prog       = {false};
        boolean[] defApplied = {false};
        boolean[] autoMerge  = {true};
        DefaultComboBoxModel<String> mergeModel = new DefaultComboBoxModel<>();
        mergeModel.addElement("不合并");
        JComboBox<String> mergeCombo = new JComboBox<>(mergeModel);
        mergeCombo.addActionListener(e -> {
            if (prog[0]) return;
            autoMerge[0] = false;
            history.setMergeLevel(mergeCombo.getSelectedIndex());
        });

        DefaultComboBoxModel<String> domainModel = new DefaultComboBoxModel<>();
        domainModel.addElement("全部");
        JComboBox<String> hostCombo = new JComboBox<>(domainModel);
        hostCombo.addActionListener(e -> {
            hostFilter[0] = (String) hostCombo.getSelectedItem();
            applyFilter.run();
        });

        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"业务请求", "全部"});
        typeCombo.addActionListener(e -> {
            bizOnly[0] = typeCombo.getSelectedIndex() == 0;
            applyFilter.run();
        });

        JButton exportBtn = new JButton("导出");
        exportBtn.addActionListener(e -> exportVisible(table, history, hostFilter, settings));

        history.addTableModelListener(ev -> {
            if (ev.getType() == TableModelEvent.INSERT) {
                for (String h : history.getHosts()) {
                    if (domainModel.getIndexOf(h) < 0) domainModel.addElement(h);
                }
            } else if (ev.getFirstRow() == TableModelEvent.HEADER_ROW) {
                int maxDepth = history.getMaxDepth();
                prog[0] = true;
                mergeModel.removeAllElements();
                mergeModel.addElement("不合并");
                for (int i = 1; i <= maxDepth; i++) mergeModel.addElement("路径" + i);
                mergeCombo.setSelectedIndex(history.getMergeLevel());
                prog[0] = false;
                SwingUtilities.invokeLater(() -> {
                    // First structure change: apply the configured default level, if any
                    if (!defApplied[0] && history.isSiftMode() && history.getMaxDepth() > 0) {
                        defApplied[0] = true;
                        int def = settings.getDefaultMerge();
                        if (def > 0) {
                            autoMerge[0] = false;
                            history.setMergeLevel(Math.min(def, history.getMaxDepth()));
                            return; // setMergeLevel re-fires HEADER_ROW → redo sort/widths there
                        }
                    }
                    // Until the user picks a level, keep the path fully merged as depth grows
                    if (autoMerge[0] && history.getMaxDepth() > 0
                            && history.getMergeLevel() != history.getMaxDepth()) {
                        history.setMergeLevel(history.getMaxDepth());
                        return; // setMergeLevel re-fires HEADER_ROW → redo sort/widths there
                    }
                    sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
                    applyAllColWidths(table, history.getMergeLevel(), history.isSiftMode());
                });
            }
        });

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        toolbar.add(new JLabel("路径合并："));
        toolbar.add(mergeCombo);
        toolbar.add(new JLabel("域名："));
        toolbar.add(hostCombo);
        toolbar.add(new JLabel("类型："));
        toolbar.add(typeCombo);
        toolbar.add(exportBtn);

        JButton settingsBtn = new JButton("设置");
        settingsBtn.addActionListener(e -> showSettings());
        toolbar.add(settingsBtn);

        JSplitPane detailSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            reqEditor.uiComponent(), respEditor.uiComponent());
        detailSplit.setResizeWeight(0.5);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(table), detailSplit);
        mainSplit.setResizeWeight(0.5);
        mainSplit.addComponentListener(new ComponentAdapter() {
            private boolean placed;
            @Override public void componentResized(ComponentEvent e) {
                if (!placed && mainSplit.getHeight() > 0) {
                    placed = true;
                    mainSplit.setDividerLocation(0.5);
                }
            }
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(mainSplit, BorderLayout.CENTER);
        return panel;
    }

    private JTable buildTable(MontoyaApi api, TrafficHistory history,
                               HttpRequestEditor reqEditor, HttpResponseEditor respEditor,
                               TableRowSorter<TrafficHistory> sorter) {
        JTable table = new JTable(history);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSorter(sorter);

        DefaultTableCellRenderer rowRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object value, boolean selected, boolean focused, int row, int col) {
                super.getTableCellRendererComponent(t, value, selected, focused, row, col);
                if (!selected) {
                    int mr = t.convertRowIndexToModel(row);
                    if (history.isBatchStart(mr))     { setBackground(COLOR_BATCH); setForeground(Color.WHITE); }
                    else if (history.isMarked(mr))    { setBackground(COLOR_MARK);  setForeground(Color.WHITE); }
                    else if (history.isNew(mr))       { setBackground(COLOR_NEW);   setForeground(Color.BLACK); }
                    else                              { setBackground(COLOR_DUP);   setForeground(new Color(160,160,160)); }
                }
                return this;
            }
        };
        table.setDefaultRenderer(Object.class,  rowRenderer);
        table.setDefaultRenderer(Integer.class, rowRenderer);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int vr = table.getSelectedRow();
            if (vr < 0) return;
            TrafficHistory.Entry entry = history.getEntry(table.convertRowIndexToModel(vr));
            if (entry.request  != null) reqEditor.setRequest(entry.request);
            if (entry.response != null) respEditor.setResponse(entry.response);
        });

        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { selectOnRight(e); }
            @Override public void mouseReleased(MouseEvent e) { selectOnRight(e); }
            private void selectOnRight(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) table.setRowSelectionInterval(row, row);
                }
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) { table.setRowSelectionInterval(row, row); toggleSelected(table, history); }
                }
            }
        });

        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        String shortcutName = shortcutMask == InputEvent.META_DOWN_MASK ? "Cmd+R" : "Ctrl+R";

        JPopupMenu popup = new JPopupMenu();
        JMenuItem repeaterItem = new JMenuItem("发送到 Repeater（" + shortcutName + "）");
        repeaterItem.addActionListener(e -> sendSelected(api, table, history));
        popup.add(repeaterItem);
        table.setComponentPopupMenu(popup);

        table.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_R, shortcutMask), "sendToRepeater");
        table.getActionMap().put("sendToRepeater", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { sendSelected(api, table, history); }
        });

        return table;
    }

    // ── 设置弹窗 ─────────────────────────────────────────────────────

    private void showSettings() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Sift 设置", Dialog.ModalityType.APPLICATION_MODAL);

        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        south.add(closeBtn);

        JPanel content = new JPanel(new BorderLayout());
        content.add(settings, BorderLayout.CENTER);
        content.add(south,    BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    // ── 导出 ─────────────────────────────────────────────────────────

    private void exportVisible(JTable table, TrafficHistory history, String[] hostFilter,
                               SettingsPanel cfg) {
        String domain = "全部".equals(hostFilter[0]) ? "all"
            : hostFilter[0].replaceAll("[^\\w.-]", "_");
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出流量 JSONL");
        chooser.setSelectedFile(new java.io.File(domain + "_" + ts + ".jsonl"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        java.io.File file = chooser.getSelectedFile();
        int count = 0;
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            int rows = table.getRowCount();
            for (int vr = 0; vr < rows; vr++) {
                TrafficHistory.Entry entry = history.getEntry(table.convertRowIndexToModel(vr));
                pw.println(toJsonLine(entry, cfg));
                count++;
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "导出失败：" + ex.getMessage(),
                "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(this, "已导出 " + count + " 条\n" + file.getAbsolutePath(),
            "导出成功", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String toJsonLine(TrafficHistory.Entry e, SettingsPanel cfg) {
        String ct = contentTypeOf(e.request);
        StringBuilder sb = new StringBuilder("{\"#\":").append(e.num);
        if (cfg.cbHost.isSelected())
            sb.append(",\"host\":\"").append(jsonEscape(e.host)).append('"');
        if (cfg.cbApi.isSelected())
            sb.append(",\"api\":\"").append(jsonEscape(lastSegment(e.url))).append('"');
        if (cfg.cbMethod.isSelected())
            sb.append(",\"method\":\"").append(jsonEscape(e.method)).append('"');
        if (cfg.cbParams.isSelected()) {
            String params = e.request != null ? extractParams(e.url, e.request, ct) : "{}";
            sb.append(",\"params\":").append(params);
        }
        if (cfg.cbRkeys.isSelected())
            sb.append(",\"response_keys\":").append(responseKeys(e.response));
        if (cfg.cbFull.isSelected())
            sb.append(",\"response\":").append(responseJson(e.response));
        sb.append('}');
        return sb.toString();
    }

    // ── 导出工具方法 ──────────────────────────────────────────────────

    private static String lastSegment(String url) {
        return url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
    }

    private static String contentTypeOf(HttpRequest req) {
        if (req == null) return "";
        for (var h : req.headers()) {
            if (h.name().equalsIgnoreCase("content-type")) return h.value();
        }
        return "";
    }

    private static String responseJson(HttpResponse resp) {
        if (resp == null) return "{}";
        String body = resp.bodyToString().trim();
        return (body.startsWith("{") || body.startsWith("[")) ? body : "{}";
    }

    /** Returns a JSON array of all keys at every nesting level of the response body object. */
    private static String responseKeys(HttpResponse resp) {
        if (resp == null) return "[]";
        String body = resp.bodyToString().trim();
        if (!body.startsWith("{")) return "[]";

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        int i = 0, n = body.length();
        while (i < n) {
            char c = body.charAt(i);
            if (c == '"') {
                // read complete string
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    char s = body.charAt(i++);
                    if (s == '\\') { if (i < n) sb.append(body.charAt(i++)); }
                    else if (s == '"') break;
                    else sb.append(s);
                }
                // if followed by ':', it's a key at any depth
                int j = i;
                while (j < n && Character.isWhitespace(body.charAt(j))) j++;
                if (j < n && body.charAt(j) == ':') keys.add(sb.toString());
            } else {
                i++;
            }
        }

        if (keys.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String key : keys) {
            if (!first) sb.append(",");
            sb.append("\"").append(jsonEscape(key)).append("\"");
            first = false;
        }
        return sb.append("]").toString();
    }

    private static String extractParams(String url, HttpRequest req, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        Map<String, String> queryParams = new LinkedHashMap<>();
        int qIdx = url.indexOf('?');
        if (qIdx >= 0) parseFormEncoded(url.substring(qIdx + 1), queryParams);

        String body = req.bodyToString();
        if (ct.contains("application/json") && !body.isBlank()) {
            String trimmed = body.trim();
            // merge query params into JSON body if both exist
            if (!queryParams.isEmpty() && trimmed.startsWith("{")) {
                StringBuilder merged = new StringBuilder(trimmed.substring(0, trimmed.lastIndexOf('}')));
                for (var entry : queryParams.entrySet())
                    merged.append(",\"").append(jsonEscape(entry.getKey()))
                          .append("\":\"").append(jsonEscape(entry.getValue())).append("\"");
                return merged.append("}").toString();
            }
            return (trimmed.startsWith("{") || trimmed.startsWith("[")) ? trimmed
                : "\"" + jsonEscape(trimmed) + "\"";
        }
        Map<String, String> params = new LinkedHashMap<>(queryParams);
        if (ct.contains("application/x-www-form-urlencoded") && !body.isBlank()) {
            parseFormEncoded(body, params);
        } else if (!body.isBlank() && !ct.contains("multipart")) {
            params.put("_body", body);
        }
        return mapToJson(params);
    }

    private static void parseFormEncoded(String encoded, Map<String, String> out) {
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) out.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                else out.put(URLDecoder.decode(pair.substring(0, eq),  StandardCharsets.UTF_8),
                             URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
    }

    private static String mapToJson(Map<String, String> map) {
        if (map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(jsonEscape(entry.getKey()))
              .append("\":\"").append(jsonEscape(entry.getValue())).append("\"");
            first = false;
        }
        return sb.append("}").toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }

    // ── 列宽 / 交互 ──────────────────────────────────────────────────

    private void applyAllColWidths(JTable table, int mergeLevel, boolean siftMode) {
        var cm = table.getColumnModel();
        int b = siftMode ? TrafficHistory.SIFT_BASE : TrafficHistory.NORMAL_BASE;
        cm.getColumn(0).setMinWidth(40); cm.getColumn(0).setMaxWidth(50);
        cm.getColumn(1).setMinWidth(50); cm.getColumn(1).setMaxWidth(65);
        cm.getColumn(2).setPreferredWidth(160);
        int segCols = cm.getColumnCount() - b;
        for (int k = 0; k < segCols; k++) {
            int pw = (mergeLevel > 0 && segCols == 1) ? 500
                   : (mergeLevel > 0 && k == 0)       ? 220 : 110;
            cm.getColumn(b + k).setPreferredWidth(pw);
        }
    }

    private void toggleSelected(JTable table, TrafficHistory history) {
        int vr = table.getSelectedRow();
        if (vr < 0) return;
        history.toggleMark(table.convertRowIndexToModel(vr));
    }

    private void sendSelected(MontoyaApi api, JTable table, TrafficHistory history) {
        int vr = table.getSelectedRow();
        if (vr < 0) return;
        TrafficHistory.Entry entry = history.getEntry(table.convertRowIndexToModel(vr));
        if (entry.request != null) api.repeater().sendToRepeater(entry.request, entry.host);
    }
}
