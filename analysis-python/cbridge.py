"""ctypes 绑定：Python 分析服务调用 C++ 计算内核。

内核产物为 chemlab_core.dll，通过纯 C ABI 暴露。本模块只做类型转换与
错误码检查，把 C 风格「返回码 + 输出缓冲」的接口包装成 Python 的
「返回标量/列表，失败抛异常」风格。
"""

from __future__ import annotations

import ctypes
import os
import sys
from ctypes import c_double, c_int, c_char_p, POINTER
from typing import List, Optional, Sequence

_IS_WINDOWS = sys.platform.startswith("win")
_LIB_NAME = "chemlab_core.dll" if _IS_WINDOWS else "libchemlab_core.so"

# 状态码，与 core-cpp/include/chemlab_core.h 保持一致
CL_OK = 0
CL_ERR_NULL = -1
CL_ERR_SIZE = -2
CL_ERR_RANGE = -3
CL_ERR_DEGEN = -4
CL_ERR_CONVERGE = -5
CL_ERR_CAPACITY = -6

_ERROR_TEXT = {
    CL_ERR_NULL: "传入空指针",
    CL_ERR_SIZE: "样本量不足或长度非法",
    CL_ERR_RANGE: "参数超出定义域",
    CL_ERR_DEGEN: "退化输入（全等值或零方差）",
    CL_ERR_CONVERGE: "数值迭代未收敛",
    CL_ERR_CAPACITY: "输出缓冲不足",
}


class CoreError(RuntimeError):
    """内核返回非零状态码。"""

    def __init__(self, code: int, func: str):
        self.code = code
        self.func = func
        super().__init__(f"{func} 失败：{_ERROR_TEXT.get(code, f'未知错误码 {code}')}")


def _candidate_dirs() -> List[str]:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    return [
        os.path.join(root, "dist", "native", "Release"),
        os.path.join(root, "dist", "native"),
        os.path.join(root, "dist", "native", "Debug"),
        here,
    ]


def _find_library() -> Optional[str]:
    env = os.environ.get("CHEMLAB_CORE_DLL")
    if env and os.path.isfile(env):
        return env
    for d in _candidate_dirs():
        p = os.path.join(d, _LIB_NAME)
        if os.path.isfile(p):
            return p
    return None


