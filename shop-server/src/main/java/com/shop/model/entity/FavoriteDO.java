package com.shop.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品收藏实体
 * <p>
 * 取消收藏走物理删除（表无 deleted 字段）；(user_id, product_id) 唯一索引保证不重复。
 *
 * @author shop
 */
@Data
@TableName("favorite")
@Schema(description = "商品收藏")
public class FavoriteDO {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "收藏 ID")
    private Long id;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "商品 ID")
    private Long productId;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "收藏时间")
    private LocalDateTime createTime;
}
