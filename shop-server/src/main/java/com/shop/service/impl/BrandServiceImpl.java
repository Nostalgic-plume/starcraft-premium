package com.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.mapper.BrandMapper;
import com.shop.model.entity.BrandDO;
import com.shop.service.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 品牌服务实现
 * <p>
 * 当前**只提供读**：品牌主数据由 `main/resources/db/migrate-brand-20260927.sql`
 * 从商品表历史 brand 列去重一次性回填，尚无增删改入口。
 * 商品表单需要品牌下拉选项（否则新增/编辑商品时 brand_id 无法赋值，恒为 NULL），故先补齐列表查询。
 * <p>
 * 未加分类那样的 Redis 缓存：品牌仅百余条、变更极少，且只在打开商品表单时查询，回源 DB 成本可忽略。
 *
 * @author shop
 */
@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    /** 品牌状态：0=正常 1=停用（全库统一约定，同 user / admin / category） */
    private static final int STATUS_ENABLED = 0;

    private final BrandMapper brandMapper;

    /**
     * 查询启用状态的品牌列表（按 sort 升序）
     *
     * @return 启用品牌列表 {@code List<BrandDO>}
     */
    @Override
    public List<BrandDO> list() {
        return brandMapper.selectList(new LambdaQueryWrapper<BrandDO>()
                .eq(BrandDO::getStatus, STATUS_ENABLED)
                .orderByAsc(BrandDO::getSort));
    }
}
