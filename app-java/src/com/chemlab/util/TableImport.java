package com.chemlab.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.xml.sax.SAXException;

/**
 * 表格文件读取：CSV/TXT 与 XLSX，零第三方依赖。
 *
 * <p>CSV 按字节探测编码（UTF-8 BOM / UTF-16 BOM / 严格 UTF-8 校验 / GBK 回退），
 * 中文 Excel 导出的 ANSI 文件也能正确读出。XLSX 本质是 zip 包里的 XML，
 * 这里直接用 JDK 自带的 {@link ZipFile} + DOM 解析第一张工作表，
 * 不引入 Apache POI 之类的重型依赖。
 *
 * <p>不支持旧版二进制 .xls（建议先在 Excel 里另存为 .xlsx）。
 */
public final class TableImport {

    /** 解析结果：纯文本单元格矩阵（已去掉完全空白的行）。 */
    public static final class Table {
        public final List<String[]> rows = new ArrayList<>();
        public int columns;
        public String name = "";

        public int rowCount() {
            return rows.size();
        }
    }

    private TableImport() {
    }

    /** 按扩展名分派：.xlsx 走 zip 解析，其余按分隔符文本处理。 */
    public static Table read(File f) throws IOException {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".xlsx")) {
            return readXlsx(f);
        }
        if (n.endsWith(".xls")) {
            throw new IOException("暂不支持旧版 .xls，请在 Excel 中另存为 .xlsx 后再导入");
        }
        return readDelimited(f);
    }

    // ------------------------------------------------------------------
    // CSV / TXT
    // ------------------------------------------------------------------

    private static Table readDelimited(File f) throws IOException {
        byte[] bytes = Files.readAllBytes(f.toPath());
        String text = decode(bytes);
        char delim = detectDelimiter(text);

        Table t = new Table();
        t.name = f.getName();
        for (String[] row : parseCsv(text, delim)) {
            boolean blank = true;
            for (String cell : row) {
                if (!cell.trim().isEmpty()) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                continue;
            }
            t.rows.add(row);
            t.columns = Math.max(t.columns, row.length);
        }
        if (t.rows.isEmpty()) {
            throw new IOException("文件中没有数据行");
        }
        return t;
    }

    /** 字节级编码探测：BOM 优先，其次严格 UTF-8 校验，失败回退 GBK。 */
    private static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /** 统计首个非空行中各候选分隔符的出现次数，取最多者；默认逗号。 */
    private static char detectDelimiter(String text) {
        char[] cands = {',', ';', '\t'};
        char best = ',';
        int bestCount = 0;
        for (String line : text.split("\\r?\\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            for (char c : cands) {
                int count = 0;
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) == c) {
                        count++;
                    }
                }
                if (count > bestCount) {
                    bestCount = count;
                    best = c;
                }
            }
            break;
        }
        return best;
    }

    /** 带引号状态的 CSV 解析：支持 "a,b"、"" 转义、\r\n / \n / \r 换行。 */
    private static List<String[]> parseCsv(String text, char delim) {
        List<String[]> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        sb.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                    i++;
                    continue;
                }
                sb.append(c);
                i++;
            } else if (c == '"') {
                quoted = true;
                i++;
            } else if (c == delim) {
                cells.add(sb.toString());
                sb.setLength(0);
                i++;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(sb.toString());
                sb.setLength(0);
                rows.add(cells.toArray(new String[0]));
                cells.clear();
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        if (sb.length() > 0 || !cells.isEmpty()) {
            cells.add(sb.toString());
            rows.add(cells.toArray(new String[0]));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // XLSX（纯 JDK：ZipFile + DOM）
    // ------------------------------------------------------------------

    private static Table readXlsx(File f) throws IOException {
        try (ZipFile zip = new ZipFile(f)) {
            List<String> shared = readSharedStrings(zip);
            String sheetPath = firstSheetPath(zip);
            ZipEntry entry = zip.getEntry(sheetPath);
            if (entry == null) {
                throw new IOException("工作簿中找不到工作表：" + sheetPath);
            }
            Table t = new Table();
            t.name = f.getName();
            try (InputStream in = zip.getInputStream(entry)) {
                parseSheet(in, shared, t);
            }
            if (t.rows.isEmpty()) {
                throw new IOException("第一张工作表没有数据");
            }
            return t;
        }
    }

    private static List<String> readSharedStrings(ZipFile zip) throws IOException {
        List<String> out = new ArrayList<>();
        ZipEntry e = zip.getEntry("xl/sharedStrings.xml");
        if (e == null) {
            return out;
        }
        Document doc;
        try (InputStream in = zip.getInputStream(e)) {
            doc = parseXml(in);
        }
        NodeList sis = doc.getElementsByTagName("si");
        for (int i = 0; i < sis.getLength(); i++) {
            Element si = (Element) sis.item(i);
            NodeList ts = si.getElementsByTagName("t");
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < ts.getLength(); j++) {
                sb.append(ts.item(j).getTextContent());
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** 取工作簿声明的第一张表；解析失败时回退 sheet1.xml。 */
    private static String firstSheetPath(ZipFile zip) {
        try {
            ZipEntry wb = zip.getEntry("xl/workbook.xml");
            if (wb == null) {
                return "xl/worksheets/sheet1.xml";
            }
            Document doc;
            try (InputStream in = zip.getInputStream(wb)) {
                doc = parseXml(in);
            }
            NodeList sheets = doc.getElementsByTagName("sheet");
            if (sheets.getLength() > 0) {
                Element s = (Element) sheets.item(0);
                String rid = s.getAttribute("r:id");
                if (rid.isEmpty()) {
                    rid = s.getAttribute("id");
                }
                if (!rid.isEmpty()) {
                    String target = relTarget(zip, rid);
                    if (target != null && !target.isEmpty()) {
                        if (target.startsWith("/")) {
                            return target.substring(1);
                        }
                        return "xl/" + target;
                    }
                }
            }
        } catch (Exception ignore) {
            // 回退默认路径
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static String relTarget(ZipFile zip, String rid) {
        try {
            ZipEntry rels = zip.getEntry("xl/_rels/workbook.xml.rels");
            if (rels == null) {
                return null;
            }
            Document doc;
            try (InputStream in = zip.getInputStream(rels)) {
                doc = parseXml(in);
            }
            NodeList list = doc.getElementsByTagName("Relationship");
            for (int i = 0; i < list.getLength(); i++) {
                Element el = (Element) list.item(i);
                if (rid.equals(el.getAttribute("Id"))) {
                    return el.getAttribute("Target");
                }
            }
        } catch (Exception ignore) {
            // 回退默认路径
        }
        return null;
    }

    private static void parseSheet(InputStream in, List<String> shared, Table t)
            throws IOException {
        Document doc = parseXml(in);
        NodeList rowList = doc.getElementsByTagName("row");
        for (int r = 0; r < rowList.getLength(); r++) {
            Element rowEl = (Element) rowList.item(r);
            NodeList cells = rowEl.getElementsByTagName("c");
            if (cells.getLength() == 0) {
                continue;
            }
            List<String> line = new ArrayList<>();
            int fallbackCol = 0;
            for (int c = 0; c < cells.getLength(); c++) {
                Element cellEl = (Element) cells.item(c);
                int col = colIndex(cellEl.getAttribute("r"));
                if (col < 0) {
                    col = fallbackCol;
                }
                fallbackCol = col + 1;
                while (line.size() < col + 1) {
                    line.add("");
                }
                line.set(col, cellValue(cellEl, shared));
            }
            int last = line.size();
            while (last > 0 && line.get(last - 1).trim().isEmpty()) {
                last--;
            }
            if (last == 0) {
                continue;
            }
            t.rows.add(line.subList(0, last).toArray(new String[0]));
            t.columns = Math.max(t.columns, last);
        }
    }

    /** "B3" → 1；无引用时返回 -1（由调用方按顺序补位）。 */
    private static int colIndex(String ref) {
        if (ref == null || ref.isEmpty()) {
            return -1;
        }
        int idx = 0;
        int i = 0;
        for (; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                idx = idx * 26 + (ch - 'A' + 1);
            } else if (ch >= 'a' && ch <= 'z') {
                idx = idx * 26 + (ch - 'a' + 1);
            } else {
                break;
            }
        }
        return i == 0 ? -1 : idx - 1;
    }

    private static String cellValue(Element cellEl, List<String> shared) {
        String type = cellEl.getAttribute("t");
        if ("inlineStr".equals(type)) {
            NodeList ts = cellEl.getElementsByTagName("t");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ts.getLength(); i++) {
                sb.append(ts.item(i).getTextContent());
            }
            return sb.toString();
        }
        NodeList vs = cellEl.getElementsByTagName("v");
        if (vs.getLength() == 0) {
            return "";
        }
        String raw = vs.item(0).getTextContent().trim();
        if ("s".equals(type)) {
            try {
                int idx = Integer.parseInt(raw);
                return idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return raw;
    }

    /** 关闭外部实体的安全 DOM 解析（防范 XXE）。 */
    private static Document parseXml(InputStream in) throws IOException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException ignore) {
            // 解析器不支持该特性时继续，文件来自用户本地
        }
        f.setExpandEntityReferences(false);
        try {
            return f.newDocumentBuilder().parse(in);
        } catch (SAXException e) {
            throw new IOException("XML 解析失败：" + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new IOException("XML 解析器不可用：" + e.getMessage(), e);
        }
    }
}
