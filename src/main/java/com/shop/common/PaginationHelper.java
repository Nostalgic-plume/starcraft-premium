package com.shop.common;

/**
 * 分页参数标准化辅助类
 * 统一处理 page / pageSize 的 null 值和越界，消除各 Service 中重复的 if 校验
 */
public class PaginationHelper {

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 最小页码 */
    public static final int MIN_PAGE = 1;

    /** 最小每页条数 */
    public static final int MIN_PAGE_SIZE = 1;

    /**
     * 标准化分页参数，非法值回退为默认值
     *
     * @param page     nullable 页码
     * @param pageSize nullable 每页条数
     * @return int[0]=page, int[1]=pageSize
     */
    public static int[] normalize(Integer page, Integer pageSize) {
        if (page == null || page < MIN_PAGE) page = MIN_PAGE;
        if (pageSize == null || pageSize < MIN_PAGE_SIZE) pageSize = DEFAULT_PAGE_SIZE;
        return new int[]{page, pageSize};
    }
}
