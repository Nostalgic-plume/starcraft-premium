package com.shop.rec.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 偏好项视图对象
 *
 * <p>
 * 用于用户画像中「类目偏好」「品牌偏好」列表的元素结构，
 * 由 M-03 生成并持久化到 t_user_profile 的 json 字段。
 * 同一个结构同时承载类目维度与品牌维度，以 dimId 泛化字段名。
 *
 * @author shop
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "偏好项：维度标识 + 偏好分")
public class PrefItemVO implements Serializable {

    @Schema(description = "维度标识：类目标识或品牌标识")
    private Long dimId;

    @Schema(description = "偏好分")
    private BigDecimal score;
}
