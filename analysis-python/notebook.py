"""实验记录本归纳分析：把零散的实验记录汇总成可读的统计结论。

输入是一组实验记录（字典列表，字段与数据库一致），输出包含：
    - 概览：记录总数、类型分布、时间跨度
    - 数值统计：从记录的数据/结果文本中抽取数值，做描述统计
    - 分组统计：按实验类型分组，给出各组的均值、RSD、异常值
    - 趋势分析：按日期排序做线性回归，判断结果是否漂移
    - 结论建议：基于统计量生成的文字判断

统计计算全部下沉到 C++ 内核（cbridge），Python 只负责文本解析与编排。
"""

from __future__ import annotations

import re
from collections import Counter, defaultdict
from datetime import datetime
from typing import Dict, Iterable, List, Optional, Sequence

import cbridge

# 匹配形如 0.1000、22.5、1.8e-5、-3.2 的数值
_NUMBER_RE = re.compile(r"[-+]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?")

# 日期/时间：抽取数值前先剔除，否则 "2026-03-01" 会被切成 -3、-1 等假测量值
_DATETIME_LIKE = re.compile(
    r"\d{4}\s*[-/年]\s*\d{1,2}\s*[-/月]\s*\d{1,2}\s*日?"
    r"(?:[ T]\d{1,2}:\d{2}(?::\d{2})?)?"
)

# 排除明显不是测量值的数字（年份、编号等）
_YEAR_LIKE = re.compile(r"^(19|20)\d{2}$")

TYPE_LABELS = {
    "titration": "滴定分析",
    "spectro": "光度分析",
    "ph": "pH测定",
    "calib": "标定曲线",
    "grav": "重量分析",
    "complex": "络合滴定",
    "redox": "氧化还原滴定",
    "other": "其他",
}


def type_label(t: str) -> str:
    return TYPE_LABELS.get(t, t or "其他")


def extract_numbers(text: Optional[str], min_abs: float = 0.0) -> List[float]:
    """从自由文本中抽取数值。

    跳过形如年份的整数，避免把「2026-03-05」里的年份当作测量值。
    """
    if not text:
        return []
    text = _DATETIME_LIKE.sub(" ", text)
    out: List[float] = []
    for m in _NUMBER_RE.finditer(text):
        tok = m.group(0)
        if _YEAR_LIKE.match(tok):
            continue
        try:
            v = float(tok)
        except ValueError:
            continue
        if abs(v) >= min_abs:
            out.append(v)
    return out


