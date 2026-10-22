package com.shop.service;

import com.shop.model.vo.ProductVO;

import java.util.List;

/**
 * 商品收藏服务
 * <p>
 * 全部方法以当前登录用户为边界，userId 由服务端从会话注入。
 *
 * @author shop
 */
public interface FavoriteService {

    /**
     * 当前用户的收藏商品列表（按收藏时间倒序，跳过已删除 / 已下架商品）
     *
     * @return 商品视图列表
     */
    List<ProductVO> list();

    /**
     * 收藏商品（幂等：已收藏时直接返回成功）
     *
     * @param productId 商品 ID
     * @throws com.shop.common.BusinessException 商品不存在或未上架
     */
    void add(Long productId);

    /**
     * 取消收藏（未收藏时静默忽略）
     *
     * @param productId 商品 ID
     */
    void remove(Long productId);

    /**
     * 当前用户是否已收藏该商品
     *
     * @param productId 商品 ID
     * @return true 已收藏
     */
    boolean isFavorite(Long productId);

    /**
     * 当前用户的收藏总数
     *
     * @return 收藏数
     */
    long count();
}