class _CoreLib:
    """懒加载的内核句柄，绑定全部函数签名。"""

    _instance: Optional["_CoreLib"] = None

    def __init__(self) -> None:
        path = _find_library()
        if not path:
            raise FileNotFoundError(
                f"未找到 {_LIB_NAME}，请先运行 build-all.ps1 构建 C++ 内核，"
                f"或设置环境变量 CHEMLAB_CORE_DLL 指向内核文件。"
            )
        self.path = path
        self.lib = ctypes.CDLL(path)
        self._bind()

    @classmethod
    def get(cls) -> "_CoreLib":
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    # -- 签名绑定 ------------------------------------------------------

    def _bind(self) -> None:
        L = self.lib
        dp = POINTER(c_double)
        ip = POINTER(c_int)

        L.cl_version.restype = c_char_p
        L.cl_version.argtypes = []

        def scalar(name: str, argtypes):
            f = getattr(L, name)
            f.restype = c_int
            f.argtypes = argtypes
            return f

        # 描述统计
        self._describe = scalar("cl_describe", [dp, c_int, dp])
        self._mean = scalar("cl_mean", [dp, c_int, dp])
        self._stddev = scalar("cl_stddev", [dp, c_int, c_int, dp])
        self._rsd = scalar("cl_rsd", [dp, c_int, dp])
        self._rel_error = scalar("cl_rel_error", [c_double, c_double, dp])

        # 异常值与检验
        self._grubbs = scalar("cl_grubbs_max", [dp, c_int, c_double, dp])
        self._dixon = scalar("cl_dixon_q", [dp, c_int, c_double, dp])
        self._t1 = scalar("cl_t_test_one", [dp, c_int, c_double, dp])
        self._t2 = scalar("cl_t_test_two", [dp, c_int, dp, c_int, c_int, dp])
        self._ftest = scalar("cl_f_test", [dp, c_int, dp, c_int, dp])
        self._ci = scalar("cl_ci_mean", [dp, c_int, c_double, dp])

        # 回归
        self._linreg = scalar("cl_linreg", [dp, dp, c_int, dp])
        self._linreg_w = scalar("cl_linreg_weighted", [dp, dp, dp, c_int, dp])
        self._lodloq = scalar("cl_lod_loq", [c_double, c_double, c_double, c_double, dp])
        self._residuals = scalar("cl_linreg_residuals", [dp, dp, c_int, c_double, c_double, dp])
        self._calib_inv = scalar("cl_calib_inverse", [c_double, c_double, c_double, dp])

        # 化学
        self._molarity = scalar("cl_molarity", [c_double, c_double, c_double, dp])
        self._dilution = scalar("cl_dilution", [c_double, c_double, c_double, dp])
        self._mass_conc = scalar("cl_mass_conc", [c_double, c_double, dp])
        self._ph_sa = scalar("cl_ph_strong_acid", [c_double, dp])
        self._ph_sb = scalar("cl_ph_strong_base", [c_double, dp])
        self._ph_wa = scalar("cl_ph_weak_acid", [c_double, c_double, dp])
        self._ph_wb = scalar("cl_ph_weak_base", [c_double, c_double, dp])
        self._ph_buf = scalar("cl_ph_buffer", [c_double, c_double, c_double, dp])
        self._titration = scalar("cl_titration", [c_double, c_double, c_double, c_double, dp])
        self._bl_abs = scalar("cl_beer_lambert_abs", [c_double, c_double, c_double, dp])
        self._bl_conc = scalar("cl_beer_lambert_conc", [c_double, c_double, c_double, dp])
        self._bl_eps = scalar("cl_beer_lambert_epsilon", [c_double, c_double, c_double, dp])

        # 误差传递
        self._unc_add = scalar("cl_unc_add", [dp, dp, c_int, dp])
        self._unc_mul = scalar("cl_unc_mul", [dp, dp, c_int, dp])
        self._unc_pow = scalar("cl_unc_power", [c_double, c_double, c_double, dp])

        # 信号
        self._movavg = scalar("cl_moving_average", [dp, c_int, c_int, dp])
        self._savgol = scalar("cl_savgol", [dp, c_int, c_int, c_int, dp])
        self._base_lin = scalar("cl_baseline_linear", [dp, c_int, c_int, dp])
        self._base_poly = scalar("cl_baseline_poly", [dp, c_int, c_int, c_int, dp])
        self._trapz = scalar("cl_trapz", [dp, dp, c_int, dp])
        self._normalize = scalar("cl_normalize", [dp, c_int, dp])

        L.cl_find_peaks.restype = c_int
        L.cl_find_peaks.argtypes = [dp, c_int, c_double, c_int, ip, dp, c_int, ip]
        self._find_peaks = L.cl_find_peaks

    # -- 工具 ----------------------------------------------------------

    @staticmethod
    def _arr(values: Sequence[float]):
        n = len(values)
        buf = (c_double * n)(*[float(v) for v in values])
        return buf, n

    def _call(self, fn, args, n_out: int, name: str) -> List[float]:
        out = (c_double * n_out)()
        rc = fn(*args, out)
        if rc != CL_OK:
            raise CoreError(rc, name)
        return [float(x) for x in out]

    # -- 对外 API ------------------------------------------------------

    @property
    def version(self) -> str:
        return self.lib.cl_version().decode("utf-8")

    def describe(self, data: Sequence[float]) -> dict:
        buf, n = self._arr(data)
        r = self._call(self._describe, [buf, n], 8, "cl_describe")
        keys = ["mean", "median", "stddev", "rsd", "variance", "sem", "min", "max"]
        return dict(zip(keys, r))

    def grubbs(self, data: Sequence[float], alpha: float = 0.05) -> dict:
        buf, n = self._arr(data)
        r = self._call(self._grubbs, [buf, n, alpha], 4, "cl_grubbs_max")
        return {"g": r[0], "gCrit": r[1], "value": r[2], "outlierIndex": int(r[3])}

    def dixon(self, data: Sequence[float], alpha: float = 0.05) -> dict:
        buf, n = self._arr(data)
        r = self._call(self._dixon, [buf, n, alpha], 3, "cl_dixon_q")
        return {"q": r[0], "qCrit": r[1], "outlierIndex": int(r[2])}

    def t_test_one(self, data: Sequence[float], mu0: float) -> dict:
        buf, n = self._arr(data)
        r = self._call(self._t1, [buf, n, mu0], 4, "cl_t_test_one")
        return {"t": r[0], "p": r[1], "df": r[2], "sem": r[3]}

    def t_test_two(self, a: Sequence[float], b: Sequence[float], pooled: bool = True) -> dict:
        ba, na = self._arr(a)
        bb, nb = self._arr(b)
        r = self._call(self._t2, [ba, na, bb, nb, 1 if pooled else 0], 4, "cl_t_test_two")
        return {"t": r[0], "p": r[1], "df": r[2], "meanDiff": r[3]}

    def f_test(self, a: Sequence[float], b: Sequence[float]) -> dict:
        ba, na = self._arr(a)
        bb, nb = self._arr(b)
        r = self._call(self._ftest, [ba, na, bb, nb], 3, "cl_f_test")
        return {"f": r[0], "p": r[1], "df": r[2]}

    def confidence_interval(self, data: Sequence[float], conf: float = 0.95) -> dict:
        buf, n = self._arr(data)
        r = self._call(self._ci, [buf, n, conf], 4, "cl_ci_mean")
        return {"lower": r[0], "upper": r[1], "halfWidth": r[2], "tCrit": r[3]}

    def linear_regression(self, x: Sequence[float], y: Sequence[float]) -> dict:
        bx, n = self._arr(x)
        by, m = self._arr(y)
        if n != m:
            raise CoreError(CL_ERR_SIZE, "cl_linreg")
        r = self._call(self._linreg, [bx, by, n], 8, "cl_linreg")
        keys = ["slope", "intercept", "r2", "r", "syx", "sb", "sa", "n"]
        return dict(zip(keys, r))

    def lod_loq(self, slope: float, syx: float, k_lod: float = 3.0, k_loq: float = 10.0) -> dict:
        r = self._call(self._lodloq, [slope, syx, k_lod, k_loq], 2, "cl_lod_loq")
        return {"lod": r[0], "loq": r[1]}

    def residuals(self, x: Sequence[float], y: Sequence[float],
                  slope: float, intercept: float) -> List[float]:
        bx, n = self._arr(x)
        by, m = self._arr(y)
        if n != m:
            raise CoreError(CL_ERR_SIZE, "cl_linreg_residuals")
        out = (c_double * n)()
        rc = self._residuals(bx, by, n, slope, intercept, out)
        if rc != CL_OK:
            raise CoreError(rc, "cl_linreg_residuals")
        return [float(v) for v in out]

    def calib_inverse(self, y: float, slope: float, intercept: float) -> float:
        out = (c_double * 1)()
        rc = self._calib_inv(y, slope, intercept, out)
        if rc != CL_OK:
            raise CoreError(rc, "cl_calib_inverse")
        return float(out[0])

    def molarity(self, mass_g: float, molar_mass: float, volume_l: float) -> float:
        return self._scalar(self._molarity, [mass_g, molar_mass, volume_l], "cl_molarity")

    def dilution(self, c1: float, v1: float, v2: float) -> float:
        return self._scalar(self._dilution, [c1, v1, v2], "cl_dilution")

    def mass_conc(self, molarity: float, molar_mass: float) -> float:
        return self._scalar(self._mass_conc, [molarity, molar_mass], "cl_mass_conc")

    def ph_strong_acid(self, c: float) -> float:
        return self._scalar(self._ph_sa, [c], "cl_ph_strong_acid")

    def ph_strong_base(self, c: float) -> float:
        return self._scalar(self._ph_sb, [c], "cl_ph_strong_base")

    def ph_weak_acid(self, c: float, ka: float) -> dict:
        r = self._call(self._ph_wa, [c, ka], 3, "cl_ph_weak_acid")
        return {"pH": r[0], "alpha": r[1], "hConc": r[2]}

    def ph_weak_base(self, c: float, kb: float) -> dict:
        r = self._call(self._ph_wb, [c, kb], 3, "cl_ph_weak_base")
        return {"pH": r[0], "alpha": r[1], "ohConc": r[2]}

    def ph_buffer(self, ka: float, ca: float, cb: float) -> dict:
        r = self._call(self._ph_buf, [ka, ca, cb], 2, "cl_ph_buffer")
        return {"pH": r[0], "ratio": r[1]}

    def titration(self, c_std: float, v_std: float, v_sample: float, ratio: float = 1.0) -> float:
        return self._scalar(self._titration, [c_std, v_std, v_sample, ratio], "cl_titration")

    def beer_lambert_abs(self, eps: float, path: float, conc: float) -> float:
        return self._scalar(self._bl_abs, [eps, path, conc], "cl_beer_lambert_abs")

    def beer_lambert_conc(self, abs_: float, eps: float, path: float) -> float:
        return self._scalar(self._bl_conc, [abs_, eps, path], "cl_beer_lambert_conc")

    def beer_lambert_epsilon(self, abs_: float, path: float, conc: float) -> float:
        return self._scalar(self._bl_eps, [abs_, path, conc], "cl_beer_lambert_epsilon")

    def unc_add(self, vals: Sequence[float], uncs: Sequence[float]) -> dict:
        bv, n = self._arr(vals)
        bu, m = self._arr(uncs)
        if n != m:
            raise CoreError(CL_ERR_SIZE, "cl_unc_add")
        r = self._call(self._unc_add, [bv, bu, n], 2, "cl_unc_add")
        return {"value": r[0], "uncertainty": r[1]}

    def unc_mul(self, vals: Sequence[float], uncs: Sequence[float]) -> dict:
        bv, n = self._arr(vals)
        bu, m = self._arr(uncs)
        if n != m:
            raise CoreError(CL_ERR_SIZE, "cl_unc_mul")
        r = self._call(self._unc_mul, [bv, bu, n], 3, "cl_unc_mul")
        return {"value": r[0], "uncertainty": r[1], "relative": r[2]}

    def unc_power(self, x: float, ux: float, a: float) -> dict:
        r = self._call(self._unc_pow, [x, ux, a], 2, "cl_unc_power")
        return {"value": r[0], "uncertainty": r[1]}

    def moving_average(self, y: Sequence[float], win: int) -> List[float]:
        return self._array_op(self._movavg, y, [win], "cl_moving_average")

    def savgol(self, y: Sequence[float], win: int, order: int = 2) -> List[float]:
        return self._array_op(self._savgol, y, [win, order], "cl_savgol")

    def baseline_linear(self, y: Sequence[float], win: int) -> List[float]:
        return self._array_op(self._base_lin, y, [win], "cl_baseline_linear")

    def baseline_poly(self, y: Sequence[float], order: int = 3, iters: int = 5) -> List[float]:
        return self._array_op(self._base_poly, y, [order, iters], "cl_baseline_poly")

    def find_peaks(self, y: Sequence[float], min_height: float = 0.1,
                   min_dist: int = 5, max_peaks: int = 256) -> List[dict]:
        by, n = self._arr(y)
        idx = (c_int * max_peaks)()
        hts = (c_double * max_peaks)()
        found = c_int(0)
        rc = self._find_peaks(by, n, float(min_height), int(min_dist),
                              idx, hts, max_peaks, ctypes.byref(found))
        if rc != CL_OK:
            raise CoreError(rc, "cl_find_peaks")
        return [{"index": int(idx[i]), "height": float(hts[i])}
                for i in range(found.value)]

    def trapz(self, x: Optional[Sequence[float]], y: Sequence[float]) -> float:
        by, n = self._arr(y)
        if x is not None:
            bx, m = self._arr(x)
            if m != n:
                raise CoreError(CL_ERR_SIZE, "cl_trapz")
            px = bx
        else:
            px = None
        out = (c_double * 1)()
        rc = self._trapz(px, by, n, out)
        if rc != CL_OK:
            raise CoreError(rc, "cl_trapz")
        return float(out[0])

    def normalize(self, y: Sequence[float]) -> List[float]:
        return self._array_op(self._normalize, y, [], "cl_normalize")

    # -- 内部辅助 ------------------------------------------------------

    def _scalar(self, fn, args, name: str) -> float:
        out = (c_double * 1)()
        rc = fn(*args, out)
        if rc != CL_OK:
            raise CoreError(rc, name)
        return float(out[0])

    def _array_op(self, fn, y: Sequence[float], extra, name: str) -> List[float]:
        by, n = self._arr(y)
        out = (c_double * n)()
        rc = fn(by, n, *extra, out)
        if rc != CL_OK:
            raise CoreError(rc, name)
        return [float(v) for v in out]


def get_core() -> _CoreLib:
    """获取内核句柄；未构建时抛 FileNotFoundError。"""
    return _CoreLib.get()


def try_get_core() -> Optional[_CoreLib]:
    """获取内核句柄；不可用时返回 None，供服务降级使用。"""
    try:
        return _CoreLib.get()
    except Exception:
        return None


if __name__ == "__main__":
    core = get_core()
    print("内核路径:", core.path)
    print("内核版本:", core.version)
    print("describe:", core.describe([1, 2, 3, 4, 5]))
    print("linreg:", core.linear_regression([1, 2, 3, 4, 5], [3, 5, 7, 9, 11]))
    print("pH 弱酸:", core.ph_weak_acid(0.1, 1.8e-5))
    print("寻峰:", core.find_peaks([0, 1, 5, 1, 0, 2, 8, 2, 0], 0.1, 2))