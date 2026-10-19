# 商品图片生成 — 设计文档

> 创建日期：2026-10-14
> 关联计划：`docs/product-image-plan.md`

## 一、背景与目标

商城三端的商品图片全部无法显示：mock 数据生成时写入的是**占位 URL**而非真实文件。本任务为 967 个商品、4000 个 SKU 生成本地图片文件并回填数据库，使前端演示不出现裂图。

## 二、现状盘点（实测数据）

| 字段 | 现状 | 影响 |
|------|------|------|
| `product.main_image` | `https://cdn.example.com/product/{1..5}.jpg` | `example.com` 为保留域名，图片不存在；且 967 个商品仅复用 5 个 URL |
| `product.images`（轮播） | `["https://picsum.photos/seed/N/400/400", ...]` | 国外图床，国内访问超时；内容是随机风景照，与商品无关 |
| `sku.image` | `https://cdn.example.com/sku/...` | 同上，假域名 |
| `order_item.product_main_image` | 90000 条快照，仅 5 种坏 URL | 历史订单页裂图 |

`product.detail` 为纯文本 HTML（**不含** `<img>` 标签），详情页富文本无需处理。

## 三、方案选型

**已定：本地程序生成 SVG 图片 + 落盘 `uploads/` + 回填库。**

选型理由（对比另两条路）：

1. **AI 文生图**：967 张按张计费约需数百元 + 数小时批处理，对求职演示项目性价比最低。
2. **免费图库抓取真实照片**：真实性最好，但需外部 API key、通用素材与具体型号对不上、下载依赖网络。
3. **本地生成（选定）**：零成本、秒级、完全离线、永不裂图。

**为什么用 SVG 而非 PNG**：SVG 是纯文本拼接，无需绘图库、无需字体渲染引擎；体积约 2KB（PNG 约 100KB）；7868 张生成耗时秒级（PNG 转码需数分钟）；矢量放大不糊。

**为什么不印品牌名**：品牌关联数据与商品名随机错配（"优衣库"那件外套关联的品牌是"星河数码"），印在图上等于把数据错误摆到明处。改用**从商品名推断的主题标签**（数码 / APPAREL），该推断一定与商品名自洽。

## 四、硬约束

1. **图片必须落盘到宿主 `./uploads/` 下，URL 以 `/uploads` 开头**
   —— 复用现有的 Nginx `location /uploads/` alias（`docker/nginx/nginx.conf:114`）与 compose 只读挂载（`docker-compose.yml:295`），不新增任何基础设施。
2. **`first_category_id` 全为 0，不可用于配色**
   —— 967 条该列全为 0（历史 ALTER 未回填）。配色改用商品名关键词推断。
3. **分类字段同样错配**（"松下剃须刀"挂在"电视"分类下），**配色不得依赖 `category_id`**。
4. **`images` 字段必须是 JSON 数组字符串**，与后端 `ProductServiceImpl` 写入格式一致，否则详情页轮播解析失败。
5. URL 长度须 < 500（`main_image` / `sku.image` 均为 `varchar(500)`）；实际 URL 约 40 字符。
6. **不改表结构、不改后端/前端代码** —— 本任务只产出文件 + 数据。
7. `.gitignore:48` 已含 `uploads/`，产物不入库。
8. **文件名不得含中文** —— 一律用商品 ID / SKU ID 命名，避免 URL 编码与 Nginx 处理问题。

## 五、产出物与命名规则

```
uploads/mock/product/{productId}-0.svg   主图 / 轮播第 1 张        1000 个
uploads/mock/product/{productId}-1.svg   轮播第 2 张              2000 个
uploads/mock/product/{productId}-2.svg   轮播第 3 张
uploads/mock/sku/{skuId}.svg             SKU 图                   4000 个
```

合计 **7000 个文件**，实测约 29 MB。

**为何覆盖全部 1000 个商品（含 33 个已逻辑删除的）**：初版实现只处理 `deleted=0` 的 967 个商品，结果 `order_item` 快照有 **2833 条仍是坏 URL** —— 这些订单项属于已删除商品，而**历史订单详情页显示的是快照图**，漏掉它们老订单就会裂图。故生成器刻意**不过滤 `deleted`**，`product` 与 `sku` 全表覆盖。

> 实测教训：`UPDATE order_item oi JOIN product p ON p.id = oi.product_id SET oi.product_main_image = p.main_image` 的影响行数是 **0** 而非 2833 —— 因为 JOIN 到的已删商品 `main_image` 仍是旧值，SET 后值未变，MySQL 按「实际更改行数」计数。这正好暴露了漏处理的记录。

**每商品 3 张而非 4 张**：轮播数组第一张直接复用主图（真实电商的惯例，列表页所见即详情页轮播首图），避免多生成 967 个文件。

入库 URL 示例：

| 表.字段 | 值 |
|---------|-----|
| `product.main_image` | `/uploads/mock/product/1-0.svg` |
| `product.images` | `["/uploads/mock/product/1-0.svg","/uploads/mock/product/1-1.svg","/uploads/mock/product/1-2.svg"]` |
| `sku.image` | `/uploads/mock/sku/1.svg` |
| `order_item.product_main_image` | `/uploads/mock/product/1-0.svg`（按 `product_id` 回填） |

## 六、图片设计（风格 B：全幅大字）

800×800 画布，构成自上而下：

