"""分析服务进程：把 Python 分析能力以「JSON 行」协议暴露给 Java 主应用。

Java 侧通过标准输入/输出与常驻的本进程通信，每行一个完整 JSON 报文：

    请求  {"id": 1, "method": "spectrum.analyze", "params": {...}}
    成功  {"id": 1, "ok": true,  "result": {...}}
    失败  {"id": 1, "ok": false, "error": "...", "trace": "..."}

之所以用「行协议 + 常驻进程」而不是 HTTP：桌面应用无需占用端口，也不会与
其他软件冲突；进程常驻可避免每次分析都重新加载 OpenCV/NumPy（约 1 秒）。

stdout 只允许出现协议报文，任何库在导入期打印的内容都会被重定向到 stderr。
"""

from __future__ import annotations

import json
import os
import sys
import traceback
from typing import Any, Callable, Dict

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

PROTOCOL_VERSION = 1


# --------------------------------------------------------------------------
# 导入分析模块（导入期的杂散输出一律丢到 stderr，避免污染协议）
# --------------------------------------------------------------------------

def _load_modules():
    """导入分析模块；导入期的杂散输出一律丢到 stderr，避免污染协议。

    ``spectrum`` 依赖 OpenCV/NumPy，缺失时只禁用光谱分析，不影响记录本归纳，
    因此单独容错导入，把失败原因记录在 ``_SPECTRUM_ERROR`` 里供上层提示。
    """
    real_stdout = sys.stdout
    sys.stdout = sys.stderr
    try:
        import cbridge
        import notebook
        try:
            import spectrum
            spectrum_error = None
        except Exception as exc:  # 缺少 cv2/numpy 等
            spectrum = None
            spectrum_error = f"{type(exc).__name__}: {exc}"
    finally:
        sys.stdout = real_stdout
    return cbridge, notebook, spectrum, spectrum_error


cbridge, notebook, spectrum, _SPECTRUM_ERROR = _load_modules()


def _require_spectrum():
    if spectrum is None:
        raise RuntimeError(
            "光谱分析不可用：" + (_SPECTRUM_ERROR or "未知原因")
            + "。请安装 opencv-python 与 numpy 后重启应用。")
    return spectrum


# --------------------------------------------------------------------------
# 方法实现
# --------------------------------------------------------------------------

def _m_ping(params: Dict[str, Any]) -> Dict[str, Any]:
    core = cbridge.try_get_core()
    return {
        "pong": True,
        "protocol": PROTOCOL_VERSION,
        "python": sys.version.split()[0],
        "coreAvailable": core is not None,
        "coreVersion": core.version if core else None,
        "corePath": core.path if core else None,
        "vision": spectrum is not None,
        "visionError": _SPECTRUM_ERROR,
    }


def _m_core_info(params: Dict[str, Any]) -> Dict[str, Any]:
    core = cbridge.try_get_core()
    return {
        "available": core is not None,
        "version": core.version if core else None,
        "path": core.path if core else None,
    }


def _m_spectrum_analyze(params: Dict[str, Any]) -> Dict[str, Any]:
    spec = _require_spectrum()
    src = params.get("src")
    if not src:
        raise ValueError("缺少参数 src（图像路径或 base64）")
    return spec.analyze_image(
        src,
        roi=params.get("roi"),
        max_curves=int(params.get("maxCurves", 1) or 1),
        mode=params.get("mode") or "auto",
        smooth_win=int(params.get("smoothWin", 7) or 7),
        baseline=bool(params.get("baseline", True)),
        min_height=float(params.get("minHeight", 0.08)),
        min_dist=int(params.get("minDist", 8) or 8),
        x_range=params.get("xRange"),
    )


def _m_spectrum_compare(params: Dict[str, Any]) -> Dict[str, Any]:
    spec = _require_spectrum()
    sources = params.get("sources") or []
    if not sources:
        raise ValueError("缺少参数 sources（图像列表）")
    kwargs = dict(
        max_curves=int(params.get("maxCurves", 1) or 1),
        mode=params.get("mode") or "auto",
        smooth_win=int(params.get("smoothWin", 7) or 7),
        baseline=bool(params.get("baseline", True)),
        min_height=float(params.get("minHeight", 0.08)),
        min_dist=int(params.get("minDist", 8) or 8),
    )
    return spec.compare_images(sources, **kwargs)


