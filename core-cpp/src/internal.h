/*
 * internal.h —— 内核内部数值工具，不对外暴露。
 */
#ifndef CHEMLAB_INTERNAL_H
#define CHEMLAB_INTERNAL_H

#include <vector>
#include <algorithm>
#include <cmath>

namespace cl {

constexpr double kPi = 3.14159265358979323846;

/** 对数伽马函数（Lanczos 近似，误差 ~1e-15）。 */
double lgamma_fn(double x);

/** 正则化不完全贝塔函数 I_x(a,b)，用于 t / F 分布 CDF。 */
double betai(double a, double b, double x);

/** 学生 t 分布累积分布函数 P(T <= t)。 */
double t_cdf(double t, double df);

/** 学生 t 分布双侧 p 值。 */
double t_two_tail_p(double t, double df);

/** t 分布分位数（双侧 alpha 对应的临界值），二分法求根。 */
double t_quantile(double p, double df);

/** F 分布累积分布函数。 */
double f_cdf(double f, double df1, double df2);

/** 标准正态分布 CDF。 */
double norm_cdf(double z);

/** 样本标准差；ddof=0 总体，ddof=1 样本。 */
double stddev(const double* d, int n, int ddof);

/** 均值。 */
double mean(const double* d, int n);

/** 线性插值查表：给定升序 xs、对应 ys，求 xq 处取值。 */
double table_lookup(const double* xs, const double* ys, int n, double xq);

/**
 * 解 n 阶线性方程组 A·x = b（高斯消元 + 部分主元）。
 * A 以行优先一维数组给出，会被就地修改。
 * @return true 表示求解成功
 */
bool solve_linear(std::vector<double>& A, std::vector<double>& b, int n);

} // namespace cl

#endif /* CHEMLAB_INTERNAL_H */