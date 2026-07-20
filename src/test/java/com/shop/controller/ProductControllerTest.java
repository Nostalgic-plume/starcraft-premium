package com.shop.controller;

import com.shop.common.PageResult;
import com.shop.config.MockMyBatisConfig;
import org.springframework.context.annotation.Import;
import com.shop.common.Result;
import com.shop.model.dto.ProductDTO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.ProductVO;
import com.shop.service.ProductService;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ProductController 单元测试
 * 覆盖商品分页、详情、新增、状态更新接口
 * 注意：@SaCheckPermission 注解在 @WebMvcTest 中不生效，方法正常执行
 */
@WebMvcTest(ProductController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("ProductController - 商品接口")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @Test
    @DisplayName("商品分页列表：返回分页结果")
    void page_ShouldReturnPageResult() throws Exception {
        ProductVO vo = new ProductVO();
        vo.setId(1L);
        vo.setName("测试商品");
        vo.setMinPrice(new BigDecimal("99.00"));

        PageResult<ProductVO> pageResult = new PageResult<>();
        pageResult.setList(List.of(vo));
        pageResult.setTotal(1L);

        given(productService.page(any(ProductPageQuery.class))).willReturn(pageResult);

        mockMvc.perform(get("/api/product/page")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.total", is("1")))
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.list[0].name", is("测试商品")));
    }

    @Test
    @DisplayName("商品分页列表：无结果返回空分页")
    void page_ShouldReturnEmpty_WhenNoResults() throws Exception {
        PageResult<ProductVO> emptyResult = new PageResult<>();
        emptyResult.setList(List.of());
        emptyResult.setTotal(0L);

        given(productService.page(any(ProductPageQuery.class))).willReturn(emptyResult);

        mockMvc.perform(get("/api/product/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.total", is("0")))
                .andExpect(jsonPath("$.data.list", hasSize(0)));
    }

    @Test
    @DisplayName("商品详情：返回商品 VO")
    void detail_ShouldReturnProductVO() throws Exception {
        ProductVO vo = new ProductVO();
        vo.setId(1L);
        vo.setName("测试商品");
        vo.setMinPrice(new BigDecimal("99.00"));

        given(productService.getDetail(1L)).willReturn(vo);

        mockMvc.perform(get("/api/product/detail/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.name", is("测试商品")));
    }

    @Test
    @DisplayName("商品详情：商品不存在时返回业务异常")
    void detail_ShouldHandleNotFound() throws Exception {
        given(productService.getDetail(999L)).willThrow(new com.shop.common.BusinessException("商品不存在"));

        mockMvc.perform(get("/api/product/detail/{id}", 999L))
                .andExpect(status().isOk()) // GlobalExceptionHandler 返回 HTTP 200
                .andExpect(jsonPath("$.code", is(500)))
                .andExpect(jsonPath("$.msg", is("商品不存在")));
    }

    @Test
    @DisplayName("新增商品：返回商品 ID")
    void add_ShouldReturnProductId() throws Exception {
        given(productService.addProduct(any(ProductDTO.class))).willReturn(100L);

        String requestBody = """
                {
                    "name": "新商品",
                    "categoryId": 1,
                    "images": ["http://example.com/img.jpg"],
                    "skuList": [
                        {"name": "SKU1", "price": 99.00, "stock": 10}
                    ]
                }
                """;

        mockMvc.perform(post("/api/product/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", is("新增成功")))
                .andExpect(jsonPath("$.data", is("100")));
    }

    @Test
    @DisplayName("更新商品状态：成功返回")
    void updateStatus_ShouldSucceed() throws Exception {
        doNothing().when(productService).updateStatus(1L, 0);

        mockMvc.perform(put("/api/product/status/{id}", 1L)
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }
}
