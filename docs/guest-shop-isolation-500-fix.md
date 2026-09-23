# 游客访问商品列表 500 —— 跨端会话串扰 + 不可变参数集合

> 2026-09-23 · 游客（未登录）浏览用户端商品时接口 500；已登录用户正常

## 1. 现象

- **已登录用户**浏览商品：正常
- **退出登录、以游客身份**浏览商品：`{"code":500,"msg":"系统繁忙，请稍后再试"}`
- 报错位置：`ShopDataIsolationInterceptor` 第 252 行 `parameterMappings().add(...)`
  抛 `UnsupportedOperationException`

## 2. 根因（两层，缺一不可）

### 第一层：cookie 导致三端会话串扰 —— 为什么「游客」会进商家分支

拦截器入口有守卫 `if (!isMerchantSession()) return;`，用户端会话**本不该**进入。
但 Sa-Token 取 token 的顺序是 **body → header → cookie**，且 `application.yml` 未关 cookie：

```yaml
sa-token:
  token-name: token     # ← cookie 名也是 token
  # 没有 is-read-cookie: false，默认 true
```

三端前端**同源**（`localhost`），cookie **按域名共享**，与 localStorage 的 key 隔离无关：

1. 用户在**商家中心**登录 → Sa-Token 种下 `token` cookie（shop token）
2. 前端「退出登录」只清 localStorage 的 `merchant-token`，**cookie 原样残留**
3. 游客访问用户端 → header 无 token → Sa-Token **回落到 cookie** → 读到 shop token
4. `StpShopUtil.INSTANCE.isLogin()` = **true** → 被判定为商家会话 → 隔离拦截器生效

**实测证据**（只用 cookie、不带 header）：

| 请求 | 结果 |
|------|------|
| `GET /api/merchant/stats/dashboard`（商家专属） | **200** —— 确实被当作商家会话 |
| 不带任何凭证 | `code:401 未登录` |

### 第二层：parameterMappings 被 MP 换成不可变集合 —— 为什么是 500

`BoundSql.getParameterMappings()` **不保证可变**。MyBatis-Plus 分页插件写回分页参数时：

```java
// com.baomidou.mybatisplus.core.toolkit.PluginUtils$MPBoundSql
public void parameterMappings(List<ParameterMapping> list) {
    this.boundSql.setValue("parameterMappings", Collections.unmodifiableList(list));
}
```

商品列表走 MP 分页（`selectPage`）→ 分页插件处理后 `parameterMappings` 变为
`Collections$UnmodifiableRandomAccessList` → 第 252 行 `.add()` 抛
`UnsupportedOperationException` → 整个查询 500。

> 值得注意的是：**如果只修第二层不修第一层**，异常会消失，但游客将静默看到
> 「某个商家的商品」——比 500 更危险的**数据污染**。两层都必须修。

## 3. 修复

### 3.1 关闭 Sa-Token 的 cookie 读写（治本）

```yaml
sa-token:
  token-name: token
  is-read-cookie: false
  is-write-cookie: false
```

三端均为 SPA：token 走请求头、存于各端独立 localStorage，**cookie 没有任何用途**，
只会造成跨端串扰（同时也消除了 cookie 带来的 CSRF 面）。

### 3.2 参数注入不再假设集合可变（健壮性）

```java
List<ParameterMapping> mutableMappings = new ArrayList<>(mappings);
mutableMappings.add(mapping);
SystemMetaObject.forObject(boundSql).setValue("parameterMappings", mutableMappings);
```

与同文件改写 `sql` 字段（final）用的是同一手法（`MetaObject.setValue`）。

## 4. 验证

新增 `ShopDataIsolationInterceptorTest`（3 个用例），并**用回退实验证明其有效性**：

| 状态 | 结果 |
|------|------|
| 回退修复（修复前代码） | `Failures: 1, Errors: 1` → `UnsupportedOperationException` |
| 应用修复 | **3/3 通过** |

- 不可变集合 → 正常注入 `_shopId`，不抛异常
- 可变集合 → 保持原行为
- 重复拦截 → 不重复注入参数

编译 ✅ `mvn compile` EXIT=0

## 5. 待人工确认

配置与代码均需**重启后端**生效。重启后游客态验证：

```bash
# 携带商家 cookie（模拟你浏览器里的残留）
curl -s "http://localhost:8080/api/product/page?page=1&pageSize=3" \
  -H "Cookie: token=<任意商家 token>"
# 修复前：{"code":500,...}   修复后：{"code":200,...} 且返回全量商品（不再被 shop_id 过滤）
```

## 6. 复盘

- 前端「退出登录」只清 localStorage、未调后端登出，是 cookie 残留的直接原因。
  关闭 cookie 读取后已无影响，但**建议补上调用登出接口**以清理服务端会话。
- 该缺陷同时是一个**数据安全隐患**：任何端只要浏览器残留了商家 cookie，
  就会被识别为商家会话 —— 修复前表现为 500（侥幸挡住），修复后需确保
  `is-read-cookie: false` 已生效，否则会变成静默的数据越界。
