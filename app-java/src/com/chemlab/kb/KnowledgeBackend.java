package com.chemlab.kb;

import java.util.List;

/**
 * 知识后端抽象：把「知识从哪来」与「界面怎么用」解耦。
 *
 * <p>本地参考书与 ima 知识库是同级的两个实现，界面只依赖本接口。任一实现
 * 不可用时由 {@link #available()} 暴露，调用方降级即可，不影响其余功能——
 * 与项目既有的「能力探测 + 优雅降级」约定一致。
 */
public interface KnowledgeBackend {

    /** 来源标识，与 {@link Hit#source} 一致。 */
    String id();

    /** 展示名。 */
    String name();

    /** 能力探测：数据就绪 / 凭据齐备，且最近一次连通成功。 */
    boolean available();

    /** 关键词检索；无结果或不可用时返回空列表，不抛异常。 */
    List<Hit> search(String query, int limit);

    /** 取命中项正文；取不到时返回 null。 */
    String readText(Hit hit);

    default void close() {
    }
}
