/*
 * chemlab_jni.cpp —— Java ↔ C++ 内核桥接层（JNI）。
 *
 * 设计要点：
 *   - 与 Java 的接口刻意保持「窄而扁平」：标量返回 double，结构化结果返回 double[]，
 *     失败时返回 NaN 或 null，Java 侧统一做校验，避免在 JNI 里抛异常带来的复杂度。
 *   - 所有数组长度校验在进入内核前完成，内核只负责数值计算。
 *   - 对应 Java 类：com.chemlab.core.NativeCore
 */
#include <jni.h>
#include "chemlab_core.h"
#include <vector>
#include <cmath>
#include <string>

namespace {

const double kNaN = std::nan("");

/** 把 jdoubleArray 复制到 vector；数组为空或长度为 0 时返回空 vector。 */
std::vector<double> to_vec(JNIEnv* env, jdoubleArray arr) {
    if (!arr) return {};
    jsize n = env->GetArrayLength(arr);
    if (n <= 0) return {};
    std::vector<double> v((size_t)n);
    env->GetDoubleArrayRegion(arr, 0, n, v.data());
    return v;
}

/** 由 C 数组构造 jdoubleArray；n<=0 时返回 null。 */
jdoubleArray make_arr(JNIEnv* env, const double* data, int n) {
    if (n <= 0) return nullptr;
    jdoubleArray out = env->NewDoubleArray(n);
    if (!out) return nullptr;
    env->SetDoubleArrayRegion(out, 0, n, data);
    return out;
}

/** 调用内核函数并写出 nOut 个结果；失败时填充 NaN，保证 Java 侧长度稳定。 */
template <typename Fn>
jdoubleArray call_arr(JNIEnv* env, int nOut, Fn fn) {
    std::vector<double> out((size_t)nOut, kNaN);
    int rc = fn(out.data());
    if (rc != CL_OK) return nullptr;
    return make_arr(env, out.data(), nOut);
}

} // namespace

