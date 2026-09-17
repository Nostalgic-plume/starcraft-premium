package com.shop.rag;

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
}
