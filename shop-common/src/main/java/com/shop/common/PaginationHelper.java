package com.shop.common;

/**
 * 分页参数标准化辅助类
 * 统一处理 page / pageSize 的 null 值和越界，消除各 Service 中重复的 if 校验
 *
 * @author shop
 */
public class PaginationHelper {

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 最小页码 */
    public static final int MIN_PAGE = 1;

    /** 最小每页条数 */
    public static final int MIN_PAGE_SIZE = 1;

    /**
     * 最大每页条数上限
     * <p>
     * 防止超大 pageSize 引发全表扫描与 N+1 查询风暴：订单/订单项需逐条组装详情，
     * pageSize=999 时单请求耗时可达 15s，且日志被 SQL 刷屏（曾误判为死循环）。
     * 同时堵住 pageSize=999999 这类可被恶意利用的 DoS 面。
     */
    public static final int MAX_PAGE_SIZE = 200;

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
        // 上限收敛：超大 pageSize 一律截断，避免单请求触发海量 N+1 查询
        if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;
        return new int[]{page, pageSize};
    }
}
