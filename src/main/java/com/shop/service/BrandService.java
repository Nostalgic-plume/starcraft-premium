package com.shop.service;

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
}
