package com.shop.rec.mall.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 商城类目只读视图
 *
 * <p>
 * 用于建立一级 / 二级类目关系：parent_id 为 0 表示一级类目，非 0 表示二级类目。
 * 推荐的类目偏好以二级类目为聚合维度。
 *
 * @author shop
 */
@Data
public class MallCategoryDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 类目标识 */
    private Long id;

    /** 类目名称 */
    private String name;

    /** 父类目标识：0 表示一级类目 */
    private Long parentId;
}
