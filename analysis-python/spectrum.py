"""光谱图像分析：从谱图截图中用机器视觉方法提取曲线并做定量处理。

典型输入是一张光谱仪导出图或手机拍摄的谱图。处理流程：

    读图 → 定位绘图区（坐标轴内框）→ 分离曲线像素 → 逐列提取曲线
         → 调用 C++ 内核做平滑/基线/寻峰/积分 → 输出曲线点、峰位与统计量

坐标约定：图像坐标 y 向下增长，本模块统一转换为「强度」——
以绘图区底边为 0、顶边为 1，便于直接当作相对强度使用。
"""

from __future__ import annotations

import base64
import binascii
import os
from typing import Dict, List, Optional, Sequence, Tuple

import cv2
import numpy as np

import cbridge

try:
    from PIL import Image
except ImportError:  # pragma: no cover - Pillow 缺失时仅影响中文路径读图
    Image = None


class SpectrumError(RuntimeError):
    """光谱图像无法解析时抛出。"""


# --------------------------------------------------------------------------
# 读图
# --------------------------------------------------------------------------

def imread_unicode(path: str) -> np.ndarray:
    """读取图像，兼容 Windows 中文路径。

    cv2.imread 在 Windows 上无法处理非 ASCII 路径，这里改为读字节流再解码。
    """
    if not os.path.isfile(path):
        raise SpectrumError(f"图像文件不存在：{path}")
    data = np.fromfile(path, dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if img is None:
        raise SpectrumError(f"无法解码图像：{path}")
    return img


def imwrite_unicode(path: str, img: np.ndarray) -> None:
    """保存图像，兼容 Windows 中文路径。"""
    ext = os.path.splitext(path)[1] or ".png"
    ok, buf = cv2.imencode(ext, img)
    if not ok:
        raise SpectrumError(f"无法编码图像：{path}")
    buf.tofile(path)


def load_image(src: str) -> np.ndarray:
    """从文件路径或 base64/dataURL 载入图像。"""
    if not src:
        raise SpectrumError("图像源为空")

    if src.startswith("data:image") and "," in src:
        src = src.split(",", 1)[1]
    looks_b64 = len(src) > 512 and not os.path.exists(src)
    if looks_b64:
        try:
            raw = base64.b64decode(src, validate=False)
        except (binascii.Error, ValueError) as e:
            raise SpectrumError(f"base64 解码失败：{e}") from e
        arr = np.frombuffer(raw, dtype=np.uint8)
        img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if img is None:
            raise SpectrumError("无法解码 base64 图像数据")
        return img

    return imread_unicode(src)


# --------------------------------------------------------------------------
# 绘图区定位
# --------------------------------------------------------------------------

def _axis_lines(mask: np.ndarray, min_frac: float = 0.5) -> Tuple[List[int], List[int]]:
    """在二值掩膜中找出贯穿型横线/竖线的位置。"""
    h, w = mask.shape[:2]
    row_frac = mask.sum(axis=1).astype(float) / max(w, 1)
    col_frac = mask.sum(axis=0).astype(float) / max(h, 1)
    rows = [i for i in range(h) if row_frac[i] >= min_frac]
    cols = [j for j in range(w) if col_frac[j] >= min_frac]
    return rows, cols


def _group(indices: Sequence[int], gap: int = 3) -> List[int]:
    """把连续下标归并成一组，返回每组的中心。"""
    if not indices:
        return []
    groups: List[List[int]] = [[indices[0]]]
    for v in indices[1:]:
        if v - groups[-1][-1] <= gap:
            groups[-1].append(v)
        else:
            groups.append([v])
    return [int(round(sum(g) / len(g))) for g in groups]


def detect_plot_area(img: np.ndarray) -> Tuple[int, int, int, int]:
    """定位绘图区内框，返回 (x0, y0, x1, y1)，右/下为开区间。

    优先寻找坐标轴框线；找不到时退化为内容外接矩形。
    """
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    dark = (gray < 180).astype(np.uint8)

    rows, cols = _axis_lines(dark, min_frac=0.45)
    rows = _group(rows)
    cols = _group(cols)

    if len(rows) >= 2 and len(cols) >= 2:
        # 取最外侧的两条横线/竖线作为边框
        y0, y1 = rows[0], rows[-1]
        x0, x1 = cols[0], cols[-1]
        if x1 - x0 > 20 and y1 - y0 > 20:
            return x0 + 1, y0 + 1, x1, y1

    ys, xs = np.nonzero(dark)
    if len(xs) == 0:
        raise SpectrumError("图像中未检测到任何内容")
    x0, x1 = int(xs.min()), int(xs.max()) + 1
    y0, y1 = int(ys.min()), int(ys.max()) + 1
    if x1 - x0 < 20 or y1 - y0 < 20:
        raise SpectrumError("检测到的绘图区过小，请确认图像内容")
    return x0, y0, x1, y1


# --------------------------------------------------------------------------
# 曲线像素分离
# --------------------------------------------------------------------------

def _curve_mask(img: np.ndarray, roi: Tuple[int, int, int, int], mode: str) -> np.ndarray:
    """在 ROI 内生成「属于曲线」的像素掩膜。

    mode:
        dark  —— 深色曲线（灰度阈值）
        color —— 彩色曲线（饱和度阈值）
        auto  —— 依据彩色像素占比自动选择
    """
    x0, y0, x1, y1 = roi
    sub = img[y0:y1, x0:x1]
    hsv = cv2.cvtColor(sub, cv2.COLOR_BGR2HSV)
    sat = hsv[:, :, 1]
    val = hsv[:, :, 2]

    color_frac = float((sat > 60).mean()) if sat.size else 0.0
    if mode == "auto":
        mode = "color" if color_frac > 0.002 else "dark"

    if mode == "color":
        # 彩色曲线：饱和度足够高且不过暗
        mask = ((sat > 60) & (val > 60)).astype(np.uint8)
    else:
        gray = cv2.cvtColor(sub, cv2.COLOR_BGR2GRAY)
        thr = int(np.clip(np.percentile(gray, 3) + 40, 40, 200))
        mask = (gray < thr).astype(np.uint8)

    # 去掉贴边的坐标轴残留：边缘 2 像素清零
    if mask.shape[0] > 4 and mask.shape[1] > 4:
        mask[:2, :] = 0
        mask[-2:, :] = 0
        mask[:, :2] = 0
        mask[:, -2:] = 0
    return mask


def _dominant_colors(sub: np.ndarray, mask: np.ndarray,
                     k: int) -> List[Tuple[int, int, int]]:
    """对掩膜内的像素颜色做 k-means，返回各簇的 BGR 中心色（按像素数降序）。

    sub 与 mask 必须同为 ROI 子图尺寸（二者形状一致），否则布尔索引会错位。
    """
    pixels = sub[mask > 0]
    if len(pixels) < k:
        return []
    data = pixels.astype(np.float32)
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.5)
    _, labels, centers = cv2.kmeans(data, k, None, criteria, 5, cv2.KMEANS_PP_CENTERS)
    counts = np.bincount(labels.flatten(), minlength=k)
    order = np.argsort(-counts)
    return [tuple(int(c) for c in centers[i]) for i in order if counts[i] > 0]


