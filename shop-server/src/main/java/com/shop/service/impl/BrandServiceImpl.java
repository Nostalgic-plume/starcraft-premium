package com.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.mapper.BrandMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.dto.BrandDTO;
import com.shop.model.entity.BrandDO;
import com.shop.model.entity.ProductDO;
import com.shop.service.BrandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 品牌服务实现
 * <p>
 * 品牌是商品表单的下拉主数据，读接口供三端共用；写接口仅管理后台可用。
 * <p>
 * 未加 Redis 缓存：品牌仅百余条、变更极少，且只在打开商品表单 / 后台管理页时查询，
 * 回源 DB 成本可忽略；写后立即可见也省掉了缓存清理逻辑。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    /** 品牌状态：0=正常 1=停用（全库统一约定，同 user / admin / category） */
    private static final int STATUS_ENABLED = 0;
    private static final int MAX_PAGE_SIZE = 100;

    private final BrandMapper brandMapper;
    private final ProductMapper productMapper;

    @Override
    public List<BrandDO> list() {
        return brandMapper.selectList(new LambdaQueryWrapper<BrandDO>()
                .eq(BrandDO::getStatus, STATUS_ENABLED)
                .orderByAsc(BrandDO::getSort));
    }

    @Override
    public PageResult<BrandDO> adminPage(Integer page, Integer pageSize, String keyword) {
        int current = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<BrandDO> wrapper = new LambdaQueryWrapper<BrandDO>()
                .like(keyword != null && !keyword.isBlank(), BrandDO::getName, keyword)
                .orderByAsc(BrandDO::getSort)
                .orderByDesc(BrandDO::getId);

        return PageResult.of(brandMapper.selectPage(new Page<>(current, size), wrapper));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(BrandDTO dto) {
        String name = dto.getName().trim();
        assertNameAvailable(name, null);

        BrandDO brand = new BrandDO();
        brand.setName(name);
        brand.setLogo(dto.getLogo());
        brand.setDescription(dto.getDescription());
        brand.setSort(dto.getSort() == null ? 0 : dto.getSort());
        brand.setStatus(dto.getStatus() == null ? STATUS_ENABLED : dto.getStatus());
        try {
            brandMapper.insert(brand);
        } catch (DuplicateKeyException e) {
            // 逻辑删除的旧品牌仍占着唯一索引，查重（deleted=0）查不到但插入会冲突
            throw new BusinessException("品牌名称已存在（可能与已删除品牌重名）");
        }

        log.info("品牌新增成功, brandId: {}, name: {}", brand.getId(), name);
        return brand.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BrandDTO dto) {
        BrandDO brand = requireExists(id);
        String name = dto.getName().trim();
        assertNameAvailable(name, id);

        brand.setName(name);
        brand.setLogo(dto.getLogo());
        brand.setDescription(dto.getDescription());
        brand.setSort(dto.getSort() == null ? brand.getSort() : dto.getSort());
        if (dto.getStatus() != null) {
            brand.setStatus(dto.getStatus());
        }
        try {
            brandMapper.updateById(brand);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("品牌名称已存在（可能与已删除品牌重名）");
        }

        log.info("品牌修改成功, brandId: {}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireExists(id);

        long productCount = productMapper.selectCount(new LambdaQueryWrapper<ProductDO>()
                .eq(ProductDO::getBrandId, id));
        if (productCount > 0) {
            throw new BusinessException("该品牌下还有 " + productCount + " 个商品，不能删除");
        }

        brandMapper.deleteById(id);
        log.info("品牌删除成功, brandId: {}", id);
    }

    private BrandDO requireExists(Long id) {
        BrandDO brand = id == null ? null : brandMapper.selectById(id);
        if (brand == null) {
            throw new BusinessException("品牌不存在");
        }
        return brand;
    }

    /**
     * 名称查重（排除自身）
     * <p>
     * 只查未删除记录：正常业务语义下「已删除品牌的名称」应可复用，
     * 但数据库唯一索引不区分逻辑删除，故插入侧仍要兜 DuplicateKeyException。
     */
    private void assertNameAvailable(String name, Long selfId) {
        Long count = brandMapper.selectCount(new LambdaQueryWrapper<BrandDO>()
                .eq(BrandDO::getName, name)
                .ne(selfId != null, BrandDO::getId, selfId));
        if (count > 0) {
            throw new BusinessException("品牌名称已存在");
        }
    }
}
