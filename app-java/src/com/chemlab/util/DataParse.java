package com.chemlab.util;

import java.util.ArrayList;
import java.util.List;

/** 界面文本输入到数值数组的解析工具。 */
public final class DataParse {

    private DataParse() {
    }

    /** 解析一组数值：支持空格、逗号、分号、换行、制表符分隔。 */
    public static double[] numbers(String text) {
        if (text == null) {
            return new double[0];
        }
        List<Double> out = new ArrayList<>();
        for (String tok : text.split("[\\s,;，、]+")) {
            if (tok.isEmpty()) {
                continue;
            }
            try {
                out.add(Double.parseDouble(tok));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无法解析为数值：" + tok);
            }
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /**
     * 解析成对数据（标定曲线用）。
     *
     * <p>每行一对，列间用空格/逗号/制表符分隔；也接受「一行内交替出现」的写法。
     * 返回 {x[], y[]}。
     */
    public static double[][] pairs(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new double[][]{new double[0], new double[0]};
        }
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();

        String[] lines = text.split("\\r?\\n");
        boolean multiLine = false;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] cols = line.trim().split("[\\s,;，、\\t]+");
            if (cols.length >= 2) {
                multiLine = true;
                break;
            }
        }

        if (multiLine) {
            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] cols = line.trim().split("[\\s,;，、\\t]+");
                if (cols.length < 2) {
                    throw new IllegalArgumentException("每行需要两列（x y）：" + line.trim());
                }
                try {
                    xs.add(Double.parseDouble(cols[0]));
                    ys.add(Double.parseDouble(cols[1]));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("无法解析该行：" + line.trim());
                }
            }
        } else {
            // 单行交替写法：x1 y1 x2 y2 ...
            double[] all = numbers(text);
            if (all.length % 2 != 0) {
                throw new IllegalArgumentException("交替输入时数值个数必须为偶数（x y 成对）");
            }
            for (int i = 0; i < all.length; i += 2) {
                xs.add(all[i]);
                ys.add(all[i + 1]);
            }
        }

        double[] ax = new double[xs.size()];
        double[] ay = new double[ys.size()];
        for (int i = 0; i < ax.length; i++) {
            ax[i] = xs.get(i);
            ay[i] = ys.get(i);
        }
        return new double[][]{ax, ay};
    }

    public static List<Double> toList(double[] arr) {
        List<Double> l = new ArrayList<>(arr.length);
        for (double v : arr) {
            l.add(v);
        }
        return l;
    }

    /** 数值格式化：整数不带小数点，其余保留有效位并去掉尾随零。 */
    public static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "∞" : "-∞";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        double a = Math.abs(v);
        if (a != 0 && (a >= 1e6 || a < 1e-4)) {
            return String.format("%.4e", v);
        }
        String s = String.format("%.6f", v);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static String fmt(double v, int digits) {
        return String.format("%." + digits + "f", v);
    }
}