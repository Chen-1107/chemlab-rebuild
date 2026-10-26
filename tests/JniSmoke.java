import com.chemlab.core.NativeCore;

import java.util.Arrays;

/** JNI 冒烟测试：验证 Java 能加载 chemlab_jni.dll 并正确调用 C++ 内核。 */
public class JniSmoke {
    public static void main(String[] args) {
        System.out.println("内核可用: " + NativeCore.isAvailable());
        if (!NativeCore.isAvailable()) {
            System.out.println("加载错误: " + NativeCore.loadError());
            System.exit(1);
        }
        System.out.println("版本: " + NativeCore.version());

        double[] d = {1, 2, 3, 4, 5};
        System.out.println("describe: " + Arrays.toString(NativeCore.describe(d)));

        double[] x = {1, 2, 3, 4, 5};
        double[] y = {3, 5, 7, 9, 11};
        System.out.println("linreg: " + Arrays.toString(NativeCore.linearRegression(x, y)));

        System.out.println("pH 强酸 0.1M: " + NativeCore.pHStrongAcid(0.1));
        System.out.println("pH 弱酸(醋酸): " + Arrays.toString(NativeCore.pHWeakAcid(0.1, 1.8e-5)));
        System.out.println("滴定: " + NativeCore.titration(0.1, 22.5, 25.0, 1.0));

        double[] sig = new double[200];
        for (int i = 0; i < 200; i++) {
            double t = i;
            sig[i] = 5 * Math.exp(-Math.pow(t - 50, 2) / 100.0)
                   + 8 * Math.exp(-Math.pow(t - 140, 2) / 200.0);
        }
        System.out.println("findPeaks: " + Arrays.toString(NativeCore.findPeaks(sig, 0.2, 5)));
        System.out.println("savgol 长度: " + NativeCore.savgol(sig, 7, 2).length);

        double[] g = NativeCore.grubbs(new double[]{10.2, 10.1, 10.3, 10.2, 10.4, 10.1, 10.3, 18.0}, 0.05);
        System.out.println("grubbs: " + Arrays.toString(g));

        System.out.println("uncMul: " + Arrays.toString(NativeCore.uncMul(new double[]{10, 5}, new double[]{0.1, 0.2})));

        // 滴定曲线：强碱滴强酸，等当点 pH 应为 7
        double[] ph = NativeCore.titrationCurve(0, 0.1, 25.0, 0.1, 0.0,
                new double[]{0.0, 24.9, 25.0, 25.1});
        System.out.println("titrationCurve pH: " + Arrays.toString(ph));
        if (ph == null || Math.abs(ph[2] - 7.0) > 0.05) {
            System.out.println("滴定曲线等当点 pH 异常");
            System.exit(1);
        }

        // 色谱峰：双高斯峰 + 线性漂移基线
        java.util.List<Double> ts = new java.util.ArrayList<>();
        java.util.List<Double> ss = new java.util.ArrayList<>();
        for (double t = 0.0; t <= 5.0; t += 0.02) {
            ts.add(t);
            ss.add(0.05 + 0.02 * t
                    + Math.exp(-0.5 * Math.pow((t - 2.0) / 0.12, 2))
                    + 0.6 * Math.exp(-0.5 * Math.pow((t - 3.2) / 0.15, 2)));
        }
        double[] res = NativeCore.chromPeaks(toArr(ts), toArr(ss), 0.05, 5, 7);
        if (res == null) {
            System.out.println("chromPeaks 返回 null");
            System.exit(1);
        }
        int nPk = (int) res[0];
        System.out.println("chromPeaks: " + nPk + " 个峰, 峰1 tR=" + res[1]
                + ", 峰2 tR=" + res[9]);
        if (nPk != 2 || Math.abs(res[1] - 2.0) > 0.05 || Math.abs(res[9] - 3.2) > 0.05) {
            System.out.println("色谱峰检测结果异常");
            System.exit(1);
        }

        // 标准溶液配制：0.1 mol/L × 250 mL KHP (M=204.22) 应称 5.1055 g
        double[] prep = NativeCore.stdPrepMass(0.1, 0.250, 204.22, 1.0, 1.0);
        System.out.println("stdPrepMass: " + Arrays.toString(prep));
        if (prep == null || Math.abs(prep[0] - 5.1055) > 1e-4) {
            System.out.println("标准溶液配制称样量异常");
            System.exit(1);
        }

        // 标定法反算：与直接法互逆
        double[] stdc = NativeCore.stdPrepConc(5.1055, 204.22, 1.0, 0.250);
        System.out.println("stdPrepConc: " + Arrays.toString(stdc));
        if (stdc == null || Math.abs(stdc[0] - 0.1) > 1e-9) {
            System.out.println("标定法浓度异常");
            System.exit(1);
        }

        System.out.println("JNI 冒烟测试通过");
    }

    private static double[] toArr(java.util.List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}