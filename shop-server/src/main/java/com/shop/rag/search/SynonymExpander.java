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
 * <p>
 * 另支持**单向错别字纠正**（{@code rag.synonym.corrections}）：错词 → 正确词，只追加正确词，
 * 绝不反向。它不并入 {@link #synonymIndex} 的原因：同义词组是双向的，
 * 把「手鸡」和「手机」放同一组会让搜「手机」时混入「手鸡」。
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

    /** 错词 → 正确词集合（**单向**），与 synonymIndex 分开维护，避免反向污染 */
    private final Map<String, Set<String>> correctionIndex = new HashMap<>();

    /**
     * 构建双向同义词索引
     * <p>
     * 无效配置（null 组、组内有效词少于 2 个、空串/空白词）一律跳过，不抛异常。
     * 包可见以便单测在无 Spring 上下文时手动调用。
     */
    @PostConstruct
    void buildIndex() {
        synonymIndex.clear();
        correctionIndex.clear();
        buildCorrectionIndex();

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
        // 1. 错别字纠正（单向，整串 + 分段都试）
        addCorrections(trimmed, additions);
        // 2. 整串命中（query 本身就是词表词）
        Set<String> direct = synonymIndex.get(trimmed);
        if (direct != null) {
            additions.addAll(direct);
        } else {
            // 3. 按分隔符切段，逐段整词命中
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
     * 单向错别字纠正：把 query 中命中的错词替换词（正确词）追加进 additions
     * <p>
     * 只做「错 → 对」，不做反向 —— 这是与同义词组的本质区别。
     * 整串与分段都尝试，覆盖「手鸡」和「手鸡 多少钱」两种输入。
     *
     * @param text      已 trim 的 query
     * @param additions 待追加集合
     */
    private void addCorrections(String text, Set<String> additions) {
        if (correctionIndex.isEmpty()) {
            return;
        }
        Set<String> whole = correctionIndex.get(text);
        if (whole != null) {
            additions.addAll(whole);
        }
        for (String token : text.split(TOKEN_DELIMITER)) {
            Set<String> corrected = correctionIndex.get(token);
            if (corrected != null) {
                additions.addAll(corrected);
            }
        }
    }

    /**
     * 构建单向纠错索引：每组首个词是错词，其余是纠正结果
     * <p>
     * 配置格式与 {@link #synonymIndex} 同构（{@code List<List<String>>}），
     * 因为 Spring Boot 的 {@code ConfigurationPropertyName} 不接受中文 Map key。
     */
    private void buildCorrectionIndex() {
        List<List<String>> corrections = properties.getCorrections();
        if (corrections == null || corrections.isEmpty()) {
            return;
        }
        int validGroups = 0;
        for (List<String> group : corrections) {
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
            Set<String> targets = correctionIndex.computeIfAbsent(words.get(0), key -> new LinkedHashSet<>());
            for (int i = 1; i < words.size(); i++) {
                targets.add(words.get(i));
            }
            validGroups++;
        }
        log.info("错别字纠正表加载完成: 有效 {} 组, 覆盖 {} 个错词", validGroups, correctionIndex.size());
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
