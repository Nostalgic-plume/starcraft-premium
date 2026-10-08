package com.shop.rag.search;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 领域同义词扩展器
 * <p>
 * 启动时把配置的词组构建为**双向索引**（词 → 同义词集合），检索时把用户 query 扩展为
 * 「原词 + 同义词」，仅交给关键词路（MySQL FULLTEXT）使用。
 * <p>
 * 之所以只用于关键词路：ngram=2 的 FULLTEXT 是纯字面匹配，2-gram 无交集即漏召
 * （「充电宝」切分为 充电/电宝，与「移动电源」的 移动/动电/电源 无交集）；
 * 而向量路本身具备语义泛化能力，扩展词反而会稀释 embedding 的语义重心，故保持原 query。
 * <p>
 * 匹配采用**整词相等**语义：query 整串等于词表词，或按分隔符切段后某段等于词表词。
 * 不做子串匹配——否则「手机壳」会被「手机」命中，造成错误的词义扩散。
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SynonymExpander {

    private final SynonymProperties properties;

    /** 扩展后 query 的最大字符数（防 FULLTEXT query 过长拖慢检索） */
    private static final int MAX_EXPANDED_LENGTH = 100;

    /** query 切分分隔符（空白与中英文常见标点） */
    private static final String TOKEN_DELIMITER = "[\\s,，、;；/|]+";

    /** 词 → 同义词集合（不含自身），双向索引 */
    private final Map<String, Set<String>> synonymIndex = new HashMap<>();

    /**
     * 构建双向同义词索引
     * <p>
     * 无效配置（null 组、组内有效词少于 2 个、空串/空白词）一律跳过，不抛异常。
     * 包可见以便单测在无 Spring 上下文时手动调用。
     */
    @PostConstruct
    void buildIndex() {
        synonymIndex.clear();
        List<List<String>> groups = properties.getGroups();
        if (groups == null || groups.isEmpty()) {
            log.warn("同义词表为空，关键词路不做同义词扩展");
            return;
        }

        int validGroups = 0;
        for (List<String> group : groups) {
            if (group == null || group.size() < 2) {
                continue;
            }
            List<String> words = group.stream()
                    .filter(word -> word != null && !word.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (words.size() < 2) {
                continue;
            }
            for (String word : words) {
                Set<String> synonyms = synonymIndex.computeIfAbsent(word, key -> new LinkedHashSet<>());
                for (String other : words) {
                    if (!other.equals(word)) {
                        synonyms.add(other);
                    }
                }
            }
            validGroups++;
        }
        log.info("同义词表加载完成: 有效 {} 组, 覆盖 {} 个词条", validGroups, synonymIndex.size());
    }

    /**
     * 扩展 query 为「原词 + 同义词（空格分隔）」
     *
     * @param query 原始用户 query（可为 null / 空白）
     * @return 扩展后的关键词串；未启用、无命中或无有效扩展词时原样返回入参
     */
    public String expand(String query) {
        if (!properties.isEnabled() || query == null || query.isBlank()) {
            return query;
        }
        String trimmed = query.trim();

        LinkedHashSet<String> additions = new LinkedHashSet<>();
        // 1. 整串命中（query 本身就是词表词）
        Set<String> direct = synonymIndex.get(trimmed);
        if (direct != null) {
            additions.addAll(direct);
        } else {
            // 2. 按分隔符切段，逐段整词命中
            for (String token : trimmed.split(TOKEN_DELIMITER)) {
                Set<String> synonyms = synonymIndex.get(token);
                if (synonyms != null) {
                    additions.addAll(synonyms);
                }
            }
        }

        if (additions.isEmpty()) {
            return query;
        }
        // 去重：query 中已出现的词不再追加
        additions.removeIf(trimmed::contains);
        if (additions.isEmpty()) {
            return query;
        }
        return appendWithLimit(trimmed, additions);
    }

    /**
     * 追加同义词并受最大长度限制
     *
     * @param query     原始关键词串
     * @param additions 待追加的同义词
     * @return 追加后的关键词串
     */
    private static String appendWithLimit(String query, Collection<String> additions) {
        StringBuilder sb = new StringBuilder(query);
        for (String word : additions) {
            if (sb.length() + 1 + word.length() > MAX_EXPANDED_LENGTH) {
                break;
            }
            sb.append(' ').append(word);
        }
        return sb.toString();
    }
}
