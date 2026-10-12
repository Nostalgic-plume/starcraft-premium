package com.shop.rec.query.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 榜单查询入参（外部接口 E-02）
 *
 * <p>
 * <b>首屏与翻页的语义边界</b>：首屏加载与下拉刷新<b>不传 snapshotId</b>，由服务端按当前实时特征
 * 重新计算；只有同一批结果的翻页（第 2 页起）才回传首屏返回的 snapshotId。
 * 这是「行为产生后 5 秒内影响推荐」能够生效的前提——若前端把上一次的快照标识缓存复用，
 * 实时性将在快照有效期（默认 300 秒）内完全失效（见详细设计 5.1.2、6.5、9.3）。
 *
 * @author shop
 */
@Data
@Schema(description = "榜单查询入参")
public class RankQuery {

    /** 页码，从 1 开始 */
    public static final int DEFAULT_PAGE_NUM = 1;

    /** 每页条数缺省值 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数上限 */
    public static final int MAX_PAGE_SIZE = 50;

    @Schema(description = "快照标识：仅翻页时回传；首屏与下拉刷新不传")
    private String snapshotId;

    @Schema(description = "页码，从 1 开始，缺省 1", example = "1")
    private Integer pageNum;

    @Schema(description = "每页条数，1~50，缺省 20", example = "20")
    private Integer pageSize;

    /**
     * 取规范化后的页码
     *
     * @return 页码，最小为 1
     */
    public int normalizePageNum() {
        return pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
    }

    /**
     * 取规范化后的每页条数
     *
     * @return 每页条数，限制在 1~{@value #MAX_PAGE_SIZE}
     */
    public int normalizePageSize() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
