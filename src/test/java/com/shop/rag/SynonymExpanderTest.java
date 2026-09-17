package com.shop.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SynonymExpander 单元测试（纯 POJO，不依赖 Spring / Qdrant / MySQL）
 */
@DisplayName("SynonymExpander - 领域同义词双向扩展")
class SynonymExpanderTest {

    private static SynonymExpander expanderWith(List<List<String>> groups) {
        SynonymProperties properties = new SynonymProperties();
        properties.setEnabled(true);
        properties.setGroups(groups);
        SynonymExpander expander = new SynonymExpander(properties);
        expander.buildIndex();
        return expander;
    }

    private static List<List<String>> defaultGroups() {
        return List.of(
                List.of("充电宝", "移动电源", "充电电池"),
                List.of("刮胡刀", "剃须刀", "电动剃须刀"),
                List.of("球鞋", "运动鞋", "跑鞋", "跑步鞋"));
    }

    @Test
    @DisplayName("整串命中：原词在前，同义词按组内顺序追加")
    void expand_shouldAppendSynonymsForWholeQuery() {
        SynonymExpander expander = expanderWith(defaultGroups());

        assertEquals("充电宝 移动电源 充电电池", expander.expand("充电宝"));
    }

    @Test
    @DisplayName("双向生效：组内任一方向的词都能扩展")
    void expand_shouldWorkBidirectionally() {
        SynonymExpander expander = expanderWith(defaultGroups());

        assertEquals("移动电源 充电宝 充电电池", expander.expand("移动电源"));
        assertEquals("剃须刀 刮胡刀 电动剃须刀", expander.expand("剃须刀"));
    }

    @Test
    @DisplayName("分段命中：多词 input 按分隔符逐段扩展")
    void expand_shouldExpandMatchingToken() {
        SynonymExpander expander = expanderWith(defaultGroups());

        assertEquals("充电宝 防水 移动电源 充电电池", expander.expand("充电宝 防水"));
        assertEquals("我想要 球鞋 运动鞋 跑鞋 跑步鞋", expander.expand("我想要 球鞋"));
    }

    @Test
    @DisplayName("禁止子串扩散：词表含「手机」时，「手机壳」不得被扩展")
    void expand_shouldNotMatchSubstring() {
        SynonymExpander expander = expanderWith(List.of(List.of("手机", "移动电话")));

        assertEquals("手机壳", expander.expand("手机壳"));
        assertEquals("手机 移动电话", expander.expand("手机"));
    }

    @Test
    @DisplayName("已出现的同义词不重复追加")
    void expand_shouldDeduplicate() {
        SynonymExpander expander = expanderWith(defaultGroups());

        assertEquals("充电宝 移动电源 充电电池", expander.expand("充电宝 移动电源"));
    }

    @Test
    @DisplayName("开关关闭：原样返回，便于 baseline 对比与回滚")
    void expand_shouldReturnOriginalWhenDisabled() {
        SynonymProperties properties = new SynonymProperties();
        properties.setEnabled(false);
        properties.setGroups(defaultGroups());
        SynonymExpander expander = new SynonymExpander(properties);
        expander.buildIndex();

        assertEquals("充电宝", expander.expand("充电宝"));
    }

    @Test
    @DisplayName("空输入 / 无命中：原样返回")
    void expand_shouldReturnOriginalWhenNoHit() {
        SynonymExpander expander = expanderWith(defaultGroups());

        assertNull(expander.expand(null));
        assertEquals("  ", expander.expand("  "));
        assertEquals("洗衣机", expander.expand("洗衣机"));
    }

    @Test
    @DisplayName("非法词组被忽略且不抛异常")
    void buildIndex_shouldIgnoreInvalidGroups() {
        SynonymExpander expander = expanderWith(Arrays.asList(
                null,
                List.of("单词组"),
                Arrays.asList("有效甲", "无效", null),
                List.of("键盘", "键盘")));

        assertEquals("有效甲 无效", expander.expand("有效甲"));
        assertEquals("单词组", expander.expand("单词组"));
        assertEquals("键盘", expander.expand("键盘"));
    }

    @Test
    @DisplayName("空词表：功能静默失效，query 原样返回")
    void expand_shouldReturnOriginalWhenDictionaryEmpty() {
        SynonymExpander expander = expanderWith(List.of());

        assertEquals("充电宝", expander.expand("充电宝"));
    }
}
