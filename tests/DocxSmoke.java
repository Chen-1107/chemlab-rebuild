import com.chemlab.util.DocxWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;

/**
 * Word 报告导出冒烟测试：
 * 1. 用 DocxWriter 生成包含全部版式的 .docx；
 * 2. 以 zip 重新打开，确认三个必需部件齐全；
 * 3. 用 JDK XML 解析器校验每个部件的 XML 良构；
 * 4. 确认中文字符经 UTF-8 往返后完整保留。
 */
public class DocxSmoke {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "DocxSmoke-report.docx");

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"酸碱滴定", "3", "0.1024"});
        rows.add(new String[]{"分光光度", "2", "—"});

        try (DocxWriter w = DocxWriter.open(out)) {
            w.title("分析化学实验报告")
                    .subtitle("DocxSmoke 自检报告")
                    .labeled("实验人", "测试用户")
                    .heading("一、实验概览")
                    .table(new String[]{"实验类型", "记录数", "代表值均值"}, rows)
                    .paragraph("说明：本段正文用于校验 UTF-8 往返。特殊字符：<tag> & \"引号\" ≥ 25.0 ℃")
                    .heading("二、数据记录")
                    .dataBlock("编号  体积/mL   pH\n1     10.00     4.20\n2     20.00     5.60")
                    .paragraph("—— 报告结束 ——");
        }

        if (!Files.exists(out) || Files.size(out) < 500) {
            System.out.println("生成的 docx 文件异常（大小 " + Files.size(out) + " 字节）");
            System.exit(1);
        }

        Map<String, String> parts = new HashMap<>();
        try (ZipFile zip = new ZipFile(out.toFile())) {
            for (String need : new String[]{"[Content_Types].xml", "_rels/.rels", "word/document.xml"}) {
                ZipEntry e = zip.getEntry(need);
                if (e == null) {
                    System.out.println("缺少部件: " + need);
                    System.exit(1);
                }
                parts.put(need, new String(zip.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        for (Map.Entry<String, String> p : parts.entrySet()) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document d = f.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(p.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (d.getDocumentElement() == null) {
                System.out.println("XML 无根元素: " + p.getKey());
                System.exit(1);
            }
        }

        String doc = parts.get("word/document.xml");
        for (String expect : new String[]{
                "分析化学实验报告", "实验人", "酸碱滴定", "0.1024",
                "≥ 25.0 ℃", "—— 报告结束 ——", "&lt;tag&gt;"}) {
            if (!doc.contains(expect)) {
                System.out.println("正文缺少预期内容: " + expect);
                System.exit(1);
            }
        }

        System.out.println("Word 报告自检通过: " + out.toAbsolutePath());
    }
}