# --------------------------------------------------------------------------
# 逐列提曲线
# --------------------------------------------------------------------------

def _extract_by_color(img: np.ndarray, roi: Tuple[int, int, int, int],
                      color: Tuple[int, int, int], tol: int = 60) -> List[List[float]]:
    """提取指定颜色的曲线：每列取与该颜色最接近的像素，多段时取最强的一段。"""
    x0, y0, x1, y1 = roi
    sub = img[y0:y1, x0:x1].astype(np.int16)
    h, w = sub.shape[:2]
    target = np.array(color, dtype=np.int16)

    dist = np.abs(sub - target).sum(axis=2)
    hit = dist <= tol * 3

    pts: List[List[float]] = []
    height = max(h - 1, 1)
    for col in range(w):
        rows = np.nonzero(hit[:, col])[0]
        if rows.size == 0:
            continue
        # 同列可能有多段（曲线穿越或噪声），取最长连续段的中点
        splits = np.split(rows, np.nonzero(np.diff(rows) > 2)[0] + 1)
        best = max(splits, key=len)
        r = float(best.mean())
        intensity = 1.0 - (r / height)
        pts.append([float(col), round(float(r), 3), round(float(intensity), 6)])
    return pts


def _extract_dark(img: np.ndarray, roi: Tuple[int, int, int, int],
                  smooth_win: int = 3) -> List[List[float]]:
    """提取深色曲线：每列取最上方的深色像素作为曲线位置。"""
    mask = _curve_mask(img, roi, "dark")
    h, w = mask.shape[:2]
    height = max(h - 1, 1)

    pts: List[List[float]] = []
    for col in range(w):
        rows = np.nonzero(mask[:, col])[0]
        if rows.size == 0:
            continue
        r = float(rows[0])
        intensity = 1.0 - (r / height)
        pts.append([float(col), round(r, 3), round(float(intensity), 6)])

    if smooth_win > 1 and len(pts) > smooth_win:
        ys = np.array([p[1] for p in pts], dtype=float)
        kernel = np.ones(smooth_win) / smooth_win
        ys = np.convolve(ys, kernel, mode="same")
        for i, p in enumerate(pts):
            p[1] = round(float(ys[i]), 3)
            p[2] = round(float(1.0 - ys[i] / height), 6)
    return pts


