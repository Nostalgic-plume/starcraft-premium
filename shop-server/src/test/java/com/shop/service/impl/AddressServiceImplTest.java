package com.shop.service.impl;

import com.shop.common.BusinessException;
import com.shop.common.MyBatisPlusTestSupport;
import com.shop.common.SaTokenMockUtil;
import com.shop.mapper.AddressMapper;
import com.shop.model.dto.AddressDTO;
import com.shop.model.entity.AddressDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收货地址服务测试（纯 Mockito）
 * <p>
 * 锁定两条安全/一致性约束：
 * <ol>
 *   <li><b>归属隔离</b>：查询 / 修改 / 删除都带 userId，别人的地址一律「不存在」；</li>
 *   <li><b>默认地址唯一</b>：新增默认、显式设默认时先清空其他；首条自动默认。</li>
 * </ol>
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AddressServiceImpl - 归属隔离与默认地址唯一")
class AddressServiceImplTest {

    private static final long USER_ID = 1L;
    private static final long ADDRESS_ID = 100L;

    @Mock
    private AddressMapper addressMapper;

    private AddressServiceImpl addressService;
    private SaTokenMockUtil.SaTokenLoginScope loginScope;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        MyBatisPlusTestSupport.initTableInfo(AddressDO.class);
    }

    @BeforeEach
    void setUp() {
        addressService = new AddressServiceImpl(addressMapper);
        loginScope = SaTokenMockUtil.userLogin(USER_ID);
    }

    @AfterEach
    void tearDown() {
        loginScope.close();
    }

    private static AddressDTO dto(Integer isDefault) {
        AddressDTO dto = new AddressDTO();
        dto.setReceiverName("张三");
        dto.setReceiverPhone("13800138000");
        dto.setProvince("河南省");
        dto.setCity("郑州市");
        dto.setDistrict("金水区");
        dto.setDetail("某某路 1 号");
        dto.setIsDefault(isDefault);
        return dto;
    }

    @Test
    @DisplayName("首个地址自动设为默认")
    void firstAddress_becomesDefault() {
        when(addressMapper.selectCount(any())).thenReturn(0L);
        when(addressMapper.insert(any(AddressDO.class))).thenAnswer(inv -> {
            AddressDO address = inv.getArgument(0);
            address.setId(ADDRESS_ID);
            return 1;
        });

        addressService.add(dto(0));

        ArgumentCaptor<AddressDO> captor = ArgumentCaptor.forClass(AddressDO.class);
        verify(addressMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getIsDefault(), "首条地址应自动设为默认");
        assertEquals(USER_ID, captor.getValue().getUserId(), "userId 必须来自登录会话");
        // 首条时没有其他地址可清，clearDefault 的 update 仍会执行一次（条件更新影响 0 行）
        verify(addressMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("已有地址时新增非默认地址：保持默认不变")
    void nonDefaultWhenOthersExist_keepsOriginalDefault() {
        when(addressMapper.selectCount(any())).thenReturn(1L);
        when(addressMapper.insert(any(AddressDO.class))).thenAnswer(inv -> {
            ((AddressDO) inv.getArgument(0)).setId(ADDRESS_ID);
            return 1;
        });

        addressService.add(dto(0));

        ArgumentCaptor<AddressDO> captor = ArgumentCaptor.forClass(AddressDO.class);
        verify(addressMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getIsDefault());
        verify(addressMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("新增默认地址：先清空该用户其他默认标记")
    void addDefault_clearsOtherDefaults() {
        when(addressMapper.selectCount(any())).thenReturn(2L);
        when(addressMapper.insert(any(AddressDO.class))).thenAnswer(inv -> {
            ((AddressDO) inv.getArgument(0)).setId(ADDRESS_ID);
            return 1;
        });

        addressService.add(dto(1));

        verify(addressMapper).update(isNull(), any());
        ArgumentCaptor<AddressDO> captor = ArgumentCaptor.forClass(AddressDO.class);
        verify(addressMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getIsDefault());
    }

    @Test
    @DisplayName("查询他人地址：按归属条件查不到，抛「地址不存在」")
    void getForeignAddress_throws() {
        // 归属条件由 SQL 保证：换成别人的地址时 selectOne 返回 null
        when(addressMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> addressService.getById(ADDRESS_ID));
        assertEquals("地址不存在", ex.getMessage());
    }

    @Test
    @DisplayName("删除默认地址：自动把最近一条顶为默认")
    void deleteDefault_promotesFallback() {
        AddressDO current = new AddressDO();
        current.setId(ADDRESS_ID);
        current.setUserId(USER_ID);
        current.setIsDefault(1);

        AddressDO fallback = new AddressDO();
        fallback.setId(200L);
        fallback.setUserId(USER_ID);
        fallback.setIsDefault(0);

        when(addressMapper.selectOne(any())).thenReturn(current, fallback);
        when(addressMapper.deleteById(ADDRESS_ID)).thenReturn(1);

        addressService.delete(ADDRESS_ID);

        verify(addressMapper).deleteById(ADDRESS_ID);
        ArgumentCaptor<AddressDO> captor = ArgumentCaptor.forClass(AddressDO.class);
        verify(addressMapper).updateById(captor.capture());
        assertEquals(200L, captor.getValue().getId());
        assertEquals(1, captor.getValue().getIsDefault(), "补位地址应设为默认");
    }

    @Test
    @DisplayName("切换默认地址：先清空其他，再置当前为默认")
    void setDefault_clearsThenSets() {
        AddressDO target = new AddressDO();
        target.setId(ADDRESS_ID);
        target.setUserId(USER_ID);
        when(addressMapper.selectOne(any())).thenReturn(target);
        when(addressMapper.update(isNull(), any())).thenReturn(1);

        addressService.setDefault(ADDRESS_ID);

        // 一次 clearDefault + 一次按 id 置默认
        verify(addressMapper, times(2)).update(isNull(), any());
    }
}
