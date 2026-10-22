package com.shop.service;

import com.shop.common.PageResult;
import com.shop.model.dto.BrandDTO;
import com.shop.model.entity.BrandDO;

import java.util.List;

/**
 * 品牌服务接口
 *
 * @author shop
 */
public interface BrandService {

    /**
     * 获取所有启用状态的品牌列表（按排序升序）
     *
     * @return 启用品牌列表，按 sort 升序
     */
    List<BrandDO> list();

    /**
     * 管理端品牌分页（含停用品牌，支持名称模糊搜索）
     *
     * @param page     页码（从 1 开始）
     * @param pageSize 每页条数
     * @param keyword  名称关键字（可选）
     * @return 分页结果
     */
    PageResult<BrandDO> adminPage(Integer page, Integer pageSize, String keyword);

    /**
     * 新增品牌
     *
     * @param dto 品牌参数
     * @return 新品牌 ID
     * @throws com.shop.common.BusinessException 名称重复
     */
    Long add(BrandDTO dto);

    /**
     * 修改品牌
     *
     * @param id  品牌 ID
     * @param dto 品牌参数
     * @throws com.shop.common.BusinessException 品牌不存在 / 名称重复
     */
    void update(Long id, BrandDTO dto);

    /**
     * 删除品牌
     *
     * @param id 品牌 ID
     * @throws com.shop.common.BusinessException 品牌不存在 / 有商品引用
     */
    void delete(Long id);
}
