package com.shop.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.common.StpUserUtil;
import com.shop.constant.OrderConstants;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.mapper.ReviewMapper;
import com.shop.mapper.UserMapper;
import com.shop.model.dto.ReviewCreateDTO;
import com.shop.model.entity.OrderDO;
import com.shop.model.entity.OrderItemDO;
import com.shop.model.entity.ReviewDO;
import com.shop.model.entity.UserDO;
import com.shop.model.vo.ReviewVO;
import com.shop.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品评价服务实现
 * <p>
 * 三条硬约束：
 * <ol>
 *   <li><b>只能评价自己的订单项</b>：订单归属由服务端反查，前端只传 orderItemId；</li>
 *   <li><b>只有已收货 / 已完成的订单可评价</b>：未收货就评价等于没体验过商品；</li>
 *   <li><b>一个订单项只能评价一次</b>：应用层查重 + uk_order_item 唯一索引双保险。</li>
 * </ol>
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    /** 评价状态：0 隐藏 / 1 显示 */
    private static final int STATUS_HIDDEN = 0;
    private static final int STATUS_VISIBLE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final ReviewMapper reviewMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(ReviewCreateDTO dto) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();

        OrderItemDO item = orderItemMapper.selectById(dto.getOrderItemId());
        if (item == null) {
            throw new BusinessException("订单项不存在");
        }
        OrderDO order = orderMapper.selectById(item.getOrderId());
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            // 不区分「订单不存在」与「不属于你」：区分会泄露他人订单项是否存在
            throw new BusinessException("订单项不存在");
        }
        if (order.getStatus() == null
                || (order.getStatus() != OrderConstants.STATUS_RECEIVED
                && order.getStatus() != OrderConstants.STATUS_COMPLETED)) {
            throw new BusinessException("订单尚未确认收货，暂不能评价");
        }

        Long exists = reviewMapper.selectCount(new LambdaQueryWrapper<ReviewDO>()
                .eq(ReviewDO::getOrderItemId, dto.getOrderItemId()));
        if (exists > 0) {
            throw new BusinessException("该商品已评价，请勿重复评价");
        }

        ReviewDO review = new ReviewDO();
        review.setUserId(userId);
        review.setOrderId(order.getId());
        review.setOrderItemId(item.getId());
        review.setProductId(item.getProductId());
        review.setSkuId(item.getSkuId());
        // 存下单时快照：商品后续改名不影响已发表评价
        review.setProductName(item.getProductName());
        review.setProductImage(item.getProductMainImage());
        review.setRating(dto.getRating());
        review.setContent(dto.getContent());
        review.setImages(dto.getImages() == null || dto.getImages().isEmpty()
                ? null : JSONUtil.toJsonStr(dto.getImages()));
        review.setIsAnonymous(dto.getIsAnonymous() != null && dto.getIsAnonymous() == 1 ? 1 : 0);
        review.setStatus(STATUS_VISIBLE);

        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一索引兜底
            throw new BusinessException("该商品已评价，请勿重复评价");
        }
        log.info("评价创建成功, reviewId: {}, orderItemId: {}", review.getId(), dto.getOrderItemId());
    }

    @Override
    public List<ReviewVO> myReviews() {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        List<ReviewDO> reviews = reviewMapper.selectList(new LambdaQueryWrapper<ReviewDO>()
                .eq(ReviewDO::getUserId, userId)
                .orderByDesc(ReviewDO::getCreateTime)
                .orderByDesc(ReviewDO::getId));
        return toVOList(reviews, true);
    }

    @Override
    public PageResult<ReviewVO> productReviews(Long productId, Integer page, Integer pageSize) {
        if (productId == null) {
            return PageResult.of(new Page<>(1, 10));
        }
        Page<ReviewDO> result = reviewMapper.selectPage(pageOf(page, pageSize),
                new LambdaQueryWrapper<ReviewDO>()
                        .eq(ReviewDO::getProductId, productId)
                        .eq(ReviewDO::getStatus, STATUS_VISIBLE)
                        .orderByDesc(ReviewDO::getCreateTime)
                        .orderByDesc(ReviewDO::getId));
        return PageResult.of(result, toVOList(result.getRecords(), false));
    }

    @Override
    public PageResult<ReviewVO> adminPage(Integer page, Integer pageSize, Integer status, String keyword) {
        Page<ReviewDO> result = reviewMapper.selectPage(pageOf(page, pageSize),
                new LambdaQueryWrapper<ReviewDO>()
                        .eq(status != null, ReviewDO::getStatus, status)
                        .like(keyword != null && !keyword.isBlank(), ReviewDO::getProductName, keyword)
                        .orderByDesc(ReviewDO::getCreateTime)
                        .orderByDesc(ReviewDO::getId));
        return PageResult.of(result, toVOList(result.getRecords(), true));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        ReviewDO review = requireExists(id);
        if (status == null || (status != STATUS_HIDDEN && status != STATUS_VISIBLE)) {
            throw new BusinessException("评价状态只能是 0（隐藏）或 1（显示）");
        }
        review.setStatus(status);
        reviewMapper.updateById(review);
        log.info("评价状态更新, reviewId: {}, status: {}", id, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireExists(id);
        // 物理删除：逻辑删除会继续占用 uk_order_item，导致该订单项永远无法再次评价
        reviewMapper.hardDeleteById(id);
        log.info("评价删除, reviewId: {}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reply(Long id, String reply) {
        if (reply == null || reply.isBlank()) {
            throw new BusinessException("回复内容不能为空");
        }
        ReviewDO review = requireExists(id);
        review.setReply(reply.trim());
        review.setReplyTime(LocalDateTime.now());
        reviewMapper.updateById(review);
        log.info("评价回复成功, reviewId: {}", id);
    }

    private ReviewDO requireExists(Long id) {
        ReviewDO review = id == null ? null : reviewMapper.selectById(id);
        if (review == null) {
            throw new BusinessException("评价不存在");
        }
        return review;
    }

    private static Page<ReviewDO> pageOf(Integer page, Integer pageSize) {
        int current = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, MAX_PAGE_SIZE);
        return new Page<>(current, size);
    }

    /**
     * 实体 → 视图（批量查昵称，避免 N+1）
     *
     * @param reviews      评价列表
     * @param withNickname 是否填充昵称（公开商品评价可按需关闭）
     */
    private List<ReviewVO> toVOList(List<ReviewDO> reviews, boolean withNickname) {
        if (reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nicknameMap = withNickname ? loadNicknames(reviews) : Map.of();

        return reviews.stream().map(review -> {
            ReviewVO vo = new ReviewVO();
            vo.setId(review.getId());
            vo.setOrderItemId(review.getOrderItemId());
            vo.setProductId(review.getProductId());
            vo.setProductName(review.getProductName());
            vo.setProductImage(review.getProductImage());
            vo.setStatus(review.getStatus());
            vo.setRating(review.getRating());
            vo.setContent(review.getContent());
            vo.setImages(parseImages(review.getImages()));
            boolean anonymous = review.getIsAnonymous() != null && review.getIsAnonymous() == 1;
            vo.setAnonymous(anonymous);
            if (withNickname) {
                vo.setNickname(anonymous ? "匿名用户"
                        : nicknameMap.getOrDefault(review.getUserId(), "用户" + review.getUserId()));
            }
            vo.setReply(review.getReply());
            vo.setReplyTime(review.getReplyTime());
            vo.setCreateTime(review.getCreateTime());
            return vo;
        }).toList();
    }

    private Map<Long, String> loadNicknames(List<ReviewDO> reviews) {
        Set<Long> userIds = reviews.stream().map(ReviewDO::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream().collect(Collectors.toMap(
                UserDO::getId,
                user -> user.getNickname() != null && !user.getNickname().isBlank()
                        ? user.getNickname() : user.getUsername(),
                (a, b) -> a));
    }

    private static List<String> parseImages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSONUtil.toList(json, String.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
