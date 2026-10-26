import com.chemlab.data.Database;
import com.chemlab.data.RecordDao;
import com.chemlab.model.Record;
import com.chemlab.service.PythonBridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 全链路冒烟测试：SQLite 数据层 + Python 常驻服务桥接。 */
public class Smoke {
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("chemlab-smoke");
        Path dbFile = tmp.resolve("t.db");

        try (Database db = Database.open(dbFile)) {
            RecordDao dao = new RecordDao(db);
            Record r = new Record();
            r.title = "盐酸标定 #1";
            r.type = "titration";
            r.date = "2026-03-01";
            r.dataText = "标准液 0.1000 mol/L，滴定体积 22.50 mL";
            r.tags = "标定,盐酸";
            r.resultValue = 0.0900;
            long id = dao.insert(r);
            System.out.println("insert id=" + id + " count=" + dao.count());

            Record r2 = new Record();
            r2.title = "铁含量测定";
            r2.type = "spectro";
            r2.date = "2026-03-09";
            r2.dataText = "吸光度 0.450";
            r2.resultValue = 3.0e-5;
            dao.insert(r2);

            System.out.println("list=" + dao.listAll().size());
            System.out.println("search(铁)=" + dao.search("铁").size());

            r.title = "盐酸标定 #1（修订）";
            dao.update(r);
            System.out.println("updated title=" + dao.search("修订").get(0).title);
            dao.delete(id);
            System.out.println("after delete count=" + dao.count());
        }

        PythonBridge bridge = PythonBridge.start();
        System.out.println("python=" + bridge.interpreter() + " available=" + bridge.isAvailable());
        System.out.println("ping=" + bridge.call("ping", null));

        List<Object> recs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", "标定 #" + (i + 1));
            m.put("type", "titration");
            m.put("date", "2026-03-0" + (1 + i * 4));
            m.put("dataText", "体积 " + (22.50 + i * 0.03) + " mL");
            m.put("resultValue", 0.09 + i * 0.0001);
            recs.add(m);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("records", recs);
        Map<String, Object> res = bridge.call("notebook.summarize", params);
        List<Object> insights = com.chemlab.util.Json.asArray(res.get("insights"));
        System.out.println("insights=" + insights.size());
        for (Object o : insights) {
            System.out.println("  * " + o);
        }
        bridge.close();
        System.out.println("closed, alive=" + bridge.isAvailable());

        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(tmp);
        System.out.println("SMOKE OK");
    }
}