def _parse_date(s: Optional[str]) -> Optional[datetime]:
    if not s:
        return None
    for fmt in ("%Y-%m-%d", "%Y/%m/%d", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(s.strip(), fmt)
        except ValueError:
            continue
    return None


def _safe(fn, *args, **kwargs):
    """内核调用包装：失败时返回 None 而不是让整个归纳流程中断。"""
    try:
        return fn(*args, **kwargs)
    except Exception:
        return None


def _group_values(records: Sequence[Dict]) -> Dict[str, List[float]]:
    """按实验类型汇总每条记录的代表值。

    代表值优先取记录显式给出的 result_value，其次取数据文本中数值的均值。
    """
    groups: Dict[str, List[float]] = defaultdict(list)
    for r in records:
        t = r.get("type") or "other"
        v = r.get("resultValue")
        if v is None:
            nums = extract_numbers(r.get("dataText") or "")
            if not nums:
                continue
            v = sum(nums) / len(nums)
        try:
            groups[t].append(float(v))
        except (TypeError, ValueError):
            continue
    return groups


def _overview(records: Sequence[Dict]) -> Dict:
    types = Counter((r.get("type") or "other") for r in records)
    dates = [d for d in (_parse_date(r.get("date")) for r in records) if d]
    span = None
    if dates:
        lo, hi = min(dates), max(dates)
        span = {
            "start": lo.strftime("%Y-%m-%d"),
            "end": hi.strftime("%Y-%m-%d"),
            "days": (hi - lo).days,
        }
    tagged = sum(1 for r in records if r.get("tags"))
    return {
        "total": len(records),
        "typeCounts": {type_label(k): v for k, v in types.most_common()},
        "dateSpan": span,
        "taggedCount": tagged,
    }


def _group_stats(groups: Dict[str, List[float]]) -> List[Dict]:
    core = cbridge.try_get_core()
    out: List[Dict] = []
    for t, vals in groups.items():
        item: Dict = {
            "type": t,
            "typeLabel": type_label(t),
            "count": len(vals),
            "values": [round(v, 6) for v in vals],
        }
        if core and len(vals) >= 1:
            item["stats"] = {k: round(v, 6) for k, v in core.describe(vals).items()}
        if core and len(vals) >= 3:
            g = _safe(core.grubbs, vals, 0.05)
            if g:
                item["outlier"] = {
                    "index": g["outlierIndex"],
                    "value": round(g["value"], 6),
                    "isOutlier": g["outlierIndex"] >= 0,
                }
        if core and len(vals) >= 2:
            ci = _safe(core.confidence_interval, vals, 0.95)
            if ci:
                item["ci95"] = {k: round(v, 6) for k, v in ci.items()}
        out.append(item)
    out.sort(key=lambda x: -x["count"])
    return out


def _trend(records: Sequence[Dict]) -> Optional[Dict]:
    """按日期排序对代表值做线性回归，判断是否存在系统性漂移。"""
    points = []
    for r in records:
        d = _parse_date(r.get("date"))
        v = r.get("resultValue")
        if v is None:
            nums = extract_numbers(r.get("dataText") or "")
            if not nums:
                continue
            v = sum(nums) / len(nums)
        if d is None:
            continue
        try:
            points.append((d, float(v)))
        except (TypeError, ValueError):
            continue

    if len(points) < 3:
        return None

    points.sort(key=lambda p: p[0])
    base = points[0][0]
    xs = [(p[0] - base).days for p in points]
    ys = [p[1] for p in points]

    core = cbridge.try_get_core()
    if not core:
        return {"count": len(points), "note": "内核不可用，未做回归"}

    reg = _safe(core.linear_regression, xs, ys)
    if not reg:
        return {"count": len(points), "note": "数据退化，无法回归"}

    slope = reg["slope"]
    rel = abs(slope) * (xs[-1] - xs[0]) / (abs(reg["intercept"]) + 1e-12) if xs[-1] > xs[0] else 0.0

    if reg["r2"] < 0.3:
        judgment = "无明显趋势（相关性弱）"
    elif slope > 0:
        judgment = "呈上升趋势"
    else:
        judgment = "呈下降趋势"

    return {
        "count": len(points),
        "slopePerDay": round(slope, 8),
        "intercept": round(reg["intercept"], 6),
        "r2": round(reg["r2"], 6),
        "relativeDrift": round(rel, 6),
        "judgment": judgment,
        "range": {"start": points[0][0].strftime("%Y-%m-%d"),
                  "end": points[-1][0].strftime("%Y-%m-%d")},
    }


def _insights(overview: Dict, groups: List[Dict], trend: Optional[Dict]) -> List[str]:
    tips: List[str] = []

    total = overview["total"]
    if total == 0:
        return ["暂无实验记录，先录入数据后再做归纳分析。"]

    tips.append(f"共 {total} 条记录，覆盖 {len(overview['typeCounts'])} 类实验。")

    if overview["dateSpan"]:
        sp = overview["dateSpan"]
        tips.append(f"记录时间跨度 {sp['start']} 至 {sp['end']}（{sp['days']} 天）。")

    for g in groups:
        st = g.get("stats")
        if not st:
            continue
        if st["rsd"] > 5.0 and g["count"] >= 3:
            tips.append(
                f"{g['typeLabel']} 共 {g['count']} 次，RSD = {st['rsd']:.2f}%，"
                f"离散度偏大，建议核查操作一致性或仪器稳定性。"
            )
        elif g["count"] >= 3:
            tips.append(
                f"{g['typeLabel']} 共 {g['count']} 次，均值 {st['mean']:.6g}，"
                f"RSD = {st['rsd']:.2f}%，重复性良好。"
            )

        out = g.get("outlier")
        if out and out["isOutlier"]:
            tips.append(
                f"{g['typeLabel']} 中第 {out['index'] + 1} 个数据（{out['value']:.6g}）"
                f"经 Grubbs 检验判定为离群值（α=0.05），建议复核原始记录。"
            )

    if trend:
        if trend.get("r2", 0) >= 0.3:
            tips.append(
                f"结果随时间的回归趋势：{trend['judgment']}，"
                f"斜率 {trend['slopePerDay']:.6g}/天，r² = {trend['r2']:.3f}。"
                f"若为标定或质控数据，建议关注是否存在系统漂移。"
            )
        else:
            tips.append("结果随时间未呈现明显趋势，数据一致性较好。")

    if overview["taggedCount"] == 0:
        tips.append("当前记录均未打标签，添加标签有助于后续按项目或样品批量检索。")

    return tips


def summarize(records: Sequence[Dict]) -> Dict:
    """生成归纳总结。records 为记录字典序列。"""
    records = list(records or [])
    overview = _overview(records)
    groups = _group_stats(_group_values(records))
    trend = _trend(records)
    return {
        "overview": overview,
        "groups": groups,
        "trend": trend,
        "insights": _insights(overview, groups, trend),
        "generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "coreAvailable": cbridge.try_get_core() is not None,
    }


def search(records: Sequence[Dict], keyword: str) -> List[Dict]:
    """在标题、目的、数据、结论、标签中做不区分大小写的检索。"""
    if not keyword:
        return list(records)
    kw = keyword.lower()
    hits = []
    for r in records:
        blob = " ".join(str(r.get(f) or "") for f in
                        ("title", "purpose", "dataText", "conclusion", "tags", "type"))
        if kw in blob.lower():
            hits.append(r)
    return hits


if __name__ == "__main__":
    demo = [
        {"title": "盐酸标定 #1", "type": "titration", "date": "2026-03-01",
         "dataText": "标准液浓度 0.1000 mol/L，滴定体积 22.50 mL", "resultValue": 0.0900},
        {"title": "盐酸标定 #2", "type": "titration", "date": "2026-03-08",
         "dataText": "标准液浓度 0.1000 mol/L，滴定体积 22.48 mL", "resultValue": 0.0899},
        {"title": "盐酸标定 #3", "type": "titration", "date": "2026-03-15",
         "dataText": "标准液浓度 0.1000 mol/L，滴定体积 22.55 mL", "resultValue": 0.0902},
        {"title": "盐酸标定 #4", "type": "titration", "date": "2026-03-22",
         "dataText": "标准液浓度 0.1000 mol/L，滴定体积 23.90 mL", "resultValue": 0.0956},
        {"title": "铁含量测定", "type": "spectro", "date": "2026-03-20",
         "dataText": "吸光度 0.450，ε=1.5e4", "resultValue": 3.0e-5},
    ]
    s = summarize(demo)
    print("概览:", s["overview"])
    print("分组数:", len(s["groups"]))
    for g in s["groups"]:
        print("  -", g["typeLabel"], "n=", g["count"], "stats=", g.get("stats"),
              "outlier=", g.get("outlier"))
    print("趋势:", s["trend"])
    print("结论:")
    for t in s["insights"]:
        print("  *", t)