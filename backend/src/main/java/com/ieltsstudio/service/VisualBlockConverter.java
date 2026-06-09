package com.ieltsstudio.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convert textual [Visual Data Summary] / [Table Data] blocks inside passages
 * into structured charts[] / tables[] objects for front-end rendering.
 *
 * This class is conservative and non-invasive: it never modifies the passage text.
 * It only extracts best-effort structured data and returns them as plain Maps/Lists
 * to be merged into the parsed result map (keeping backward compatibility).
 */
public class VisualBlockConverter {

    public static Map<String, Object> extract(List<String> passages) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> charts = new ArrayList<>();
        List<Map<String, Object>> tables = new ArrayList<>();
        if (passages == null || passages.isEmpty()) {
            out.put("charts", charts);
            out.put("tables", tables);
            return out;
        }
        for (int i = 0; i < passages.size(); i++) {
            String text = String.valueOf(passages.get(i));
            extractFromPassage(text, i, charts, tables);
        }
        out.put("charts", charts);
        out.put("tables", tables);
        return out;
    }

    private static void extractFromPassage(String text, int passageIndex,
                                           List<Map<String, Object>> charts,
                                           List<Map<String, Object>> tables) {
        if (text == null || text.isBlank()) return;
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");

        // Extract [Visual Data Summary] blocks
        Pattern pV = Pattern.compile("(?is)\\[Visual Data Summary\\]([\\s\\S]*?)(?=\\n\\[[^\\]]+\\]|$)");
        Matcher mV = pV.matcher(normalized);
        while (mV.find()) {
            String block = mV.group(1).trim();
            charts.addAll(parseVisualBlock(block, passageIndex));
        }

        // Extract [Table Data] blocks (Markdown tables)
        Pattern pT = Pattern.compile("(?is)\\[Table Data\\]([\\s\\S]*?)(?=\\n\\[[^\\]]+\\]|$)");
        Matcher mT = pT.matcher(normalized);
        while (mT.find()) {
            String block = mT.group(1).trim();
            Map<String, Object> table = parseTableBlock(block, passageIndex);
            if (table != null) tables.add(table);
        }

        // Fallback: parse entire passage to capture chart blocks that are not inside [Visual Data Summary]
        List<Map<String, Object>> fallbackCharts = parseVisualBlock(normalized, passageIndex);
        for (Map<String, Object> c : fallbackCharts) {
            if (!chartExists(charts, c)) charts.add(c);
        }
    }

    private static List<Map<String, Object>> parseVisualBlock(String block, int passageIndex) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (block == null || block.isBlank()) return result;
        String[] lines = block.replace("\r\n", "\n").replace("\r", "\n").split("\n");

        class Accum {
            String chartTitle = null;
            String chartType = null;
            String xAxis = null;
            String yAxis = null;
            List<String> seriesDecl = new ArrayList<>();
            List<Datum> data = new ArrayList<>();
        }
        Accum acc = new Accum();

        Pattern pData = Pattern.compile("^([^:]+):\\s*([+-]?\\d+(?:\\.\\d+)?)(.*)$");

        // helper to flush current chart when a new chartTitle/chartType is encountered
        Runnable flush = () -> {
            if (acc.chartType == null && acc.data.isEmpty()) return;
            String unitType = classifyUnit(acc.yAxis, acc.data);
            if (acc.chartType != null && acc.chartType.toLowerCase(Locale.ROOT).contains("pie")) {
                // Split pies by group pattern if applicable
                Map<String, List<Datum>> grouped = new LinkedHashMap<>();
                Pattern grp = Pattern.compile("^([A-Za-z][A-Za-z ]*?)\\s(\\d{4})\\s+(.+)$");
                int matches = 0;
                for (Datum d : acc.data) {
                    Matcher gm = grp.matcher(d.label);
                    if (!gm.matches()) continue;
                    matches++;
                    String key = (gm.group(1).trim() + " " + gm.group(2)).trim();
                    String cat = gm.group(3).trim();
                    grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(new Datum(cat, d.value, d.unit));
                }
                if (grouped.size() >= 2 && matches >= 4) {
                    for (Map.Entry<String, List<Datum>> e : grouped.entrySet()) {
                        result.add(pieChart(acc.chartTitle, e.getKey(), unitType, e.getValue(), passageIndex, block, acc.yAxis));
                    }
                } else {
                    result.add(pieChart(acc.chartTitle, null, unitType, acc.data, passageIndex, block, acc.yAxis));
                }
            } else if (acc.chartType != null && (acc.chartType.toLowerCase(Locale.ROOT).contains("bar")
                    || acc.chartType.toLowerCase(Locale.ROOT).contains("line"))) {
                Map<String, Map<String, Double>> matrix = new LinkedHashMap<>(); // x -> series -> value
                Set<String> seriesNamesSet = new LinkedHashSet<>(acc.seriesDecl);
                Pattern yFirst = Pattern.compile("^(\\d{4})\\s+(.+)$");
                Pattern cFirst = Pattern.compile("^(.+?)\\s(\\d{4})$");
                Pattern catWithRange = Pattern.compile("^(.+?)\\s*\\((\\d{4}[–-]\\d{4})\\)$");
                Pattern catWithRangeNoParen = Pattern.compile("^(.+?)\\s+(\\d{4}[–-]\\d{4})$");
                for (Datum d : acc.data) {
                    Matcher m1 = yFirst.matcher(d.label);
                    Matcher m2 = cFirst.matcher(d.label);
                    Matcher m3 = catWithRange.matcher(d.label);
                    Matcher m4 = catWithRangeNoParen.matcher(d.label);
                    String x = null, s = null;
                    if (m1.matches()) { x = m1.group(1); s = m1.group(2).trim(); }
                    else if (m2.matches()) { x = m2.group(2); s = m2.group(1).trim(); }
                    else if (m3.matches()) { x = m3.group(1).trim(); s = m3.group(2).trim(); }
                    else if (m4.matches()) { x = m4.group(1).trim(); s = m4.group(2).trim(); }
                    else { x = d.label; s = acc.seriesDecl.isEmpty() ? "value" : acc.seriesDecl.get(0); }
                    matrix.computeIfAbsent(x, k -> new LinkedHashMap<>()).put(s, d.value);
                    seriesNamesSet.add(s);
                }
                List<String> xs = new ArrayList<>(matrix.keySet());
                List<String> seriesNames = new ArrayList<>(seriesNamesSet);

                // Special case: labels like "Never Married 1970" → xs are years, seriesNames are categories; flip to categories as xAxis, years as series
                boolean xsAreYears = xs.stream().allMatch(v -> v != null && v.matches("^\\d{4}$"));
                boolean seriesLookLikeCategories = seriesNames.stream().anyMatch(v -> v != null && !v.matches("^\\d{4}$"));

                List<Map<String, Object>> series = new ArrayList<>();
                List<String> categoriesForDim = xs;
                List<String> groupsForDim = seriesNames;

                if (acc.seriesDecl.isEmpty() && xsAreYears && seriesLookLikeCategories) {
                    List<String> categories = new ArrayList<>(seriesNames); // Never Married, Married, Widowed, Divorced
                    List<String> years = xs;
                    groupsForDim = years;
                    categoriesForDim = categories;
                    for (String year : years) {
                        List<Map<String, Object>> points = new ArrayList<>();
                        for (String cat : categories) {
                            double v = matrix.getOrDefault(year, Map.of()).getOrDefault(cat, 0.0);
                            points.add(Map.of("label", cat, "value", v));
                        }
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("name", year);
                        one.put("data", points);
                        series.add(one);
                    }
                } else {
                    for (String s : seriesNames) {
                        List<Map<String, Object>> points = new ArrayList<>();
                        for (String x : xs) {
                            double v = matrix.getOrDefault(x, Map.of()).getOrDefault(s, 0.0);
                            points.add(Map.of("label", x, "value", v));
                        }
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("name", s);
                        one.put("data", points);
                        series.add(one);
                    }
                }

                Map<String, Object> chart = new LinkedHashMap<>();
                chart.put("id", UUID.randomUUID().toString());
                chart.put("title", acc.chartTitle);
                chart.put("type", acc.chartType.toLowerCase(Locale.ROOT).contains("line") ? "line" : "bar");
                chart.put("unit", unitType);
                chart.put("dimensions", Map.of(
                        "xAxis", acc.xAxis == null ? "category" : acc.xAxis,
                        "categories", categoriesForDim,
                        "groups", groupsForDim
                ));
                chart.put("series", series);
                chart.put("meta", meta(passageIndex, block, acc.yAxis));
                result.add(chart);
            }
        };

        for (String raw : lines) {
            String line = raw == null ? "" : raw.replace('\u00A0', ' ').trim();
            if (line.isEmpty()) continue;
            if (line.matches("(?i)^chartTitle\\s*[:：].+")) {
                if (!acc.data.isEmpty() || (acc.chartType != null && !acc.chartType.isBlank())) {
                    flush.run();
                    // reset accumulators for next chart
                    acc.data = new ArrayList<>(); acc.seriesDecl = new ArrayList<>(); acc.xAxis = null; acc.yAxis = null;
                }
                acc.chartTitle = line.replaceFirst("(?i)^chartTitle\\s*[:：]\\s*", "").trim();
                continue;
            }
            if (line.matches("(?i)^chartType\\s*[:：].+")) {
                if (!acc.data.isEmpty() || (acc.chartType != null && !acc.chartType.isBlank())) {
                    flush.run();
                    acc.data = new ArrayList<>(); acc.seriesDecl = new ArrayList<>(); acc.xAxis = null; acc.yAxis = null;
                }
                acc.chartType = line.replaceFirst("(?i)^chartType\\s*[:：]\\s*", "").trim();
                continue;
            }
            if (acc.xAxis == null && line.matches("(?i)^xAxis\\s*[:：].+")) {
                acc.xAxis = line.replaceFirst("(?i)^xAxis\\s*[:：]\\s*", "").trim();
                continue;
            }
            if (line.matches("(?i)^yAxis\\s*[:：].+")) {
                // If unit type switches mid-block (e.g., millions -> percent), treat it as a new chart
                if (acc.yAxis != null && !acc.data.isEmpty()) {
                    flush.run();
                    acc.data = new ArrayList<>(); acc.seriesDecl = new ArrayList<>(); acc.xAxis = null; 
                }
                acc.yAxis = line.replaceFirst("(?i)^yAxis\\s*[:：]\\s*", "").trim();
                continue;
            }
            if (line.matches("(?i)^\\|?\\s*series\\s*[:：].+")) {
                String rawSeries = line.replaceFirst("(?i)^\\|?\\s*series\\s*[:：]\\s*", "").trim();
                Matcher pm = Pattern.compile("^\\(?\\s*([^()]+?)\\s*\\)?$").matcher(rawSeries);
                if (pm.find()) rawSeries = pm.group(1);
                String[] parts = rawSeries.split("\\s*\\|\\s*|\\s*,\\s*");
                for (String p : parts) if (!p.isBlank()) acc.seriesDecl.add(p.trim());
                continue;
            }
            Matcher dm = pData.matcher(line);
            if (dm.matches()) {
                String label = dm.group(1).trim();
                double value = Double.parseDouble(dm.group(2));
                String unit = dm.group(3).trim();
                acc.data.add(new Datum(label, value, unit));
            }
        }

        if (acc.data.isEmpty()) return result;
        // Flush last accumulated chart (if any)
        flush.run();
        return result;
    }

    private static boolean chartExists(List<Map<String, Object>> charts, Map<String, Object> candidate) {
        String title = String.valueOf(candidate.getOrDefault("title", "")).trim();
        String type = String.valueOf(candidate.getOrDefault("type", "")).trim().toLowerCase(Locale.ROOT);
        for (Map<String, Object> c : charts) {
            String t = String.valueOf(c.getOrDefault("title", "")).trim();
            String ty = String.valueOf(c.getOrDefault("type", "")).trim().toLowerCase(Locale.ROOT);
            if (title.equalsIgnoreCase(t) && type.equals(ty)) return true;
        }
        return false;
    }

    private static Map<String, Object> parseTableBlock(String block, int passageIndex) {
        if (block == null || block.isBlank()) return null;
        String[] lines = block.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        String title = null;
        List<String[]> tableLines = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (title == null && line.matches("(?i)^tableTitle\\s*[:：].+")) {
                title = line.replaceFirst("(?i)^tableTitle\\s*[:：]\\s*", "").trim();
                continue;
            }
            if (line.contains("|")) {
                String[] cells = parseMarkdownRow(line);
                if (cells.length >= 2) tableLines.add(cells);
            }
        }
        if (tableLines.size() < 2) return null;
        int sepIdx = -1;
        for (int i = 0; i < tableLines.size(); i++) {
            String[] r = tableLines.get(i);
            boolean sep = true;
            for (String c : r) {
                String t = c.trim();
                if (!t.isEmpty() && !t.matches("[-:]+")) { sep = false; break; }
            }
            if (sep) { sepIdx = i; break; }
        }
        String[] headers = sepIdx > 0 ? tableLines.get(0) : tableLines.get(0);
        List<String[]> rows = new ArrayList<>();
        List<String[]> src = sepIdx > 0 ? tableLines.subList(sepIdx + 1, tableLines.size()) : tableLines.subList(1, tableLines.size());
        for (String[] r : src) rows.add(r);

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("title", title);
        table.put("headers", Arrays.asList(headers));
        List<List<String>> rowList = new ArrayList<>();
        for (String[] r : rows) rowList.add(Arrays.asList(r));
        table.put("rows", rowList);
        table.put("meta", meta(passageIndex, block, null));
        return table;
    }

    private static String[] parseMarkdownRow(String line) {
        // Split by '|' and trim, ignore leading/trailing pipe
        String[] raw = line.split("\\|");
        List<String> cells = new ArrayList<>();
        for (int i = 1; i < raw.length - (raw[raw.length - 1].isEmpty() ? 1 : 0); i++) {
            cells.add(raw[i].trim());
        }
        return cells.toArray(new String[0]);
    }

    private static Map<String, Object> pieChart(String title, String suffix, String unit,
                                                List<Datum> data, int passageIndex, String rawBlock, String yAxis) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("id", UUID.randomUUID().toString());
        chart.put("title", suffix == null || suffix.isBlank() ? title : (title == null ? suffix : title + " · " + suffix));
        chart.put("type", "pie");
        chart.put("unit", unit);
        List<Map<String, Object>> series = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Datum d : data) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("label", d.label);
            it.put("value", d.value);
            items.add(it);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", "default");
        s.put("data", items);
        series.add(s);
        chart.put("series", series);
        chart.put("meta", meta(passageIndex, rawBlock, yAxis));
        return chart;
    }

    private static Map<String, Object> meta(int passageIndex, String rawBlock, String yAxis) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourcePassageIndex", passageIndex);
        meta.put("rawBlock", rawBlock);
        if (yAxis != null) meta.put("yAxis", yAxis);
        return meta;
    }

    private static String classifyUnit(String yAxis, List<Datum> data) {
        String base = yAxis == null ? "" : yAxis.toLowerCase(Locale.ROOT);
        if (base.contains("percent")) return "percent";
        if (base.contains("million")) return "million";
        if (base.contains("thousand")) return "thousand";
        // Fallback to infer from the first datum's unit suffix
        for (Datum d : data) {
            String u = d.unit == null ? "" : d.unit.toLowerCase(Locale.ROOT);
            if (u.contains("%") || u.contains("percent")) return "percent";
            if (u.contains("million")) return "million";
            if (u.contains("thousand")) return "thousand";
        }
        return "count";
    }

    private static class Datum {
        String label; double value; String unit;
        Datum(String label, double value, String unit) {
            this.label = label; this.value = value; this.unit = unit;
        }
    }
}