def extract_curves(img: np.ndarray, roi: Optional[Tuple[int, int, int, int]] = None,
                   max_curves: int = 1, mode: str = "auto") -> Tuple[List[Dict], Tuple[int, int, int, int]]:
    """提取 1~max_curves 条曲线。

    多条曲线的场景（同一张图叠加了多个谱图）下，按像素颜色聚类区分。
    """
    if roi is None:
        roi = detect_plot_area(img)
    x0, y0, x1, y1 = roi
    if x1 - x0 < 20 or y1 - y0 < 20:
        raise SpectrumError("绘图区尺寸不足，无法提取曲线")

    mask = _curve_mask(img, roi, mode)
    if mask.sum() < 20:
        raise SpectrumError("绘图区内未检测到曲线像素，请调整分析模式或手动框选")

    curves: List[Dict] = []
    sub = img[y0:y1, x0:x1]

    if max_curves <= 1:
        if mode == "color":
            cols = _dominant_colors(sub, mask, 1)
            color = [int(v) for v in cols[0]] if cols else [0, 0, 0]
            pts = _extract_by_color(img, roi, tuple(color)) if cols else []
        else:
            color = [0, 0, 0]
            pts = _extract_dark(img, roi)
        curves.append({"label": "曲线 1", "color": color, "points": pts})
        return curves, roi

    colors = _dominant_colors(sub, mask, max_curves)
    if not colors:
        raise SpectrumError("无法从图像中分离出多条曲线")

    for i, c in enumerate(colors):
        pts = _extract_by_color(img, roi, c)
        if len(pts) < 5:
            continue
        curves.append({
            "label": f"曲线 {i + 1}",
            "color": [int(c[0]), int(c[1]), int(c[2])],
            "points": pts,
        })

    if not curves:
        raise SpectrumError("颜色聚类未得到有效曲线，请尝试单曲线模式")
    return curves, roi


# --------------------------------------------------------------------------
# 定量处理
# --------------------------------------------------------------------------

