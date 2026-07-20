package com.shop.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页返回结果
 * 所有分页接口统一使用此包装类，包含分页元数据和当前页数据列表
 * 配合 MyBatis-Plus IPage 使用，通过 IPage 初始化
 *
 * @param <T> 列表数据类型
 */
@Data
@Schema(description = "分页返回结果")
public class PageResult<T> implements Serializable {

    @Schema(description = "当前页码", example = "1")
    private Integer page;

    @Schema(description = "每页条数", example = "10")
    private Integer pageSize;

    @Schema(description = "总记录数", example = "100")
    private Long total;

    @Schema(description = "总页数", example = "10")
    private Integer pages;

    @Schema(description = "数据列表")
    private List<T> list;

    /**
     * 从 MyBatis-Plus 分页查询记录直接创建分页结果
     *
     * @param iPage MyBatis-Plus 分页查询结果
     * @param <T>   数据类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(IPage<T> iPage) {
        PageResult<T> result = new PageResult<>();
        result.setPage((int) iPage.getCurrent());
        result.setPageSize((int) iPage.getSize());
        result.setTotal(iPage.getTotal());
        result.setPages((int) iPage.getPages());
        result.setList(iPage.getRecords());
        return result;
    }

    /**
     * 从原始分页查询结果和转换后的列表创建分页结果
     * 分页查询后对 records 做 stream/map 转换时需要保留分页元数据
     *
     * @param iPage         原始分页查询结果（用于取出分页元数据）
     * @param convertedList 转换后的数据列表
     * @param <T>           原始数据类型
     * @param <R>           转换后数据类型
     * @return 分页结果
     */
    public static <T, R> PageResult<R> of(IPage<T> iPage, List<R> convertedList) {
        PageResult<R> result = new PageResult<>();
        result.setPage((int) iPage.getCurrent());
        result.setPageSize((int) iPage.getSize());
        result.setTotal(iPage.getTotal());
        result.setPages((int) iPage.getPages());
        result.setList(convertedList);
        return result;
    }
}
