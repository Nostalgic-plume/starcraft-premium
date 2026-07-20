package com.shop.controller;

import com.shop.common.PageResult;
import com.shop.config.MockMyBatisConfig;
import org.springframework.context.annotation.Import;
import com.shop.common.Result;
import com.shop.model.dto.ProductDTO;
import com.shop.model.dto.ShopLoginDTO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.DashboardStatsVO;
import com.shop.model.vo.OrderVO;
import com.shop.model.vo.ProductVO;
import com.shop.service.OrderService;
import com.shop.service.ProductService;
import com.shop.service.ShopService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MerchantController 单元测试
 * 覆盖商家登录、统计、订单管理、商品管理接口
 * 注意：@SaCheckPermission 在 @WebMvcTest 中不生效，方法正常执行
 */
@WebMvcTest(MerchantController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("MerchantController - 商家接口")
class MerchantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShopService shopService;

    @MockBean
    private OrderService orderService;

    @MockBean
    private ProductService productService;

    @Test
    @DisplayName("商家登录：返回 token 和商家信息")
    void login_ShouldReturnToken() throws Exception {
        Map<String, Object> loginResult = Map.of(
                "token", "merchant-token-xxx",
                "shopId", 50L,
                "shopName", "测试店铺"
        );
        given(shopService.login(any(ShopLoginDTO.class))).willReturn(loginResult);

        String requestBody = """
                {"username": "shop001", "password": "123456"}
                """;

        mockMvc.perform(post("/api/merchant/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.token", is("merchant-token-xxx")));
    }

    @Test
    @DisplayName("商家控制台概览：返回统计数据")
    void dashboard_ShouldReturnStats() throws Exception {
        DashboardStatsVO stats = new DashboardStatsVO();
        stats.setTotalOrders(100L);
        stats.setTotalProducts(50L);
        stats.setTotalSales(99999.00);

        given(shopService.getDashboard()).willReturn(stats);

        mockMvc.perform(get("/api/merchant/stats/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.totalOrders", is("100")))
                .andExpect(jsonPath("$.data.totalProducts", is("50")));
    }

    @Test
    @DisplayName("商家订单分页列表：返回本店订单")
    void orderPage_ShouldReturnShopOrders() throws Exception {
        given(shopService.getCurrentShopId()).willReturn(50L);

        OrderVO vo = new OrderVO();
        vo.setId(1001L);
        vo.setOrderNo("ORD20260718001");
        PageResult<OrderVO> pageResult = new PageResult<>();
        pageResult.setList(List.of(vo));
        pageResult.setTotal(1L);

        given(orderService.merchantPageOrders(anyInt(), anyInt(), any(), eq(50L)))
                .willReturn(pageResult);

        mockMvc.perform(get("/api/merchant/order/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.total", is("1")));
    }

    @Test
    @DisplayName("商家订单详情：返回订单信息")
    void orderDetail_ShouldReturnOrder() throws Exception {
        given(shopService.getCurrentShopId()).willReturn(50L);

        OrderVO vo = new OrderVO();
        vo.setId(1001L);
        vo.setOrderNo("ORD20260718001");

        given(orderService.merchantGetDetail(1001L, 50L)).willReturn(vo);

        mockMvc.perform(get("/api/merchant/order/detail/{orderId}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.id", is("1001")));
    }

    @Test
    @DisplayName("商家发货：成功发货")
    void deliver_ShouldSucceed() throws Exception {
        given(shopService.getCurrentShopId()).willReturn(50L);
        doNothing().when(orderService).merchantDeliver(1001L, 50L, "SF123456789");

        String requestBody = """
                {"orderId": 1001, "logisticsNo": "SF123456789"}
                """;

        mockMvc.perform(post("/api/merchant/order/deliver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", is("发货成功")));
    }

    @Test
    @DisplayName("商家发货：无物流单号时使用空字符串")
    void deliver_ShouldUseEmptyLogisticsNo_WhenNotProvided() throws Exception {
        given(shopService.getCurrentShopId()).willReturn(50L);
        doNothing().when(orderService).merchantDeliver(1001L, 50L, "");

        String requestBody = """
                {"orderId": 1001}
                """;

        mockMvc.perform(post("/api/merchant/order/deliver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("商家商品列表：返回本店商品")
    void productPage_ShouldReturnShopProducts() throws Exception {
        given(shopService.getCurrentShopId()).willReturn(50L);

        ProductVO vo = new ProductVO();
        vo.setId(1L);
        vo.setName("商家商品");
        PageResult<ProductVO> pageResult = new PageResult<>();
        pageResult.setList(List.of(vo));
        pageResult.setTotal(1L);

        given(productService.page(any(ProductPageQuery.class))).willReturn(pageResult);

        mockMvc.perform(get("/api/merchant/product/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.list[0].name", is("商家商品")));
    }

    @Test
    @DisplayName("商家新增商品：返回商品 ID")
    void addProduct_ShouldReturnProductId() throws Exception {
        given(productService.addProduct(any(ProductDTO.class))).willReturn(200L);

        String requestBody = """
                {
                    "name": "商家新商品",
                    "categoryId": 10,
                    "images": ["http://example.com/img.jpg"],
                    "skuList": [{"name": "SKU1", "price": 29.90, "stock": 50}]
                }
                """;

        mockMvc.perform(post("/api/merchant/product/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", is("新增成功")))
                .andExpect(jsonPath("$.data", is("200")));
    }

    @Test
    @DisplayName("商家更新商品状态：成功更新")
    void updateStatus_ShouldSucceed() throws Exception {
        doNothing().when(productService).updateStatus(1L, 1);

        mockMvc.perform(put("/api/merchant/product/status/{id}", 1L)
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }
}
