package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收货地址新增 / 修改参数
 * <p>
 * userId 不从请求体接收：由服务端从登录会话注入，避免越权写入他人地址簿。
 *
 * @author shop
 */
@Data
@Schema(description = "收货地址参数")
public class AddressDTO {

    @NotBlank(message = "收货人不能为空")
    @Size(max = 50, message = "收货人不能超过 50 个字符")
    @Schema(description = "收货人", requiredMode = Schema.RequiredMode.REQUIRED)
    private String receiverName;

    @NotBlank(message = "收货电话不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "收货电话格式不正确")
    @Schema(description = "收货电话（11 位手机号）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String receiverPhone;

    @NotBlank(message = "省份不能为空")
    @Size(max = 50, message = "省份不能超过 50 个字符")
    @Schema(description = "省", requiredMode = Schema.RequiredMode.REQUIRED)
    private String province;

    @NotBlank(message = "城市不能为空")
    @Size(max = 50, message = "城市不能超过 50 个字符")
    @Schema(description = "市", requiredMode = Schema.RequiredMode.REQUIRED)
    private String city;

    @NotBlank(message = "区县不能为空")
    @Size(max = 50, message = "区县不能超过 50 个字符")
    @Schema(description = "区/县", requiredMode = Schema.RequiredMode.REQUIRED)
    private String district;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 200, message = "详细地址不能超过 200 个字符")
    @Schema(description = "详细地址", requiredMode = Schema.RequiredMode.REQUIRED)
    private String detail;

    @Schema(description = "是否默认：0否 1是，默认 0")
    private Integer isDefault;
}
