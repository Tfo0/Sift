package sift.tracker;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import javax.swing.table.AbstractTableModel;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TrafficHistory extends AbstractTableModel {
    private static final int MAX_HARD = 20; // 绝对上限,防止异常 URL 无限扩列

    public static final int NORMAL_BASE = 3; // # 方法 Host
    public static final int SIFT_BASE   = NORMAL_BASE; // Sift 模式保留基础列

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static class Entry {
        public final int     num;
        public final boolean isNew;
        public volatile boolean isBatchStart;
        public volatile boolean manualMark;
        public final String  time, method, host, url;
        public final String[] pathSegments;
        public final HttpRequest  request;
        public final HttpResponse response;

        Entry(int num, boolean isNew, String time, String method, String host, String url,
              String[] segs, HttpRequest req, HttpResponse resp) {
            this.num = num; this.isNew = isNew; this.isBatchStart = false; this.manualMark = false;
            this.time = time; this.method = method; this.host = host; this.url = url;
            this.pathSegments = segs; this.request = req; this.response = resp;
        }

        Object getBase(int col) {
            return switch (col) {
                case 0 -> num;
                case 1 -> method;
                case 2 -> host;
                default -> "";
            };
        }
    }

    private final List<Entry>            rows       = new ArrayList<>();
    private final Set<String>            globalSeen = new HashSet<>();
    private final LinkedHashSet<String>  knownHosts = new LinkedHashSet<>();
    private volatile int     maxDepth   = 0; // 当前最深路径段数
    private volatile int     mergeLevel = 0;
    private volatile boolean siftMode   = false;

    // ── public API ────────────────────────────────────────────────

    public synchronized void addEntry(String method, String host, String path,
                                      HttpRequest req, HttpResponse resp) {
        String noQuery = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        boolean isNew  = globalSeen.add(method + "|" + host + "|" + noQuery);
        knownHosts.add(host);

        String[] segs = parseSegments(noQuery);
        Entry e = new Entry(rows.size() + 1, isNew,
            LocalTime.now().format(FMT), method, host, path, segs, req, resp);
        rows.add(e);

        if (segs.length > maxDepth) {
            maxDepth = segs.length;
            // 正常模式不显示路径列，无需触发结构变化
            if (siftMode) fireTableStructureChanged();
            else { int idx = rows.size() - 1; fireTableRowsInserted(idx, idx); }
        } else {
            int idx = rows.size() - 1;
            fireTableRowsInserted(idx, idx);
        }
    }

    public synchronized void markTop() {
        if (rows.isEmpty()) return;
        int last = rows.size() - 1;
        rows.get(last).isBatchStart = true;
        fireTableRowsUpdated(last, last);
    }

    public synchronized void toggleMark(int row) {
        rows.get(row).manualMark = !rows.get(row).manualMark;
        fireTableRowsUpdated(row, row);
    }

    public void setSiftMode(boolean on) {
        siftMode   = on;
        mergeLevel = on ? maxDepth : 0;
        fireTableStructureChanged();
    }

    public void setMergeLevel(int level) {
        mergeLevel = Math.max(0, Math.min(maxDepth, level));
        fireTableStructureChanged();
    }

    public boolean isSiftMode()  { return siftMode; }
    public int getMergeLevel()   { return mergeLevel; }
    public int getMaxDepth()     { return maxDepth; }
    public synchronized List<String> getHosts() { return new ArrayList<>(knownHosts); }

    public synchronized Entry   getEntry(int r)      { return rows.get(r); }
    public synchronized boolean isNew(int r)         { return rows.get(r).isNew; }
    public synchronized boolean isMarked(int r)      { return rows.get(r).manualMark; }
    public synchronized boolean isBatchStart(int r)  { return rows.get(r).isBatchStart; }

    public synchronized void clear() {
        int size = rows.size();
        rows.clear();
        globalSeen.clear();
        knownHosts.clear();
        maxDepth   = 0;
        mergeLevel = 0;
        siftMode   = false;
        if (size > 0) fireTableRowsDeleted(0, size - 1);
    }

    // ── TableModel ────────────────────────────────────────────────

    private int base() { return siftMode ? SIFT_BASE : NORMAL_BASE; }

    @Override
    public int getColumnCount() {
        if (!siftMode) return NORMAL_BASE;
        if (maxDepth == 0) return SIFT_BASE;
        if (mergeLevel == 0) return SIFT_BASE + maxDepth;
        return SIFT_BASE + 1 + Math.max(0, maxDepth - mergeLevel);
    }

    @Override
    public String getColumnName(int col) {
        if (col < base()) {
            return switch (col) {
                case 0 -> "#";
                case 1 -> "方法";
                case 2 -> "主机";
                default -> "";
            };
        }
        return segColName(col - base());
    }

    private String segColName(int k) {
        if (mergeLevel == 0) return "路径" + (k + 1);
        if (k == 0) return (mergeLevel >= maxDepth) ? "路径" : "路径1~" + mergeLevel;
        return "路径" + (mergeLevel + k);
    }

    @Override public int getRowCount()  { return rows.size(); }
    @Override public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : String.class; }

    @Override
    public synchronized Object getValueAt(int row, int col) {
        Entry e = rows.get(row);
        int b = base();
        if (col < b) return e.getBase(col);
        return segValue(e.pathSegments, col - b, mergeLevel);
    }

    // ── helpers ───────────────────────────────────────────────────

    private static String segValue(String[] segs, int k, int ml) {
        if (ml == 0) return k < segs.length ? "/" + segs[k] : "";
        if (k == 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(ml, segs.length); i++) sb.append("/").append(segs[i]);
            return sb.toString();
        }
        int idx = ml + k - 1;
        return idx < segs.length ? "/" + segs[idx] : "";
    }

    private static String[] parseSegments(String path) {
        List<String> segs = new ArrayList<>();
        for (String p : path.split("/")) { if (!p.isEmpty()) segs.add(p); }
        return segs.subList(0, Math.min(segs.size(), MAX_HARD)).toArray(new String[0]);
    }
}
