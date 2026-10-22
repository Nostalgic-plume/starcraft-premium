package com.shop.service;

import com.shop.common.PageResult;
import com.shop.model.dto.ReviewCreateDTO;
import com.shop.model.vo.ReviewVO;

import java.util.List;

/**
 * 商品评价服务
 *
 * @author shop
 */
public interface ReviewService {

    /**
     * 发表评价
     * <p>
     * 校验链：订单项存在 → 订单归属当前用户 → 订单已收货/已完成 → 该订单项未评价。
     *
     * @param dto 评价参数
     * @throws com.shop.common.BusinessException 任一校验不通过
     */
    void create(ReviewCreateDTO dto);

    /**
     * 当前用户的评价列表（按时间倒序）
     *
     * @return 评价视图列表
     */
    List<ReviewVO> myReviews();

    /**
     * 商品评价列表（公开接口，仅返回显示中的评价）
     *
     * @param productId 商品 ID
     * @param page      页码
     * @param pageSize  每页条数
     * @return 分页结果
     */
    PageResult<ReviewVO> productReviews(Long productId, Integer page, Integer pageSize);

    /**
     * 管理端评价分页（含隐藏评价，支持状态与商品名筛选）
     *
     * @param page     页码
     * @param pageSize 每页条数
     * @param status   状态筛选（可选）
     * @param keyword  商品名关键字（可选）
     * @return 分页结果
     */
    PageResult<ReviewVO> adminPage(Integer page, Integer pageSize, Integer status, String keyword);

    /**
     * 修改评价状态（0隐藏 1显示）
     *
     * @param id     评价 ID
     * @param status 目标状态
     */
    void updateStatus(Long id, Integer status);

    /**
     * 删除评价（物理删除，释放订单项唯一键）
     *
     * @param id 评价 ID
     */
    void delete(Long id);

    /**
     * 商家回复评价
     *
     * @param id    评价 ID
     * @param reply 回复内容
     */
    void reply(Long id, String reply);
}
