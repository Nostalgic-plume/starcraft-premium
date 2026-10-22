package com.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.common.BusinessException;
import com.shop.common.StpUserUtil;
import com.shop.mapper.AddressMapper;
import com.shop.model.dto.AddressDTO;
import com.shop.model.entity.AddressDO;
import com.shop.service.AddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址服务实现
 * <p>
 * 有两条必须守住的约束：
 * <ol>
 *   <li><b>归属隔离</b>：所有读写都带 {@code user_id = 当前登录用户}，否则改一个 id 就能操作别人的地址；</li>
 *   <li><b>默认地址唯一</b>：新增 / 修改 / 显式设默认时，先把该用户其余地址的 is_default 清零。</li>
 * </ol>
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    /** 默认地址标记 */
    private static final int DEFAULT_YES = 1;
    private static final int DEFAULT_NO = 0;

    private final AddressMapper addressMapper;

    @Override
    public List<AddressDO> list() {
        long userId = currentUserId();
        return addressMapper.selectList(new LambdaQueryWrapper<AddressDO>()
                .eq(AddressDO::getUserId, userId)
                .orderByDesc(AddressDO::getIsDefault)
                .orderByDesc(AddressDO::getUpdateTime)
                .orderByDesc(AddressDO::getId));
    }

    @Override
    public AddressDO getById(Long id) {
        return requireOwned(id, currentUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(AddressDTO dto) {
        long userId = currentUserId();
        AddressDO address = new AddressDO();
        copy(dto, address);
        address.setUserId(userId);

        boolean makeDefault = isDefault(dto.getIsDefault());
        // 该用户的第一条地址自动设为默认：否则用户新增后没有默认地址，下单页无处可回填
        boolean hasAny = addressMapper.selectCount(new LambdaQueryWrapper<AddressDO>()
                .eq(AddressDO::getUserId, userId)) > 0;
        if (makeDefault || !hasAny) {
            clearDefault(userId);
            address.setIsDefault(DEFAULT_YES);
        } else {
            address.setIsDefault(DEFAULT_NO);
        }

        addressMapper.insert(address);
        log.info("地址新增成功, userId: {}, addressId: {}", userId, address.getId());
        return address.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AddressDTO dto) {
        long userId = currentUserId();
        AddressDO address = requireOwned(id, userId);
        copy(dto, address);

        if (isDefault(dto.getIsDefault())) {
            clearDefault(userId);
            address.setIsDefault(DEFAULT_YES);
        }
        addressMapper.updateById(address);
        log.info("地址修改成功, userId: {}, addressId: {}", userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        long userId = currentUserId();
        AddressDO address = requireOwned(id, userId);
        addressMapper.deleteById(id);

        // 删掉的是默认地址时，把最近修改的一条顶上来，避免用户地址簿长期没有默认项
        if (address.getIsDefault() != null && address.getIsDefault() == DEFAULT_YES) {
            AddressDO fallback = addressMapper.selectOne(new LambdaQueryWrapper<AddressDO>()
                    .eq(AddressDO::getUserId, userId)
                    .orderByDesc(AddressDO::getUpdateTime)
                    .last("LIMIT 1"));
            if (fallback != null) {
                fallback.setIsDefault(DEFAULT_YES);
                addressMapper.updateById(fallback);
            }
        }
        log.info("地址删除成功, userId: {}, addressId: {}", userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long id) {
        long userId = currentUserId();
        requireOwned(id, userId);

        clearDefault(userId);
        addressMapper.update(null, new LambdaUpdateWrapper<AddressDO>()
                .eq(AddressDO::getId, id)
                .eq(AddressDO::getUserId, userId)
                .set(AddressDO::getIsDefault, DEFAULT_YES));
        log.info("默认地址已切换, userId: {}, addressId: {}", userId, id);
    }

    /** 当前登录用户 ID（未登录时 Sa-Token 会直接抛未授权异常） */
    private long currentUserId() {
        return StpUserUtil.INSTANCE.getLoginIdAsLong();
    }

    /**
     * 按 ID + 归属查地址，查不到统一报「地址不存在」
     * <p>
     * 不区分「不存在」与「不属于你」：区分会泄露他人地址 ID 是否存在。
     */
    private AddressDO requireOwned(Long id, long userId) {
        if (id == null) {
            throw new BusinessException("地址不存在");
        }
        AddressDO address = addressMapper.selectOne(new LambdaQueryWrapper<AddressDO>()
                .eq(AddressDO::getId, id)
                .eq(AddressDO::getUserId, userId));
        if (address == null) {
            throw new BusinessException("地址不存在");
        }
        return address;
    }

    /** 清空该用户的默认标记（默认地址唯一性的前置动作） */
    private void clearDefault(long userId) {
        addressMapper.update(null, new LambdaUpdateWrapper<AddressDO>()
                .eq(AddressDO::getUserId, userId)
                .eq(AddressDO::getIsDefault, DEFAULT_YES)
                .set(AddressDO::getIsDefault, DEFAULT_NO));
    }

    private static boolean isDefault(Integer value) {
        return value != null && value == DEFAULT_YES;
    }

    private static void copy(AddressDTO dto, AddressDO target) {
        target.setReceiverName(dto.getReceiverName());
        target.setReceiverPhone(dto.getReceiverPhone());
        target.setProvince(dto.getProvince());
        target.setCity(dto.getCity());
        target.setDistrict(dto.getDistrict());
        target.setDetail(dto.getDetail());
    }
}
