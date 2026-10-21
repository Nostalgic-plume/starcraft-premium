package com.shop.rag.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 同义词扩展配置属性
 * <p>
 * 同义词以「词组」形式配置（组内词互为同义词），便于新增表述且无需维护方向。
 * 配置示例：
 * <pre>
 * rag:
 *   synonym:
 *     enabled: true
 *     groups:
 *       - ["充电宝", "移动电源"]
 * </pre>
 *
 * @author shop
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.synonym")
public class SynonymProperties {

    /** 是否启用同义词扩展（关闭用于 baseline 对比/线上回滚） */
    private boolean enabled = true;

    /** 领域同义词词组列表（组内词互为同义词；组内少于 2 个有效词时该组失效） */
    private List<List<String>> groups = new ArrayList<>();

    /**
     * 错别字纠正表（**单向**：错 → 对），每组首个词是错词，其余是纠正结果
     * <p>
     * 配置示例：
     * <pre>
     * rag:
     *   synonym:
     *     corrections:
     *       - ["手鸡", "手机"]
     * </pre>
     * <p>
     * 为何不用 {@code Map<String, String>}：Spring Boot 的 {@code ConfigurationPropertyName}
     * 只接受 ASCII 合法字符，中文作为 Map key 会在绑定期报
     * {@code ConverterNotFoundException}（实测踩坑）。列表形式与 {@link #groups} 同构，规避该限制。
     * <p>
     * 与 {@link #groups} 的关键区别：groups 是双向的，把「手鸡」和「手机」放进同一组
     * 会让搜「手机」时也混入「手鸡」，引入噪声；纠错必须是单向的。
     * <p>
     * 背景（功能编号 001 实测）：IK 与 MySQL ngram 对错别字都没有容错能力 ——
     * 「手鸡」被 IK 切成「手」「鸡」两个单字，纯 OR 下命中「手口湿巾」等噪声；
     * ngram 则因为「手鸡」这个 2-gram 不在索引里而 0 命中。
     * <p>
     * 这是**人工兜底**：覆盖有限，新错别字需补充。系统性方案（拼音 / 编辑距离纠错）
     * 属独立专项，不在本次范围。
     */
    private List<List<String>> corrections = new ArrayList<>();
}