def _m_notebook_summarize(params: Dict[str, Any]) -> Dict[str, Any]:
    records = params.get("records") or []
    if not isinstance(records, list):
        raise ValueError("records 必须是数组")
    return notebook.summarize(records)


def _m_notebook_search(params: Dict[str, Any]) -> Dict[str, Any]:
    records = params.get("records") or []
    keyword = params.get("keyword") or ""
    return {"hits": notebook.search(records, keyword)}


def _m_extract_numbers(params: Dict[str, Any]) -> Dict[str, Any]:
    text = params.get("text") or ""
    min_abs = float(params.get("minAbs", 0.0))
    return {"numbers": notebook.extract_numbers(text, min_abs)}


_METHODS: Dict[str, Callable[[Dict[str, Any]], Any]] = {
    "ping": _m_ping,
    "core.info": _m_core_info,
    "spectrum.analyze": _m_spectrum_analyze,
    "spectrum.compare": _m_spectrum_compare,
    "notebook.summarize": _m_notebook_summarize,
    "notebook.search": _m_notebook_search,
    "notebook.extractNumbers": _m_extract_numbers,
}


# --------------------------------------------------------------------------
# 主循环
# --------------------------------------------------------------------------

def _write(payload: Dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def _handle(raw: str) -> None:
    req_id = None
    try:
        req = json.loads(raw)
        req_id = req.get("id")
        method = req.get("method") or ""
        params = req.get("params") or {}
        fn = _METHODS.get(method)
        if fn is None:
            _write({"id": req_id, "ok": False,
                    "error": f"未知方法：{method}",
                    "methods": sorted(_METHODS.keys())})
            return
        result = fn(params)
        _write({"id": req_id, "ok": True, "result": result})
    except Exception as e:  # 单条请求失败不影响进程继续服务
        _write({"id": req_id, "ok": False,
                "error": f"{type(e).__name__}: {e}",
                "trace": traceback.format_exc()[-2000:]})


def serve() -> int:
    try:
        sys.stdin.reconfigure(encoding="utf-8", errors="replace")
        sys.stdout.reconfigure(encoding="utf-8", errors="replace", newline="\n")
    except Exception:
        pass

    _write({"id": 0, "ok": True, "result": {
        "ready": True,
        "protocol": PROTOCOL_VERSION,
        "methods": sorted(_METHODS.keys()),
        "vision": spectrum is not None,
        "visionError": _SPECTRUM_ERROR,
    }})

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line == "__quit__":
            break
        _handle(line)
    return 0


def selftest() -> int:
    """不依赖 Java 的自检：直接跑一轮方法分发。"""
    print("协议版本:", PROTOCOL_VERSION)
    if spectrum is None:
        print("光谱分析: 不可用（" + (_SPECTRUM_ERROR or "未知原因") + "）")
    else:
        print("光谱分析: 可用")
    print("ping:", json.dumps(_m_ping({}), ensure_ascii=False))

    demo = [
        {"title": "盐酸标定 #1", "type": "titration", "date": "2026-03-01",
         "dataText": "标准液 0.1000 mol/L，滴定体积 22.50 mL", "resultValue": 0.0900},
        {"title": "盐酸标定 #2", "type": "titration", "date": "2026-03-08",
         "dataText": "标准液 0.1000 mol/L，滴定体积 22.48 mL", "resultValue": 0.0899},
        {"title": "盐酸标定 #3", "type": "titration", "date": "2026-03-15",
         "dataText": "标准液 0.1000 mol/L，滴定体积 23.90 mL", "resultValue": 0.0956},
    ]
    s = _m_notebook_summarize({"records": demo})
    print("归纳结论:")
    for t in s["insights"]:
        print("  *", t)
    print("数值抽取:", _m_extract_numbers({"text": "吸光度 0.450，波长 510 nm，日期 2026-03-01"})["numbers"])
    print("分析服务自检完成")
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(selftest())
    raise SystemExit(serve())