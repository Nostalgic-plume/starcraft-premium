import request from './request'

export const productApi = {
  page(params) { return request.get('/product/page', { params }) },
  detail(id) { return request.get('/product/detail/' + id) },
  add(data) { return request.post('/product/add', data) },
  updateStatus(id, status) { return request.put('/product/status/' + id, null, { params: { status } }) },
  /** 语义搜索（RAG 向量检索） */
  ragSearch(params) { return request.get('/product/rag-search', { params }) }
}