def _process_points(points: Sequence[Sequence[float]], smooth_win: int,
                    baseline: bool, min_height: float, min_dist: int) -> Dict:
    """对提取出的曲线点做平滑、基线校正、寻峰与积分。"""
    if len(points) < 5:
        raise SpectrumError("曲线点过少，无法做定量处理")

    x = [float(p[0]) for p in points]
    y = [float(p[2]) for p in points]

    core = cbridge.get_core()

    win = smooth_win if smooth_win % 2 == 1 else smooth_win + 1
    if win >= 5 and len(y) > win:
        y_proc = core.savgol(y, win, 2)
    else:
        y_proc = list(y)

    # 内核的 baseline_linear 返回的就是扣基线后的信号
    if baseline:
        y_final = core.baseline_linear(y_proc, max(3, len(y_proc) // 20))
    else:
        y_final = list(y_proc)

    peaks = core.find_peaks(y_final, min_height, min_dist)
    area = core.trapz(x, y_final)
    stats = core.describe(y_final)

    return {
        "x": [round(v, 4) for v in x],
        "yRaw": [round(v, 6) for v in y],
        "y": [round(v, 6) for v in y_final],
        "peaks": peaks,
        "area": round(area, 6),
        "stats": {k: round(v, 6) for k, v in stats.items()},
    }


def analyze_image(src: str, roi: Optional[Sequence[int]] = None, max_curves: int = 1,
                  mode: str = "auto", smooth_win: int = 7, baseline: bool = True,
                  min_height: float = 0.08, min_dist: int = 8,
                  x_range: Optional[Sequence[float]] = None) -> Dict:
    """完整分析流程。

    Args:
        src: 图像路径或 base64。
        roi: 可选手动框选 [x0, y0, x1, y1]。
        max_curves: 期望提取的曲线条数。
        mode: dark / color / auto。
        smooth_win: Savitzky–Golay 窗口宽度。
        baseline: 是否做线性基线校正。
        min_height: 寻峰的相对高度阈值。
        min_dist: 寻峰的最小间隔（像素）。
        x_range: 可选横轴标定 [起始值, 结束值]，用于把像素列号换算为波长等物理量。
    """
    img = load_image(src)
    h, w = img.shape[:2]

    roi_t = None
    if roi is not None and len(roi) == 4:
        roi_t = (int(roi[0]), int(roi[1]), int(roi[2]), int(roi[3]))

    curves, used_roi = extract_curves(img, roi_t, max_curves, mode)
    x0, y0, x1, y1 = used_roi

    out_curves: List[Dict] = []
    for c in curves:
        pts = c["points"]
        if not pts:
            continue
        proc = _process_points(pts, smooth_win, baseline, min_height, min_dist)

        if x_range and len(x_range) == 2:
            a, b = float(x_range[0]), float(x_range[1])
            span = max(x1 - x0 - 1, 1)
            xs = [round(a + (b - a) * (p[0] / span), 6) for p in pts]
            proc["x"] = xs

        out_curves.append({
            "label": c["label"],
            "color": c["color"],
            "pointCount": len(pts),
            **proc,
        })

    if not out_curves:
        raise SpectrumError("未能得到有效曲线数据")

    preview = render_preview(img, used_roi, curves, out_curves)
    return {
        "imageWidth": w,
        "imageHeight": h,
        "plotArea": [x0, y0, x1, y1],
        "curves": out_curves,
        "preview": preview,
    }


def render_preview(img: np.ndarray, roi: Tuple[int, int, int, int],
                   raw_curves: Sequence[Dict], processed: Sequence[Dict]) -> str:
    """生成标注了绘图区、曲线与峰位的预览图（base64 PNG）。"""
    vis = img.copy()
    x0, y0, x1, y1 = roi
    cv2.rectangle(vis, (x0, y0), (x1, y1), (0, 200, 0), 1)

    palette = [(0, 0, 255), (255, 0, 0), (0, 160, 255), (200, 0, 200), (0, 200, 200)]

    for idx, (raw, proc) in enumerate(zip(raw_curves, processed)):
        color = tuple(int(v) for v in raw.get("color", (0, 0, 0)))
        if color == (0, 0, 0):
            color = palette[idx % len(palette)]
        height = max(y1 - y0 - 1, 1)
        for p in raw["points"]:
            px = int(x0 + p[0])
            py = int(y0 + p[1])
            if 0 <= px < vis.shape[1] and 0 <= py < vis.shape[0]:
                cv2.circle(vis, (px, py), 1, color, -1)
        for pk in proc.get("peaks", []):
            px = int(x0 + pk["index"])
            py = int(y0 + height * (1.0 - pk["height"]))
            cv2.circle(vis, (px, py), 4, (0, 0, 255), 1)
            cv2.putText(vis, f"{pk['index']}", (px + 4, py - 4),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.35, (0, 0, 255), 1, cv2.LINE_AA)

    ok, buf = cv2.imencode(".png", vis)
    if not ok:
        return ""
    return "data:image/png;base64," + base64.b64encode(buf.tobytes()).decode("ascii")


def compare_images(sources: Sequence[str], **kwargs) -> Dict:
    """批量分析多张谱图，便于做批次对比。"""
    results = []
    for s in sources:
        try:
            r = analyze_image(s, **kwargs)
            r["source"] = os.path.basename(s) if not s.startswith("data:") else "(内联图像)"
            r.pop("preview", None)
            results.append(r)
        except Exception as e:  # 单张失败不影响整批
            results.append({
                "source": os.path.basename(s) if not s.startswith("data:") else "(内联图像)",
                "error": str(e),
            })
    return {"count": len(results), "results": results}


if __name__ == "__main__":
    import sys

    # 生成一张合成谱图用于自检，避免依赖外部素材
    W, H = 640, 400
    canvas = np.full((H, W, 3), 255, dtype=np.uint8)
    cv2.rectangle(canvas, (60, 40), (600, 340), (0, 0, 0), 1)

    def gauss(x, mu, amp, sigma):
        return amp * np.exp(-((x - mu) ** 2) / (2 * sigma ** 2))

    xs = np.arange(60, 600)
    ys = 300 - (gauss(xs, 200, 150, 25) + gauss(xs, 400, 200, 35))
    for i in range(len(xs) - 1):
        cv2.line(canvas, (int(xs[i]), int(ys[i])), (int(xs[i + 1]), int(ys[i + 1])),
                 (0, 0, 0), 2)

    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_selftest_spectrum.png")
    imwrite_unicode(tmp, canvas)
    print("合成谱图:", tmp)

    res = analyze_image(tmp, max_curves=1, mode="dark", smooth_win=9,
                        baseline=True, min_height=0.15, min_dist=20)
    print("绘图区:", res["plotArea"])
    c = res["curves"][0]
    print("提取点数:", c["pointCount"])
    print("峰:", [(p["index"], round(p["height"], 3)) for p in c["peaks"]])
    print("面积:", c["area"])
    print("统计:", c["stats"])
    os.remove(tmp)
    print("光谱分析自检完成")