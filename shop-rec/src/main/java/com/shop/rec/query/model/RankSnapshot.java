package com.shop.rec.query.model;

import com.shop.rec.rank.model.RankedItem;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 榜单快照
 *
 * <p>
 * 一次重排产生的完整榜单及顺序，缓存于内存库（{@code rec:result:user:{userId}}，默认 300 秒），
 * 供同一用户翻页时复用，保证翻页顺序稳定、不重复曝光（见详细设计 6.5）。
 *
 * <p>
 * 降级结果（兜底名单、热门榜单）同样生成快照，保证降级期间翻页行为仍保持一致顺序。
 *
 * @author shop
 */
@Data
public class RankSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 快照标识，形如 u2635_1758528625_3f9a1c */
    private String snapshotId;

    /** 生成时刻（毫秒时间戳） */
    private long generatedAt;

    /** 降级状态（快照生成时的对外状态，翻页时沿用） */
    private String degraded;

    /** 完整榜单（已含固定坑位），顺序即最终展示顺序 */
    private List<RankedItem> items = Collections.emptyList();
}
