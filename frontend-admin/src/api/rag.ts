import { get, post } from './request'
import type { RagIndexStatus, RagRebuildResult, Result } from './types'

/**
 * RAG 索引管理 API
 *
 * 对照后端 IndexAdminController（`@RequestMapping("/api/admin/rag")`）。
 * 用途：手动触发并观察全量索引重建 —— 例如切换 embedding 模型后向量维度变更，
 * 必须重建集合才会生效（且 `ProductIndexer.rebuildAll()` 的启动钩子见集合有数据即跳过，
 * 重启不会自动重建）。URL 不写 `/api` 前缀，由 createRequest 的 baseURL 自动拼上。
 */
export const ragAdminApi = {
  /** 索引状态：Qdrant 已索引商品数 + 最近重建时间与成败 + FULLTEXT 索引健康检查 */
  status(): Promise<Result<RagIndexStatus>> {
    return get<RagIndexStatus>('/admin/rag/status')
  },
  /**
   * 触发全量重建（后端异步执行、立即返回）
   * 完成信号：轮询 status 的 lastRebuildTime 变化（动作结束）→ 再看 lastRebuildSuccess 判断成败
   */
  rebuild(): Promise<Result<RagRebuildResult>> {
    return post<RagRebuildResult>('/admin/rag/rebuild')
  }
}
