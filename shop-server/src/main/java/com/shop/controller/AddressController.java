package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.shop.common.Result;
import com.shop.model.dto.AddressDTO;
import com.shop.model.entity.AddressDO;
import com.shop.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 收货地址接口（用户端）
 * <p>
 * 全部接口只操作当前登录用户的地址簿：userId 由服务端从会话注入，请求体不接收。
 *
 * @author shop
 */
@Tag(name = "收货地址模块")
@RestController
@RequestMapping("/api/address")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @SaCheckLogin(type = "user")
    @GetMapping("/list")
    @Operation(summary = "当前用户的地址列表（默认地址置顶）")
    public Result<List<AddressDO>> list() {
        return Result.success(addressService.list());
    }

    @SaCheckLogin(type = "user")
    @GetMapping("/{id}")
    @Operation(summary = "地址详情")
    public Result<AddressDO> detail(@PathVariable Long id) {
        return Result.success(addressService.getById(id));
    }

    @SaCheckLogin(type = "user")
    @PostMapping
    @Operation(summary = "新增地址（首条自动设为默认）")
    public Result<Long> add(@Valid @RequestBody AddressDTO dto) {
        return Result.success("新增成功", addressService.add(dto));
    }

    @SaCheckLogin(type = "user")
    @PutMapping("/{id}")
    @Operation(summary = "修改地址")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AddressDTO dto) {
        addressService.update(id, dto);
        return Result.success("修改成功", null);
    }

    @SaCheckLogin(type = "user")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除地址")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(id);
        return Result.success("删除成功", null);
    }

    @SaCheckLogin(type = "user")
    @PutMapping("/{id}/default")
    @Operation(summary = "设为默认地址")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(id);
        return Result.success("已设为默认地址", null);
    }
}
