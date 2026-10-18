package com.shop.rag.chat;

import com.shop.model.entity.CategoryDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 分类回退的「分类名匹配」测试（纯静态方法，不依赖 Spring / 数据库）
 * <p>
 * 锁定的契约：**最长名称优先**。分类名之间存在包含关系（「手机」⊂「手机壳」），
 * 而分类列表的返回顺序不确定 —— 首个命中即返回会把「我想买手机壳」匹配到「手机」，
 * 回退到错误的一级分类。见 {@code docs/rag-candidate-cleanup-design.md} 的 P3 记录。
 *
 * @author auto
 */
@DisplayName("ChatService - 分类名匹配（最长优先）")
class ChatServiceCategoryMatchTest {

    private static CategoryDO category(long id, String name, long parentId) {
        CategoryDO category = new CategoryDO();
        category.setId(id);
        category.setName(name);
        category.setParentId(parentId);
        return category;
    }

    @Test
    @DisplayName("存在包含关系时选更长的分类名")
    void shouldPreferLongerName() {
        CategoryDO phone = category(101, "手机", 100);
        CategoryDO phoneCase = category(104, "手机壳", 100);

        // 顺序刻意把短名放在前面：模拟数据库返回顺序不确定
        CategoryDO matched = ChatService.matchCategory(List.of(phone, phoneCase), "我想买手机壳");

        assertSame(phoneCase, matched, "「手机壳」比「手机」更具体，必须优先");
    }

    @Test
    @DisplayName("一级分类名包含二级分类名时，选更长的一级分类")
    void shouldPreferLongerTopCategory() {
        CategoryDO digital = category(2, "手机数码", 0);
        CategoryDO phone = category(101, "手机", 2);

        CategoryDO matched = ChatService.matchCategory(List.of(phone, digital), "看看手机数码");

        assertSame(digital, matched);
    }

    @Test
    @DisplayName("单个命中：正常返回")
    void shouldMatchSingleCategory() {
        CategoryDO appliance = category(1, "家用电器", 0);

        assertSame(appliance, ChatService.matchCategory(List.of(appliance), "家用电器有哪些"));
    }

    @Test
    @DisplayName("无命中返回 null")
    void shouldReturnNullWhenNoMatch() {
        CategoryDO appliance = category(1, "家用电器", 0);

        assertNull(ChatService.matchCategory(List.of(appliance), "推荐一款手机"));
    }

    @Test
    @DisplayName("空输入：null / 空列表 / 空消息均返回 null")
    void shouldHandleEmptyInputs() {
        assertNull(ChatService.matchCategory(null, "手机"));
        assertNull(ChatService.matchCategory(List.of(), "手机"));
        assertNull(ChatService.matchCategory(List.of(category(1, "手机", 0)), null));
        assertNull(ChatService.matchCategory(List.of(category(1, "手机", 0)), "  "));
    }

    @Test
    @DisplayName("分类名为空或分类为 null 时跳过，不抛异常")
    void shouldSkipInvalidCategories() {
        CategoryDO noName = category(1, null, 0);
        CategoryDO blankName = category(2, "  ", 0);
        CategoryDO phone = category(101, "手机", 100);

        assertEquals(phone, ChatService.matchCategory(List.of(noName, blankName, phone), "买手机"));
    }
}