extern "C" {

/* ===================== 版本 ===================== */

JNIEXPORT jstring JNICALL
Java_com_chemlab_core_NativeCore_version(JNIEnv* env, jclass) {
    return env->NewStringUTF(cl_version());
}

/* ===================== 描述统计 ===================== */

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_describe(JNIEnv* env, jclass, jdoubleArray data) {
    std::vector<double> d = to_vec(env, data);
    if (d.size() < 1) return nullptr;
    return call_arr(env, 8, [&](double* o) {
        return cl_describe(d.data(), (int)d.size(), o);
    });
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_mean(JNIEnv* env, jclass, jdoubleArray data) {
    std::vector<double> d = to_vec(env, data);
    double o = kNaN;
    if (cl_mean(d.data(), (int)d.size(), &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_stddev(JNIEnv* env, jclass, jdoubleArray data, jint ddof) {
    std::vector<double> d = to_vec(env, data);
    double o = kNaN;
    if (cl_stddev(d.data(), (int)d.size(), ddof, &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_rsd(JNIEnv* env, jclass, jdoubleArray data) {
    std::vector<double> d = to_vec(env, data);
    double o = kNaN;
    if (cl_rsd(d.data(), (int)d.size(), &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_relativeError(JNIEnv*, jclass, jdouble measured, jdouble trueValue) {
    double o = kNaN;
    if (cl_rel_error(measured, trueValue, &o) != CL_OK) return kNaN;
    return o;
}

/* ===================== 异常值检验 ===================== */

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_grubbs(JNIEnv* env, jclass, jdoubleArray data, jdouble alpha) {
    std::vector<double> d = to_vec(env, data);
    if (d.empty()) return nullptr;
    return call_arr(env, 4, [&](double* o) {
        return cl_grubbs_max(d.data(), (int)d.size(), alpha, o);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_dixonQ(JNIEnv* env, jclass, jdoubleArray data, jdouble alpha) {
    std::vector<double> d = to_vec(env, data);
    if (d.empty()) return nullptr;
    return call_arr(env, 3, [&](double* o) {
        return cl_dixon_q(d.data(), (int)d.size(), alpha, o);
    });
}

/* ===================== 显著性检验 ===================== */

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_tTestOne(JNIEnv* env, jclass, jdoubleArray data, jdouble mu0) {
    std::vector<double> d = to_vec(env, data);
    if (d.empty()) return nullptr;
    return call_arr(env, 4, [&](double* o) {
        return cl_t_test_one(d.data(), (int)d.size(), mu0, o);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_tTestTwo(JNIEnv* env, jclass, jdoubleArray a,
                                          jdoubleArray b, jboolean pooled) {
    std::vector<double> va = to_vec(env, a), vb = to_vec(env, b);
    if (va.empty() || vb.empty()) return nullptr;
    return call_arr(env, 4, [&](double* o) {
        return cl_t_test_two(va.data(), (int)va.size(), vb.data(), (int)vb.size(),
                             pooled ? 1 : 0, o);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_fTest(JNIEnv* env, jclass, jdoubleArray a, jdoubleArray b) {
    std::vector<double> va = to_vec(env, a), vb = to_vec(env, b);
    if (va.empty() || vb.empty()) return nullptr;
    return call_arr(env, 3, [&](double* o) {
        return cl_f_test(va.data(), (int)va.size(), vb.data(), (int)vb.size(), o);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_confidenceInterval(JNIEnv* env, jclass, jdoubleArray data, jdouble conf) {
    std::vector<double> d = to_vec(env, data);
    if (d.empty()) return nullptr;
    return call_arr(env, 4, [&](double* o) {
        return cl_ci_mean(d.data(), (int)d.size(), conf, o);
    });
}

/* ===================== 回归与标定 ===================== */

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_linearRegression(JNIEnv* env, jclass, jdoubleArray x, jdoubleArray y) {
    std::vector<double> vx = to_vec(env, x), vy = to_vec(env, y);
    if (vx.empty() || vx.size() != vy.size()) return nullptr;
    return call_arr(env, 8, [&](double* o) {
        return cl_linreg(vx.data(), vy.data(), (int)vx.size(), o);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_weightedRegression(JNIEnv* env, jclass, jdoubleArray x,
                                                    jdoubleArray y, jdoubleArray sd) {
    std::vector<double> vx = to_vec(env, x), vy = to_vec(env, y), vs = to_vec(env, sd);
    if (vx.empty() || vx.size() != vy.size() || vx.size() != vs.size()) return nullptr;
    return call_arr(env, 8, [&](double* o) {
        return cl_linreg_weighted(vx.data(), vy.data(), vs.data(), (int)vx.size(), o);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_lodLoq(JNIEnv* env, jclass, jdouble slope, jdouble syx,
                                        jdouble kLod, jdouble kLoq) {
    return call_arr(env, 2, [&](double* o) {
        return cl_lod_loq(slope, syx, kLod, kLoq, o);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_residuals(JNIEnv* env, jclass, jdoubleArray x, jdoubleArray y,
                                           jdouble slope, jdouble intercept) {
    std::vector<double> vx = to_vec(env, x), vy = to_vec(env, y);
    if (vx.empty() || vx.size() != vy.size()) return nullptr;
    std::vector<double> out(vx.size(), kNaN);
    if (cl_linreg_residuals(vx.data(), vy.data(), (int)vx.size(), slope, intercept, out.data()) != CL_OK)
        return nullptr;
    return make_arr(env, out.data(), (int)out.size());
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_calibInverse(JNIEnv*, jclass, jdouble y, jdouble slope, jdouble intercept) {
    double o = kNaN;
    if (cl_calib_inverse(y, slope, intercept, &o) != CL_OK) return kNaN;
    return o;
}

/* ===================== 化学计算 ===================== */

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_molarity(JNIEnv*, jclass, jdouble m, jdouble mm, jdouble v) {
    double o = kNaN;
    if (cl_molarity(m, mm, v, &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_dilution(JNIEnv*, jclass, jdouble c1, jdouble v1, jdouble v2) {
    double o = kNaN;
    if (cl_dilution(c1, v1, v2, &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_massConc(JNIEnv*, jclass, jdouble c, jdouble mm) {
    double o = kNaN;
    if (cl_mass_conc(c, mm, &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_stdPrepMass(JNIEnv* env, jclass, jdouble c, jdouble volL,
                                             jdouble mm, jdouble purity, jdouble factor) {
    return call_arr(env, 4, [&](double* o) { return cl_std_prep_mass(c, volL, mm, purity, factor, o); });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_stdPrepConc(JNIEnv* env, jclass, jdouble massG, jdouble mm,
                                             jdouble purity, jdouble volL) {
    return call_arr(env, 4, [&](double* o) { return cl_std_prep_conc(massG, mm, purity, volL, o); });
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_pHStrongAcid(JNIEnv*, jclass, jdouble c) {
    double o = kNaN;
    if (cl_ph_strong_acid(c, &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_pHStrongBase(JNIEnv*, jclass, jdouble c) {
    double o = kNaN;
    if (cl_ph_strong_base(c, &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_pHWeakAcid(JNIEnv* env, jclass, jdouble c, jdouble ka) {
    return call_arr(env, 3, [&](double* o) { return cl_ph_weak_acid(c, ka, o); });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_pHWeakBase(JNIEnv* env, jclass, jdouble c, jdouble kb) {
    return call_arr(env, 3, [&](double* o) { return cl_ph_weak_base(c, kb, o); });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_pHBuffer(JNIEnv* env, jclass, jdouble ka, jdouble ca, jdouble cb) {
    return call_arr(env, 2, [&](double* o) { return cl_ph_buffer(ka, ca, cb, o); });
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_titration(JNIEnv*, jclass, jdouble cs, jdouble vs,
                                           jdouble vx, jdouble ratio) {
    double o = kNaN;
    if (cl_titration(cs, vs, vx, ratio, &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_titrationCurve(JNIEnv* env, jclass,
                                                jint type, jdouble cAnal,
                                                jdouble vAnalMl, jdouble cTitr,
                                                jdouble kAnal, jdoubleArray vols) {
    std::vector<double> v = to_vec(env, vols);
    if (v.empty()) return nullptr;
    std::vector<double> ph(v.size(), kNaN);
    int rc = cl_titration_curve(type, cAnal, vAnalMl, cTitr, kAnal,
                                (int)v.size(), v.data(), ph.data());
    if (rc != CL_OK) return nullptr;
    return make_arr(env, ph.data(), (int)ph.size());
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_beerLambertAbs(JNIEnv*, jclass, jdouble e, jdouble b, jdouble c) {
    double o = kNaN;
    if (cl_beer_lambert_abs(e, b, c, &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_beerLambertConc(JNIEnv*, jclass, jdouble a, jdouble e, jdouble b) {
    double o = kNaN;
    if (cl_beer_lambert_conc(a, e, b, &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_beerLambertEpsilon(JNIEnv*, jclass, jdouble a, jdouble b, jdouble c) {
    double o = kNaN;
    if (cl_beer_lambert_epsilon(a, b, c, &o) != CL_OK) return kNaN;
    return o;
}

/* ===================== 误差传递 ===================== */

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_uncAdd(JNIEnv* env, jclass, jdoubleArray vals, jdoubleArray uncs) {
    std::vector<double> v = to_vec(env, vals), u = to_vec(env, uncs);
    if (v.empty() || v.size() != u.size()) return nullptr;
    return call_arr(env, 2, [&](double* o) {
        return cl_unc_add(v.data(), u.data(), (int)v.size(), o);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_uncMul(JNIEnv* env, jclass, jdoubleArray vals, jdoubleArray uncs) {
    std::vector<double> v = to_vec(env, vals), u = to_vec(env, uncs);
    if (v.empty() || v.size() != u.size()) return nullptr;
    return call_arr(env, 3, [&](double* o) {
        return cl_unc_mul(v.data(), u.data(), (int)v.size(), o);
    });
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_uncPower(JNIEnv* env, jclass, jdouble x, jdouble ux, jdouble a) {
    return call_arr(env, 2, [&](double* o) { return cl_unc_power(x, ux, a, o); });
}

/* ===================== 信号处理 ===================== */

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_movingAverage(JNIEnv* env, jclass, jdoubleArray y, jint win) {
    std::vector<double> d = to_vec(env, y);
    if (d.empty()) return nullptr;
    std::vector<double> out(d.size(), kNaN);
    if (cl_moving_average(d.data(), (int)d.size(), win, out.data()) != CL_OK) return nullptr;
    return make_arr(env, out.data(), (int)out.size());
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_savgol(JNIEnv* env, jclass, jdoubleArray y, jint win, jint order) {
    std::vector<double> d = to_vec(env, y);
    if (d.empty()) return nullptr;
    std::vector<double> out(d.size(), kNaN);
    if (cl_savgol(d.data(), (int)d.size(), win, order, out.data()) != CL_OK) return nullptr;
    return make_arr(env, out.data(), (int)out.size());
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_baselineLinear(JNIEnv* env, jclass, jdoubleArray y, jint win) {
    std::vector<double> d = to_vec(env, y);
    if (d.empty()) return nullptr;
    std::vector<double> out(d.size(), kNaN);
    if (cl_baseline_linear(d.data(), (int)d.size(), win, out.data()) != CL_OK) return nullptr;
    return make_arr(env, out.data(), (int)out.size());
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_baselinePoly(JNIEnv* env, jclass, jdoubleArray y,
                                              jint order, jint iters) {
    std::vector<double> d = to_vec(env, y);
    if (d.empty()) return nullptr;
    std::vector<double> out(d.size(), kNaN);
    if (cl_baseline_poly(d.data(), (int)d.size(), order, iters, out.data()) != CL_OK) return nullptr;
    return make_arr(env, out.data(), (int)out.size());
}

/*
 * 寻峰结果编码为 [峰数, idx0, h0, idx1, h1, ...]，避免 JNI 返回多个数组。
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_findPeaks(JNIEnv* env, jclass, jdoubleArray y,
                                           jdouble minHeight, jint minDist) {
    std::vector<double> d = to_vec(env, y);
    if (d.empty()) return nullptr;

    const int kMaxPeaks = 512;
    std::vector<int> idx(kMaxPeaks, 0);
    std::vector<double> h(kMaxPeaks, 0.0);
    int nFound = 0;
    if (cl_find_peaks(d.data(), (int)d.size(), minHeight, minDist,
                      idx.data(), h.data(), kMaxPeaks, &nFound) != CL_OK)
        return nullptr;

    std::vector<double> out;
    out.reserve((size_t)nFound * 2 + 1);
    out.push_back((double)nFound);
    for (int i = 0; i < nFound; ++i) {
        out.push_back((double)idx[i]);
        out.push_back(h[i]);
    }
    return make_arr(env, out.data(), (int)out.size());
}

JNIEXPORT jdouble JNICALL
Java_com_chemlab_core_NativeCore_trapz(JNIEnv* env, jclass, jdoubleArray x, jdoubleArray y) {
    std::vector<double> vx = to_vec(env, x), vy = to_vec(env, y);
    if (vy.empty()) return kNaN;
    const double* px = (vx.size() == vy.size()) ? vx.data() : nullptr;
    double o = kNaN;
    if (cl_trapz(px, vy.data(), (int)vy.size(), &o) != CL_OK) return kNaN;
    return o;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_normalize(JNIEnv* env, jclass, jdoubleArray y) {
    std::vector<double> d = to_vec(env, y);
    if (d.empty()) return nullptr;
    std::vector<double> out(d.size(), kNaN);
    if (cl_normalize(d.data(), (int)d.size(), out.data()) != CL_OK) return nullptr;
    return make_arr(env, out.data(), (int)out.size());
}

JNIEXPORT jdoubleArray JNICALL
Java_com_chemlab_core_NativeCore_chromPeaks(JNIEnv* env, jclass,
                                            jdoubleArray x, jdoubleArray y,
                                            jdouble minHeight, jint minDist,
                                            jint smoothWin) {
    std::vector<double> vx = to_vec(env, x), vy = to_vec(env, y);
    if (vy.empty() || vx.size() != vy.size()) return nullptr;
    std::vector<double> out(CL_CHROM_OUT_CAP, kNaN);
    int rc = cl_chrom_peaks(vx.data(), vy.data(), (int)vy.size(),
                            minHeight, minDist, smoothWin,
                            out.data(), (int)out.size());
    if (rc != CL_OK) return nullptr;
    int nPk = (int)out[0];
    return make_arr(env, out.data(), 1 + nPk * 8);
}

} // extern "C"