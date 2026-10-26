package com.chemlab.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * 多来源检索融合。
 *
 * <p>本地子串匹配与 ima 语义召回的分数尺度不可直接比较，因此各来源先在自身内部
 * 归一化到 [0,1]（由各后端完成），再按 {@code 0.4 × 本地 + 0.6 × ima} 加权合并；
 * 同分时本地优先（离线可读、无网络依赖）。结果列表按分数降序，稳定排序保证同分
 * 时仍保持来源内的原有顺序。
 */
public final class HybridBackend {

    public static final String SOURCE_ALL = "all";
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_IMA = "ima";

    private static final double W_LOCAL = 0.4;
    private static final double W_IMA = 0.6;

    private final LocalKbBackend local;
    private final ImaKbBackend ima;
    private String lastNote = "";

    public HybridBackend(LocalKbBackend local, ImaKbBackend ima) {
        this.local = local;
        this.ima = ima;
    }

    public LocalKbBackend local() {
        return local;
    }

    public ImaKbBackend ima() {
        return ima;
    }

    /** ima 凭据是否齐备（决定来源下拉是否包含 ima）。 */
    public boolean imaConfigured() {
        return ima != null && ima.available();
    }

    /** 上一次检索的降级提示；无异常时为空串。 */
    public String lastNote() {
        return lastNote;
    }

    // ------------------------------------------------------------------

    /**
     * 按来源检索并融合。
     *
     * @param source          {@link #SOURCE_ALL} / {@link #SOURCE_LOCAL} / {@link #SOURCE_IMA}
     * @param localBookScope  本地检索的书目限定；null 表示全部书目
     */
    public List<Hit> search(String source, String query, int limit, String localBookScope) {
        lastNote = "";
        List<Hit> merged = new ArrayList<>();
        boolean wantLocal = SOURCE_LOCAL.equals(source) || SOURCE_ALL.equals(source);
        boolean wantIma = SOURCE_IMA.equals(source) || SOURCE_ALL.equals(source);

        if (wantLocal && local != null && local.available()) {
            for (Hit h : local.search(query, limit, localBookScope)) {
                h.score *= W_LOCAL;
                merged.add(h);
            }
        }
        if (wantIma) {
            if (!imaConfigured()) {
                if (SOURCE_IMA.equals(source)) {
                    lastNote = "尚未配置 ima 凭据，请先在「ima 知识库」页完成配置";
                }
            } else {
                List<Hit> ih = ima.search(query, limit);
                for (Hit h : ih) {
                    h.score *= W_IMA;
                    merged.add(h);
                }
                if (ih.isEmpty() && !ima.lastError().isEmpty()) {
                    lastNote = "ima 暂不可用，已仅显示本地结果（" + ima.lastError() + "）";
                }
            }
        }

        merged.sort((a, b) -> {
            int c = Double.compare(b.score, a.score);
            return c != 0 ? c : Boolean.compare(b.isLocal(), a.isLocal());
        });
        if (limit > 0 && merged.size() > limit) {
            return new ArrayList<>(merged.subList(0, limit));
        }
        return merged;
    }

    /** 取命中项正文：本地按 ref 解析页 / 条目，ima 用检索返回的正文。 */
    public String readText(Hit hit) {
        if (hit == null) {
            return null;
        }
        if (hit.isLocal()) {
            return local == null ? null : local.readText(hit);
        }
        return ima == null ? null : ima.readText(hit);
    }
}
