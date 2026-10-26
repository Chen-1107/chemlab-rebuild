package com.chemlab.model;

import com.chemlab.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一条实验记录。字段与 SQLite 表 records 及 Python 归纳分析模块保持一致。 */
public class Record {

    public long id;
    public String title = "";
    /** 实验类型：titration / spectro / ph / calib / grav / complex / redox / other */
    public String type = "other";
    /** 实验日期，ISO 格式 yyyy-MM-dd，便于排序与趋势回归 */
    public String date = "";
    public String purpose = "";
    public String dataText = "";
    public String conclusion = "";
    /** 逗号分隔的标签 */
    public String tags = "";
    /** 代表值（如测得浓度、吸光度），可为 null */
    public Double resultValue;
    public String createdAt = "";
    public String updatedAt = "";

    public Record() {
    }

    public static final String[] TYPE_KEYS = {
            "titration", "spectro", "ph", "calib", "grav", "complex", "redox", "other"
    };

    public static final String[] TYPE_LABELS = {
            "滴定分析", "光度分析", "pH 测定", "标定曲线", "重量分析", "络合滴定", "氧化还原滴定", "其他"
    };

    public static String labelOf(String type) {
        for (int i = 0; i < TYPE_KEYS.length; i++) {
            if (TYPE_KEYS[i].equals(type)) {
                return TYPE_LABELS[i];
            }
        }
        return type == null || type.isEmpty() ? "其他" : type;
    }

    /** 转为 Python 分析服务可识别的字典。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("type", type);
        m.put("date", date);
        m.put("purpose", purpose);
        m.put("dataText", dataText);
        m.put("conclusion", conclusion);
        m.put("tags", tags);
        m.put("resultValue", resultValue);
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    @Override
    public String toString() {
        return title == null || title.isEmpty() ? ("#" + id) : title;
    }
}