package com.shop.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量索引对账配置（{@code rag.reconcile.*}）
 * <p>
 * 对账用于发现并修复「索引更新失败后永久陈旧且无人知晓」的不一致，
 * 设计见 {@code docs/rag-index-reconcile-design.md}。
 *
 * @author shop
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.reconcile")
public class IndexReconcileProperties {

    /** 总开关：出问题时可一键关闭，不影响检索链路 */
    private boolean enabled = true;

    /** 执行间隔（毫秒），默认 30 分钟 */
    private long fixedRateMs = 1_800_000L;

    /**
     * 自动修复的差异比例上限
     * <p>
     * 差异（漏建 + 多余 + 陈旧）超过 {@code max(应索引数, 现存数) × 该值} 时**只告警不修复**：
     * 大规模差异通常意味着环境异常（Qdrant 刚被清空、MySQL 查询不完整），
     * 此时自动修复可能演变成大规模误删/误建。
     */
    private double maxAutoFixRatio = 0.2;

    /** Qdrant scroll 每页条数 */
    private int scrollBatchSize = 256;
}
