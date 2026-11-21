package sift.tab;

import burp.api.montoya.MontoyaApi;

import javax.swing.*;
import java.awt.*;

public class SettingsPanel extends JPanel {
    static final String KEY_EXP_HOST   = "exp_host";
    static final String KEY_EXP_API    = "exp_api";
    static final String KEY_EXP_METHOD = "exp_method";
    static final String KEY_EXP_PARAMS = "exp_params";
    static final String KEY_EXP_RKEYS  = "exp_rkeys";
    static final String KEY_EXP_FULL   = "exp_full";
    static final String KEY_DEF_MERGE  = "def_merge";

    private final MontoyaApi api;
    final JCheckBox cbHost, cbApi, cbMethod, cbParams, cbRkeys, cbFull;
    private final JSpinner mergeSpinner;

    public SettingsPanel(MontoyaApi api) {
        super(new BorderLayout());
        this.api = api;

        cbHost       = new JCheckBox("主机",     loadBool(KEY_EXP_HOST,   true));
        cbApi        = new JCheckBox("接口",     loadBool(KEY_EXP_API,    true));
        cbMethod     = new JCheckBox("方法",     loadBool(KEY_EXP_METHOD, true));
        cbParams     = new JCheckBox("参数",     loadBool(KEY_EXP_PARAMS, true));
        cbRkeys      = new JCheckBox("响应字段", loadBool(KEY_EXP_RKEYS,  true));
        cbFull       = new JCheckBox("完整响应", loadBool(KEY_EXP_FULL,   false));
        mergeSpinner = new JSpinner(new SpinnerNumberModel(loadInt(KEY_DEF_MERGE, 0), 0, 20, 1));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        content.add(sectionLabel("导出字段"));
        content.add(Box.createVerticalStrut(6));
        JPanel expRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        expRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JCheckBox cb : new JCheckBox[]{cbHost, cbApi, cbMethod, cbParams, cbRkeys, cbFull})
            expRow.add(cb);
        content.add(expRow);
        content.add(Box.createVerticalStrut(16));

        content.add(sectionLabel("路径设置"));
        content.add(Box.createVerticalStrut(6));
        JPanel mergeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        mergeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        mergeRow.add(new JLabel("默认合并层级："));
        mergeSpinner.setPreferredSize(new Dimension(64, mergeSpinner.getPreferredSize().height));
        mergeRow.add(mergeSpinner);
        mergeRow.add(new JLabel("（0 = 全合并，首次结构变化时生效）"));
        content.add(mergeRow);
        content.add(Box.createVerticalStrut(20));

        JButton saveBtn = new JButton("保存设置");
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.addActionListener(e -> save());
        content.add(saveBtn);

        add(content, BorderLayout.NORTH);
    }

    void save() {
        store(KEY_EXP_HOST,   str(cbHost));
        store(KEY_EXP_API,    str(cbApi));
        store(KEY_EXP_METHOD, str(cbMethod));
        store(KEY_EXP_PARAMS, str(cbParams));
        store(KEY_EXP_RKEYS,  str(cbRkeys));
        store(KEY_EXP_FULL,   str(cbFull));
        store(KEY_DEF_MERGE,  String.valueOf(mergeSpinner.getValue()));
        JOptionPane.showMessageDialog(this, "已保存", "设置", JOptionPane.INFORMATION_MESSAGE);
    }

    int getDefaultMerge() { return loadInt(KEY_DEF_MERGE, 0); }

    // ── helpers ───────────────────────────────────────────────────────
    private static JLabel sectionLabel(String t) {
        JLabel l = new JLabel(t);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static String str(JCheckBox cb) { return cb.isSelected() ? "true" : "false"; }

    private String  load(String k, String def)      { try { String v = api.persistence().preferences().getString(k); return v != null ? v : def; } catch (Exception e) { return def; } }
    private boolean loadBool(String k, boolean def) { return "true".equals(load(k, def ? "true" : "false")); }
    private int     loadInt(String k, int def)      { try { return Integer.parseInt(load(k, String.valueOf(def))); } catch (Exception e) { return def; } }
    private void    store(String k, String v)       { try { api.persistence().preferences().setString(k, v); } catch (Exception ignored) {} }
}
