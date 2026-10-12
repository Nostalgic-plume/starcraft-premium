package com.shop.rec.query.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 榜单查询响应（外部接口 E-02）
 *
 * <p>
 * 降级结果与正常结果的返回结构完全一致，仅 {@code degraded} 字段不同——前端无需分支处理，
 * 也不会因降级进入错误分支（见详细设计 3.2 分层约定）。
 *
 * @author shop
 */
@Data
@Schema(description = "榜单查询结果")
public class RankVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "榜单快照标识，翻页时原值回传")
    private String snapshotId;

    @Schema(description = "降级状态：正常 / 游客 / 冷启动 / 特征缺失 / 召回降级 / 兜底名单 / 上游兜底")
    private String degraded;

    @Schema(description = "当前页码")
    private int pageNum;

    @Schema(description = "每页条数")
    private int pageSize;

    @Schema(description = "快照内商品总数")
    private int total;

    @Schema(description = "是否还有下一页")
    private boolean hasMore;

    @Schema(description = "商品列表，任何情况下均非空（除非全站无可售商品）")
    private List<RankItemVO> items = Collections.emptyList();
}