1. 主题色线性渐变背景（主色 → 深色）
2. 大半径径向"光晕"（白色 16% → 0%），中心位置由商品 ID 派生，使同主题不同商品有差异
3. 深色斜切装饰块（顶部 + 底部）
4. **商品名**：白色粗体大字，居中，自动折行 + 字号自适应（64~78 起试，下限 40）
5. 珊瑚红短线（品牌强调色 `#E94560`）
6. 主题标签：`数码  DIGITAL`
7. 右下角"星创优品"水印（白色 40%）

**主题配色表**（15 个主题，按商品名关键词匹配，先命中先返回）：数码 / 电脑 / 影音 / 穿戴 / 家电 / 运动 / 服饰 / 鞋包 / 美妆 / 食品 / 家居 / 图书 / 医药 / 汽车 / 优选（兜底）。

**轮播 3 张的变体**：文字完全相同，仅背景变化（渐变方向、光晕位置、斜切形状各取 3 种预设），使轮播切换有层次而不失真。变体 `0` 即主图。

**SKU 图**：继承所属商品的主题色；显示商品名（小字）+ **规格大号标签**。规格取自 `sku.specs` 的 JSON 值（如 `{"memory":"16GB","storage":"256GB"}` → `16GB · 256GB`），**不使用 `sku.name`** —— 后者是"商品名 + 随机规格"拼接的乱数据（实测 `华为 Mate 60 Pro 12GB+512GB 雅川青 16GB-256GB`，自相矛盾）。

## 七、入参与运行方式

生成器 `com.shop.mocker.ProductImageGenerator`（置于 `shop-data-mocker`，与既有 mock 工具同源）。

| 参数 | 说明 |
|------|------|
| 无参数 | 全量：生成文件 → 校验 → 回填 |
| `--limit=N` | 只处理前 N 个商品（试跑用），生成的图片对应 N×4 + 其 SKU 数 |
| `--files-only` | 只生成文件，不回填 DB（用于先验收视觉） |
| `--db-only` | 只回填 DB（文件已存在时跳过生成） |

连接信息：**优先系统环境变量** `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`，缺失时回落到读取 `D:/claudecode/shop/.env`。**不设明文默认密码**（既有 `DataGenerator.java:34` 硬编码了明文 `DB_PASS`，是反面教材，新代码不沿用）。

## 八、错误处理

| 场景 | 行为 |
|------|------|
| DB 连接失败 | 打印明确原因（含 URL + 用户名，**不含密码**）后 `System.exit(1)` |
| 图片写盘失败 | 抛异常立即中止，**不允许带着部分文件去回填 DB** |
| 某个商品名为空 | 跳过该商品并计数告警，不中断全量流程 |
| DB 回填失败 | 可安全重跑（URL 由 ID 确定性推导，重跑结果幂等） |

**执行顺序强制**：先全部落文件 → 校验文件数达标 → 再回填 DB。反过来会导致前端拿到 URL 时文件尚不存在。

## 九、容易踩的坑

1. **Windows 路径分隔符**：写文件用 `Path`，但**存库的 URL 必须用 `/`**（`Path.toString()` 在 Windows 返回 `\`，直接入库会导致前端 404）。
2. **XML 转义**：商品名含 `&` `<` `>` 必须转义，否则 SVG 解析失败、图片打不开。
3. **长商品名**：实测最长 35 字符（`Apple iPhone 15 Pro Max 256GB 原色钛金属`），需按字符宽度折行（CJK 按 1.0em、西文按 0.55em 估算），超 3 行截断加省略号。
4. **订单快照 90000 条不要逐条 UPDATE**：用一条 `UPDATE order_item oi JOIN product p ON p.id = oi.product_id SET oi.product_main_image = p.main_image` 完成。
5. **Nginx 只读挂载不影响可读性**：`./uploads:/usr/share/nginx/html/uploads:ro` 的 `ro` 是**容器内视角**，宿主机照常写入，容器立即可读，无需重启 Nginx。
6. **Nginx 需返回 `image/svg+xml`**：标准 `mime.types` 已含 `svg`，但仍需实测确认。
7. **幂等性**：重复运行覆盖同名文件、写入相同 URL，不产生脏数据。
8. **控制台中文乱码**：Windows GBK 控制台下 `System.out` 中文显示为乱码，**不影响文件内容**（文件一律显式 `StandardCharsets.UTF_8`）。

## 十、验收标准

| # | 验收项 | 方式 |
|---|--------|------|
| 1 | 文件数 = 7000 | 目录文件计数（product 3000 + sku 4000） |
| 2 | Nginx 可访问且 MIME 正确 | `curl -I http://localhost/uploads/mock/product/1-0.svg` 返回 200 + `image/svg+xml` |
| 3 | 主图回填完整 | `SELECT COUNT(*) FROM product WHERE main_image LIKE '/uploads/%'` = 1000 |
| 4 | 轮播回填完整且为合法 JSON | 抽查 `images` 能否被 JSON 解析 |
| 5 | SKU 图回填完整 | `SELECT COUNT(*) FROM sku WHERE image LIKE '/uploads/%'` = 4000 |
| 6 | 订单快照回填完整 | `SELECT COUNT(*) FROM order_item WHERE product_main_image LIKE '/uploads%'` = 90000 |
| 7 | 前端无裂图 | 浏览器打开用户端列表页 + 详情页截图确认 |
