package com.shop.controller;

import com.shop.common.Result;
import com.shop.config.MockMyBatisConfig;
import com.shop.config.SaTokenConfig;
import org.springframework.context.annotation.Import;
import com.shop.model.dto.CartDTO;
import com.shop.model.vo.CartVO;
import com.shop.service.CartService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CartController 单元测试
 * 覆盖购物车列表、添加、修改数量、删除、选中操作接口
 * 注意：SaTokenConfig 被 @MockBean 整体替换，SaInterceptor 不注册，注解鉴权在 @WebMvcTest 中不生效，方法正常执行
 */
@WebMvcTest(CartController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("CartController - 购物车接口")
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CartService cartService;

    @MockBean
    private SaTokenConfig saTokenConfig;

    @Test
    @DisplayName("购物车列表：返回购物车 VO 列表")
    void list_ShouldReturnCartList() throws Exception {
        CartVO vo = new CartVO();
        vo.setSkuId(100L);
        vo.setSkuName("测试SKU");
        vo.setQuantity(2);
        vo.setPrice(new BigDecimal("99.00"));

        given(cartService.list()).willReturn(List.of(vo));

        mockMvc.perform(get("/api/cart/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].skuId", is("100")))
                .andExpect(jsonPath("$.data[0].quantity", is(2)));
    }

    @Test
    @DisplayName("购物车列表：空列表返回空数组")
    void list_ShouldReturnEmpty_WhenCartEmpty() throws Exception {
        given(cartService.list()).willReturn(List.of());

        mockMvc.perform(get("/api/cart/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("添加到购物车：成功添加")
    void add_ShouldSucceed() throws Exception {
        doNothing().when(cartService).addItem(any(CartDTO.class));

        String requestBody = """
                {
                    "skuId": 100,
                    "quantity": 2
                }
                """;

        mockMvc.perform(post("/api/cart/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", containsString("添加到购物车")));
    }

    @Test
    @DisplayName("修改购物车商品数量：成功更新")
    void updateQuantity_ShouldSucceed() throws Exception {
        doNothing().when(cartService).updateQuantity(100L, 3);

        mockMvc.perform(put("/api/cart/quantity/{skuId}", 100L)
                        .param("quantity", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("删除购物车商品：成功删除")
    void remove_ShouldSucceed() throws Exception {
        doNothing().when(cartService).removeItem(100L);

        mockMvc.perform(delete("/api/cart/remove/{skuId}", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("选中/取消选中购物车商品：成功操作")
    void check_ShouldSucceed() throws Exception {
        doNothing().when(cartService).checkItem(100L, true);

        mockMvc.perform(put("/api/cart/check/{skuId}", 100L)
                        .param("checked", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("取消选中购物车商品")
    void check_ShouldUncheckItem() throws Exception {
        doNothing().when(cartService).checkItem(100L, false);

        mockMvc.perform(put("/api/cart/check/{skuId}", 100L)
                        .param("checked", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }
}
