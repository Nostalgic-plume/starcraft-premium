package com.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.config.MockMyBatisConfig;
import org.springframework.context.annotation.Import;
import com.shop.common.Result;
import com.shop.mapper.CategoryMapper;
import com.shop.model.entity.CategoryDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CategoryController 单元测试
 * 覆盖分类列表和子分类查询接口
 */
@WebMvcTest(CategoryController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("CategoryController - 分类接口")
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CategoryMapper categoryMapper;

    @Test
    @DisplayName("获取全部分类列表：返回分类集合")
    void list_ShouldReturnCategoryList() throws Exception {
        CategoryDO cat1 = new CategoryDO();
        cat1.setId(1L);
        cat1.setName("手机数码");
        cat1.setParentId(0L);
        cat1.setSort(1);

        given(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .willReturn(List.of(cat1));

        mockMvc.perform(get("/api/category/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", is("操作成功")))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name", is("手机数码")))
                .andExpect(jsonPath("$.data[0].id", is("1")));
    }

    @Test
    @DisplayName("分类列表为空：返回空数组")
    void list_ShouldReturnEmpty_WhenNoCategories() throws Exception {
        given(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/category/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("获取子分类：按 parentId 过滤")
    void children_ShouldReturnChildren() throws Exception {
        CategoryDO child = new CategoryDO();
        child.setId(10L);
        child.setName("手机");
        child.setParentId(1L);

        given(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .willReturn(List.of(child));

        mockMvc.perform(get("/api/category/children")
                        .param("parentId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data[0].parentId", is("1")))
                .andExpect(jsonPath("$.data[0].name", is("手机")));
    }

    @Test
    @DisplayName("获取子分类：父分类下无子分类")
    void children_ShouldReturnEmpty_WhenNoChildren() throws Exception {
        given(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/category/children")
                        .param("parentId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}
