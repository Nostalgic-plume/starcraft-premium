package com.shop.rec.controller;

import com.shop.common.Result;
import com.shop.rec.common.RecRequestContext;
import com.shop.rec.query.RankQueryService;
import com.shop.rec.query.model.RankQuery;
import com.shop.rec.query.model.RankVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 榜单查询接口（外部接口 E-02，由 M-08 承载）
 *
 * <p>
 * 系统对外唯一出口。内部调用凭证由 {@code InternalTokenFilter} 校验（错误码 1009），
 * 用户身份由商城后端从登录态解析后经 {@code X-User-Id} 透传，本接口不接收用户标识参数，
 * 从根上避免越权查询他人推荐结果。
 *
 * <p>
 * <b>降级约定</b>：降级结果与正常结果的返回结构完全一致，HTTP 状态均为成功，
 * 仅 {@code data.degraded} 字段不同——前端无需分支处理，也不会因降级进入错误分支。
 *
 * <p>
 * <b>首屏与翻页</b>：首屏与下拉刷新不传 {@code snapshotId}（服务端按当前实时特征重算），
 * 仅第 2 页起翻页时回传首屏返回的 {@code snapshotId}（保证翻页顺序稳定）。
 * 若首屏也复用旧快照，「行为后 5 秒内影响推荐」将在快照有效期内失效。
 *
 * @author shop
 */
@Tag(name = "推荐-榜单查询")
@RestController
@RequestMapping("/api/rec/home")
@Profile("rec")
@RequiredArgsConstructor
public class RecRankController {

    private final RankQueryService rankQueryService;

    /**
     * 查询首页个性化榜单
     *
     * @param snapshotId 快照标识，仅翻页时回传；首屏与下拉刷新不传
     * @param pageNum    页码，从 1 开始，缺省 1
     * @param pageSize   每页条数，1~50，缺省 20
     * @return 榜单结果（含快照标识与降级状态）
     */
    @GetMapping("/rank")
    @Operation(summary = "查询首页个性化榜单")
    public Result<RankVO> rank(@RequestParam(required = false) String snapshotId,
                               @RequestParam(required = false) Integer pageNum,
                               @RequestParam(required = false) Integer pageSize) {
        RankQuery query = new RankQuery();
        query.setSnapshotId(snapshotId);
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        // 用户标识只能来自请求上下文（过滤器由商城后端透传写入），为 null 即游客
        return Result.success(rankQueryService.query(RecRequestContext.getUserId(), query));
    }
}
