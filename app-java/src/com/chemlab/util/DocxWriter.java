package com.chemlab.util;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 最小 .docx 生成器：仅用 JDK（ZipOutputStream + 手写 OOXML），
 * 不引入任何第三方库。支持标题、章节、正文、表格与多行数据段，
 * 中文以宋体/黑体作为东亚字体声明，Word/WPS 打开即用。
 */
public final class DocxWriter implements Closeable {

    private static final String NS_OPEN =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body>";
    private static final String NS_CLOSE =
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                    + "<w:mar w:top=\"1440\" w:right=\"1418\" w:bottom=\"1440\" w:left=\"1418\""
                    + " w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/></w:sectPr>"
                    + "</w:body></w:document>";

    private final ZipOutputStream zip;
    private final StringBuilder body = new StringBuilder(64 * 1024);

    private DocxWriter(ZipOutputStream zip) {
        this.zip = zip;
    }

    /** 创建并打开目标文件（会覆盖已存在的同名文件）。 */
    public static DocxWriter open(Path file) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8);
        put(zip, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                        + "</Types>");
        put(zip, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                        + "</Relationships>");
        return new DocxWriter(zip);
    }

    // ------------------------------------------------------------------
    // 内容 API
    // ------------------------------------------------------------------

    /** 报告封面大标题：居中、黑体、二号。 */
    public DocxWriter title(String text) {
        body.append("<w:p><w:pPr><w:spacing w:after=\"120\"/><w:jc w:val=\"center\"/></w:pPr>")
                .append(run(text, "黑体", 44, true, null))
                .append("</w:p>");
        return this;
    }

    /** 封面副标题 / 说明行：居中小字灰色。 */
    public DocxWriter subtitle(String text) {
        body.append("<w:p><w:pPr><w:spacing w:after=\"240\"/><w:jc w:val=\"center\"/></w:pPr>")
                .append(run(text, "宋体", 21, false, "595959"))
                .append("</w:p>");
        return this;
    }

    /** 章节标题：黑体四号，段前段后留空。 */
    public DocxWriter heading(String text) {
        body.append("<w:p><w:pPr><w:spacing w:before=\"280\" w:after=\"140\"/></w:pPr>")
                .append(run(text, "黑体", 28, true, null))
                .append("</w:p>");
        return this;
    }

    /** 正文段落：宋体小四，1.5 倍行距感（after=120）。 */
    public DocxWriter paragraph(String text) {
        body.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr>")
                .append(run(text, "宋体", 24, false, null))
                .append("</w:p>");
        return this;
    }

    /** 「加粗标签：内容」行，用于字段式信息。 */
    public DocxWriter labeled(String label, String value) {
        body.append("<w:p><w:pPr><w:spacing w:after=\"100\"/></w:pPr>")
                .append(run(label + "：", "宋体", 24, true, null))
                .append(run(value == null ? "" : value, "宋体", 24, false, null))
                .append("</w:p>");
        return this;
    }

    /** 数据/代码块样式：等宽西文 + 缩进 + 浅灰底色的多行段落。 */
    public DocxWriter dataBlock(String multiLine) {
        String[] lines = multiLine == null ? new String[0]
                : multiLine.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        for (String line : lines) {
            // OOXML schema 要求 pPr 子元素顺序：shd → spacing → ind
            body.append("<w:p><w:pPr>")
                    .append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F5F5F5\"/>")
                    .append("<w:spacing w:after=\"20\"/><w:ind w:left=\"360\"/></w:pPr>")
                    .append(run(line.isEmpty() ? " " : line, "宋体", 21, false, null))
                    .append("</w:p>");
        }
        body.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:p>");
        return this;
    }

    /** 带边框的数据表格：表头浅灰底加粗，列宽均分可用宽度。 */
    public DocxWriter table(String[] headers, List<String[]> rows) {
        int cols = Math.max(1, headers.length);
        int colW = 8106 / cols;

        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>")
                .append("<w:tblBorders>")
                .append(border("top")).append(border("left")).append(border("bottom"))
                .append(border("right")).append(border("insideH")).append(border("insideV"))
                .append("</w:tblBorders></w:tblPr><w:tblGrid>");
        for (int c = 0; c < cols; c++) {
            body.append("<w:gridCol w:w=\"").append(colW).append("\"/>");
        }
        body.append("</w:tblGrid>");

        body.append("<w:tr>");
        for (String h : headers) {
            body.append(cell(h, true, "EDEDED"));
        }
        body.append("</w:tr>");
        for (String[] row : rows) {
            body.append("<w:tr>");
            for (int c = 0; c < cols; c++) {
                body.append(cell(c < row.length ? row[c] : "", false, null));
            }
            body.append("</w:tr>");
        }
        body.append("</w:tbl><w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:p>");
        return this;
    }

    // ------------------------------------------------------------------

    @Override
    public void close() throws IOException {
        put(zip, "word/document.xml", NS_OPEN + body + NS_CLOSE);
        zip.close();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static void put(ZipOutputStream zip, String entry, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String border(String edge) {
        return "<w:" + edge + " w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>";
    }

    private static String cell(String text, boolean bold, String fill) {
        StringBuilder sb = new StringBuilder("<w:tc><w:tcPr>")
                .append("<w:tcW w:w=\"0\" w:type=\"auto\"/>");
        if (fill != null) {
            sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
        }
        sb.append("</w:tcPr>");
        sb.append("<w:p><w:pPr><w:spacing w:after=\"40\"/></w:pPr>")
                .append(run(text, "宋体", 21, bold, null))
                .append("</w:p></w:tc>");
        return sb.toString();
    }

    /** 一个文本 run：声明西文/东亚字体、字号（半磅）、加粗与可选颜色。 */
    private static String run(String text, String eastAsia, int halfPt,
                              boolean bold, String color) {
        StringBuilder sb = new StringBuilder("<w:r><w:rPr>")
                .append("<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\"")
                .append(" w:eastAsia=\"").append(eastAsia).append("\"/>")
                .append("<w:sz w:val=\"").append(halfPt).append("\"/>")
                .append("<w:szCs w:val=\"").append(halfPt).append("\"/>");
        if (bold) {
            sb.append("<w:b/>");
        }
        if (color != null) {
            sb.append("<w:color w:val=\"").append(color).append("\"/>");
        }
        sb.append("</w:rPr><w:t xml:space=\"preserve\">")
                .append(escape(text))
                .append("</w:t></w:r>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                case '\t': sb.append("    "); break;
                default:
                    if (ch >= 0x20 || ch == '\n') {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }
}
