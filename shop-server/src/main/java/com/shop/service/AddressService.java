package com.shop.service;

import com.shop.model.dto.AddressDTO;
import com.shop.model.entity.AddressDO;

import java.util.List;

/**
 * 收货地址服务
 * <p>
 * 所有方法都以当前登录用户为边界：userId 由服务端从会话注入，
 * 查询 / 修改 / 删除一律带归属校验，不做「按 id 直查直改」。
 *
 * @author shop
 */
public interface AddressService {

    /**
     * 当前用户的地址列表（默认地址置顶，其余按更新时间倒序）
     *
     * @return 地址列表
     */
    List<AddressDO> list();

    /**
     * 查询当前用户的一条地址
     *
     * @param id 地址 ID
     * @return 地址；不存在或不属于当前用户时抛出业务异常
     */
    AddressDO getById(Long id);

    /**
     * 新增地址
     *
     * @param dto 地址参数
     * @return 新地址 ID
     */
    Long add(AddressDTO dto);

    /**
     * 修改地址
     *
     * @param id  地址 ID
     * @param dto 地址参数
     */
    void update(Long id, AddressDTO dto);

    /**
     * 删除地址（逻辑删除）
     *
     * @param id 地址 ID
     */
    void delete(Long id);

    /**
     * 设为默认地址（同一用户下默认地址唯一）
     *
     * @param id 地址 ID
     */
    void setDefault(Long id);
}
