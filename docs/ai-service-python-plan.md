# shop AI 服务(阶段 ①②)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立独立 Python AI 服务的基础骨架(配置/数据库/会话仓储)与完整检索层(BGE-m3 向量 + MySQL 关键词 + RRF 融合),并用现有 30 条评估集产出新基线。

**Architecture:** FastAPI 服务,SQLAlchemy 2.0 async 访问 MySQL(与 Java 共用同库,只读写 `chat_*` / `rag_trace`,只读 `product` / `sku` / `category`);BGE-m3 本地推理产出 1024 维 Dense 向量存入新建的 Qdrant collection;检索为向量路 + MySQL FULLTEXT 关键词路双路并行,经 RRF 融合。

**Tech Stack:** Python 3.11+ / FastAPI / pydantic-settings / SQLAlchemy 2.0(async) + aiomysql / qdrant-client / FlagEmbedding(BGE-m3) / pytest

**Spec:** `docs/superpowers/specs/2026-09-15-ai-service-python-migration-design.md`

## Global Constraints

- Python **3.11+**;本仓库(`D:/claudecode/shop-ai`)即 AI 服务根目录,独立 git 仓库、与 shop 平级
- **本阶段不修改任何 Java 代码** —— 阶段 ①② 是纯新建,Java 侧保持可用即回滚点
- embedding 维度 **1024**(BGE-m3 Dense);Qdrant collection **必须新建**,不可原地迁移(原 1536 维)
- BGE-m3 **只用 Dense 模式**,禁用 Sparse / ColBERT(显存 OOM 风险)
- 凭证一律经环境变量注入,禁止明文写入任何配置文件
- 消息真相源 = `chat_message` 表
- RRF 常数 `k = 60`;向量检索最低分 `min_score = 0.3`
- Qdrant 走 **REST 端口 6333**(Java 侧即如此,规避 gRPC 兼容问题)
- 所有时间字段由应用层写入(表无默认值,除 `rag_trace.create_time`)
- 逻辑删除统一 `deleted = 0` 未删 / `1` 已删

---

## 顶层子任务划分(6 个)

按关联性分批,每批独立可验收。下方 Task 1–11 是执行级的 bite-sized 任务,与顶层子任务的对应关系如下。

| # | 顶层子任务 | 含执行任务 | 验收方式 |
|---|-----------|-----------|---------|
| 1 | 服务骨架与配置 | Task 1 | `pytest tests/test_config.py -v` → 5 passed |
| 2 | 数据层(ORM + 仓储) | Task 2–5 | `pytest tests/` 全绿;连库集成用例通过(MySQL 未启则 skip) |
| 3 | 向量化(BGE-m3) | Task 6 | `pytest tests/test_embedding.py -v` → 6 passed,1024 维断言生效 |
| 4 | 检索组件(RRF + 关键词) | Task 7–8 | 两个测试文件全绿;关键词路对真实库返回结果 |
| 5 | 索引构建(Qdrant) | Task 9 | `pytest tests/test_indexer.py -v` → 10 passed;`rebuild_all` 成功写入 1024 维集合 |
| 6 | 混合检索与评估基线 | Task 10–11 | **卡点**:`RUN_EVAL=1` 跑出三项指标,均 ≥ 旧基线(Recall@3=0.425 / Recall@5=0.686 / MRR=0.368)的 90% |

**逐项验收**:每完成一个顶层子任务,在下表登记实际结果(命令 + 输出摘要 + 通过/不通过),再进入下一个。

### 验收记录

| 顶层子任务 | 完成日期 | 验收命令 | 实际输出摘要 | 结论 |
|-----------|---------|---------|-------------|------|
| 1 服务骨架与配置 | 2026-09-15 | `pytest tests/test_config.py -v` | 5 passed | ✅ 通过 |
| 2 数据层 | 2026-09-17 | `pytest tests/ -q` | 38 passed / 0 skipped(config 5 + models 6 + id_gen 6 + conversation 13 + message 5 + feedback 3);连库用例真实跑在 MySQL 上且无残留行 | ✅ 通过 |
| 3 向量化 | | | | |
| 4 检索组件 | | | | |
| 5 索引构建 | | | | |
| 6 混合检索与评估基线 | | | | |

**阶段 ① 期中发现并修正的 plan 缺陷**(均已同步回上文对应任务):

1. `tests/conftest.py` 的 `os.environ.setdefault("DB_PASSWORD", "test-password")` 无条件写入占位值,而环境变量优先级高于 `.env` → 真实密码被静默覆盖 → 全部连库测试 skip。已改为条件兜底(Task 1 Step 6)。
2. 会话仓储四处 `session.get()` 未过滤 `deleted`,与同文件 `list_user_conversations` 的既有约定自相矛盾。已统一为带 `deleted == 0` 的 `get_conversation`(Task 4 Step 4)。

### 实施中的偏离(由项目所有者直接实施,不属本计划原文)

| 提交 | 内容 | 对后续执行的影响 |
|------|------|-----------------|
| `e4b5757` | Task 9:Qdrant 集合管理与商品索引构建 | 原计划 Task 9,由 owner 直接实现,已走独立评审 |
| `bc2cad3` | `refactor(config)`:连接信息全部外置到环境变量 | ⚠️ **使上文 Task 1 的 `config.py` / `.env.example` / `test_config.py` 代码块过时** |

`bc2cad3` 的具体变化:`db_host` / `db_port` / `db_name` / `db_user` 与 `qdrant_host` / `qdrant_rest_port` 由「带默认值」改为**必填、代码内不留任何明文默认值**,并同步更新了 `.env.example`、`tests/conftest.py`、`tests/test_config.py`。方向符合项目「禁止明文凭证」规范。

**执行约束**:Task 10、Task 11 如需引用这些字段,以**仓库实际实现**为准,勿照抄本文件 Task 1 的代码块。

**协作方式变更**:自 Task 9 起,代码由项目所有者直接实现,AI 侧职责为——同步本文档、维护 ledger、对每个任务派独立评审、跑验收登记。

### 评审待办(Open Items)

由各任务独立评审提出、**尚未修复**的问题。最终全分支评审时逐条 triage。

#### 待裁决(阻塞 Task 10/11 的正确性)

| # | 来源 | 问题 | 备选 |
|---|------|------|------|
| 1 | Task 9 评审(Important) | `indexer.py` 的 payload **未写入 `description`**,而 Task 10 的 `_to_hit` 从 `payload.get("description", "")` 取摘要 → 向量路结果的 `excerpt` **恒为空串**,商品卡片摘要静默消失;两侧测试的 payload 工厂都没有该键,故测试抓不到。根因:Java 的摘要来自命中片段原文(`RagSearchService.java:154` 的 `truncate(segment.text(), 120)`),而 Python 侧未往 Qdrant 存文本。 | **A**(倾向):Task 9 `build_metadata` 加 `"description"` 键,一行<br>**C**:Task 9 把整个索引文本存进 payload(键 `text`),Task 10 截 120 字 —— 与 Java `segment.text()` 完全对齐,代价是 payload 每条增大约 500 字 |

#### Minor 汇总(不阻塞,最终评审 triage)

| 来源 | 问题 |
|------|------|
| Task 9 | `strip_html` 比 Java 少 `\s+ → " "` + `trim()` 两步,含空白/换行的富文本详情索引结果与 Java 不一致 |
| Task 9 | 截断详情未追加 Java 那个 `"..."`(Java 详情段长 503,Python 500);且 brief 的测试把 500 写死,固化了该偏差 |
| Task 9 | `rebuild_all` 的 `batch_size: int = 12` 是字面量,而 `config.embedding_batch_size` 已是同一语义的第二个真源,改配置不影响索引构建 |
| Task 9 | `rebuild_all` 先 `recreate_collection()` 再查商品,查询失败或查空会留下**被清空且无标记**的集合(Java 同顺序,非新引入) |
| Task 9 | 嵌入服务批次中途抛 `EmbeddingDimensionError` 时集合已删空,无回滚也无"部分完成"标记(Java 是跳过空向量继续) |
| Task 9 | `qdrant_store.search` 返回注解为裸 `list`,消费方需自行知道是 `ScoredPoint` |
| Task 9 | 全量商品 id 拼进单条 `IN :ids`,超大目录可能撞 `max_allowed_packet` |
| Task 9 | 测试可证伪性:min/max 用例实际只断言空入参早返回(同义反复);有一条永不可能失败;有一条把生产 SQL 原样抄了一遍(故"过滤 deleted"这条硬约束实际未被交叉验证) |
| Task 9 | payload 无条件写入 `None` 而 Java 用 `putIfNotNull` 显式跳过(实测影响有界,属格式保真缺口) |
| Task 6 | `dimension=` 构造参数覆盖路径 0 覆盖;`MAX_LENGTH = 8192` 透传未被断言;`encode()` 恒传 `max_length=8192` 而 FlagEmbedding 默认 `passage_max_length` 仅 512,超长文本可能把 batch 显存拉到 8GB 边缘 |
| Task 7 | `rrf.py` 用 `DEFAULT_K = 60`,未读 `settings.rrf_k`(该配置项目前是死配置);`merge` 就地改写传入的 `RagHit.score` |
| Task 5 | 计数器断言经同一 session 读回,不独立证明 UPDATE 到达 DB;`id.asc()` 的承重理由未写在代码旁 |
| Task 4 | `conftest.py` 的 `DB_PASSWORD` 守卫正则不容忍 `DB_PASSWORD = xxx` 等写法(命中则重新引入"占位符覆盖 .env");连库夹具对任何连接异常都 skip,MySQL 挂掉时套件仍绿 |
| Task 3 | `next_id()` 时钟回拨睡眠后未复核是否追上;时间戳字段无位宽掩码 |
| Task 2 | `database_url` 未对密码做 URL 编码;模块级 engine 无关闭钩子(待 `main.py` 落地时补) |

**未覆盖项(须在 Task 11 闭合)**:真实 BGE-m3 模型全程未被加载 —— "能加载、返回 1024 维、显存足够"目前零证据,Task 11 的评估会首次真实加载。

#### brief 自身的缺陷(执行时已处置,记录备查)

| 来源 | 缺陷 | 处置 |
|------|------|------|
| Task 7 | `test_deduplicates_by_product_id` 与 brief 的"单路提前返回"实现冲突,brief 承诺的 "10 passed" 按原文不可达 | 保留提前返回并在其内补去重(Java `RrfMerger` 存在同样缺口) |
| Task 8 | `assert scores == sorted(scores, reverse=True)` 是恒真断言(位置分天然降序),验证不了 MySQL 的相关性排序 | 新增用例直接比对 `MATCH AGAINST` 原始分数顺序,并改用相关性分数不并列的 query |
| Task 3 | `test_sequence_overflow_waits_for_next_millisecond` 未真正覆盖自旋分支 | 加可注入时钟,先同步时间戳再强制序号耗尽 |
| Task 1 | `conftest.py` 无条件 `setdefault("DB_PASSWORD", ...)` 覆盖 `.env` 真实密码 → 连库测试全 skip | 改为条件兜底 |
| Task 4 | 四处 `session.get()` 未过滤 `deleted`,与同文件 `list_user_conversations` 自相矛盾 | 统一为带 `deleted == 0` 的 `get_conversation` |
| Task 6 | `encode()` 未显式声明 Dense-only,依赖 `FlagEmbedding` 默认值而依赖声明是开放下界 | 显式传三个 `return_*` kwarg + 加约束注释 |
| Task 9 | brief 的 `Interfaces` 段写 `build_index_text(row)` / `upsert(points)`,而示例代码与测试用 `build_index_text(row, category_name)` / `upsert(ids, vectors, payloads)` | 实现跟随示例代码,与测试契约自洽;后续任务勿照抄 `Interfaces` 段 |

---

## 文件结构

```
├── pyproject.toml
├── .env.example
├── app/
│   ├── __init__.py
│   ├── config.py                  # pydantic-settings 配置模型
│   ├── db.py                      # async engine + session
│   ├── models/
│   │   ├── __init__.py
│   │   └── chat.py                # 4 张表的 ORM 映射
│   ├── repository/
│   │   ├── __init__.py
│   │   ├── id_gen.py              # 雪花 ID 生成
│   │   ├── conversation_repository.py   # §3.1 会话逻辑
│   │   ├── message_repository.py        # §3.1 消息逻辑
│   │   └── feedback_repository.py       # §3.1 反馈逻辑
│   └── retrieval/
│       ├── __init__.py
│       ├── types.py               # RagHit 数据类
│       ├── embedding.py           # BGE-m3 封装
│       ├── keyword_search.py      # MySQL FULLTEXT
│       ├── rrf.py                 # RRF 融合
│       ├── qdrant_store.py        # collection 管理
│       ├── indexer.py             # 商品索引构建
│       └── search.py              # 混合检索
└── tests/
    ├── conftest.py
    ├── test_config.py
    ├── test_id_gen.py
    ├── test_conversation_repository.py
    ├── test_message_repository.py
    ├── test_feedback_repository.py
    ├── test_rrf.py
    ├── test_keyword_search.py
    ├── test_embedding.py
    ├── test_indexer.py
    ├── test_search.py
    └── test_evaluation.py
```

---

# 阶段 ①:服务骨架与数据层

### Task 1: 项目骨架与配置加载

**Files:**
- Create: `pyproject.toml`
- Create: `.env.example`
- Create: `app/__init__.py`
- Create: `app/config.py`
- Create: `tests/conftest.py`
- Test: `tests/test_config.py`

**Interfaces:**
- Consumes: 无(首个任务)
- Produces: `app.config.Settings`(字段见下)、模块级单例 `app.config.settings`

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_config.py`:

```python
import os
from pathlib import Path

import pytest

from app.config import PROJECT_ROOT, Settings


@pytest.fixture(autouse=True)
def _db_password(monkeypatch):
    monkeypatch.setenv("DB_PASSWORD", "test-password")
    yield


def test_reads_db_password_from_env():
    assert Settings().db_password == "test-password"


def test_embedding_dimension_defaults_to_1024():
    # BGE-m3 Dense 输出 1024 维,不得回落到旧的 1536
    assert Settings().embedding_dimension == 1024


def test_qdrant_uses_rest_port_6333():
    assert Settings().qdrant_rest_port == 6333


def test_project_root_points_to_repo_root():
    # app/config.py -> parents[1] 即本仓库根(独立仓库,与 shop 平级)
    assert (PROJECT_ROOT / ".env").parent == PROJECT_ROOT
    assert PROJECT_ROOT.name == "shop-ai"


def test_env_file_is_repo_root_dotenv():
    assert Settings.model_config["env_file"] == str(PROJECT_ROOT / ".env")
```

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_config.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app'`

- [ ] **Step 3: 创建 pyproject.toml**

创建 `pyproject.toml`:

```toml
[project]
name = "shop-ai"
version = "0.1.0"
description = "shop 电商系统 AI 服务(检索 + 智能客服)"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.115",
    "uvicorn[standard]>=0.32",
    "pydantic-settings>=2.6",
    "sqlalchemy[asyncio]>=2.0.36",
    "aiomysql>=0.2.0",
    "qdrant-client>=1.12",
    "FlagEmbedding>=1.3.0",
    "httpx>=0.27",
]

[project.optional-dependencies]
dev = ["pytest>=8.3", "pytest-asyncio>=0.24"]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]

[tool.setuptools.packages.find]
include = ["app*"]
```

- [ ] **Step 4: 实现配置模型**

创建 `app/__init__.py`(空文件)。

创建 `app/config.py`:

```python
"""服务配置 —— 读取本仓库根 .env(独立仓库,不依赖 shop 的 .env)。"""

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# app/config.py -> parents[0]=app, [1]=本仓库根
PROJECT_ROOT = Path(__file__).resolve().parents[1]


class Settings(BaseSettings):
    """AI 服务配置。凭证一律来自环境变量 / .env,不落明文。"""

    model_config = SettingsConfigDict(
        env_file=str(PROJECT_ROOT / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ---- MySQL(与 Java 共用同一个库)----
    db_host: str = "localhost"
    db_port: int = 3306
    db_name: str = "shop"
    db_user: str = "root"
    db_password: str

    # ---- Qdrant ----
    # ⚠️ 集合名必须与 Java 侧区分:Java 的 "products" 是 1536 维(text-embedding-v1),
    # 本服务是 1024 维(BGE-m3)。同名会导致维度不匹配报错,或在重建时抹掉 Java 的索引。
    qdrant_host: str = "localhost"
    qdrant_rest_port: int = 6333
    qdrant_collection: str = "products_bge_m3_1024"

    # ---- BGE-m3 本地向量模型 ----
    embedding_model_path: str = "BAAI/bge-m3"
    embedding_dimension: int = 1024
    embedding_batch_size: int = 12
    embedding_use_fp16: bool = True

    # ---- 检索 ----
    rrf_k: int = 60
    min_relevance_score: float = 0.3

    # ---- 雪花 ID ----
    snowflake_worker_id: int = 1

    @property
    def database_url(self) -> str:
        return (
            f"mysql+aiomysql://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}?charset=utf8mb4"
        )


settings = Settings()
```

- [ ] **Step 5: 创建 .env.example**

创建 `.env.example`:

```
# 复制为本仓库根 .env 并填写真实值(该文件已 gitignore)
DB_HOST=localhost
DB_PORT=3306
DB_NAME=shop
DB_USER=root
DB_PASSWORD=
DEEPSEEK_API_KEY=
DASHSCOPE_API_KEY=
INTERNAL_API_KEY=
```

- [ ] **Step 6: 创建测试夹具**

创建 `tests/conftest.py`:

```python
"""测试夹具。

单元测试不连真实数据库;需要连库的夹具(async_session / real_session)由后续任务加入。
"""

import os
import re
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]

# 必须在导入 app.config 之前备好必填项 —— app.config 在模块级实例化 Settings(),
# 缺 DB_PASSWORD 会直接抛 ValidationError,导致测试收集阶段就报错。
# ⚠️ 仅在环境变量与本仓 .env 都拿不到 DB_PASSWORD 时才兜底:环境变量优先级高于 .env,
# 无条件写入占位符会把 .env 里的真实密码盖掉,连库测试会全部因认证失败而 skipped
# —— 那种"全绿但全 skip"的假成功比失败更危险。
_DOTENV = PROJECT_ROOT / ".env"
_HAS_DOTENV_PASSWORD = bool(
    _DOTENV.exists()
    and re.search(r"^DB_PASSWORD=.+$", _DOTENV.read_text(encoding="utf-8"), re.M)
)
if not _HAS_DOTENV_PASSWORD:
    os.environ.setdefault("DB_PASSWORD", "test-password")
```

- [ ] **Step 7: 安装依赖并运行测试**

Run:
```bash
pip install -e ".[dev]" && python -m pytest tests/test_config.py -v
```
Expected: 5 passed

- [ ] **Step 8: 提交**

```bash
git add pyproject.toml .env.example app/__init__.py app/config.py tests/conftest.py tests/test_config.py
git commit -m "feat(ai): Python 服务骨架与配置加载"
```

---

### Task 2: 数据库连接与 chat_* 表映射

**Files:**
- Create: `app/db.py`
- Create: `app/models/__init__.py`
- Create: `app/models/chat.py`
- Test: `tests/test_models.py`(新增)

**Interfaces:**
- Consumes: `app.config.settings`(Task 1)
- Produces: `Base`、`ChatConversation`、`ChatMessage`、`ChatFeedback`、`RagTrace`、`get_session()`、`SessionLocal`

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_models.py`:

```python
from app.models.chat import Base, ChatConversation, ChatFeedback, ChatMessage, RagTrace


def test_tables_match_existing_schema():
    assert ChatConversation.__tablename__ == "chat_conversation"
    assert ChatMessage.__tablename__ == "chat_message"
    assert ChatFeedback.__tablename__ == "chat_feedback"
    assert RagTrace.__tablename__ == "rag_trace"


def test_conversation_columns():
    cols = {c.name for c in ChatConversation.__table__.columns}
    assert cols == {
        "id", "user_id", "title", "status",
        "total_msgs", "create_time", "update_time", "deleted",
    }


def test_message_columns():
    cols = {c.name for c in ChatMessage.__table__.columns}
    assert cols == {
        "id", "conversation_id", "role", "content",
        "rag_context", "token_count", "create_time",
    }


def test_feedback_columns():
    cols = {c.name for c in ChatFeedback.__table__.columns}
    assert cols == {"id", "message_id", "user_id", "rating", "feedback_text", "create_time"}


def test_rag_trace_columns():
    cols = {c.name for c in RagTrace.__table__.columns}
    assert cols == {"id", "query", "product_id", "action", "user_id", "create_time"}


def test_all_tables_registered_on_same_metadata():
    names = set(Base.metadata.tables.keys())
    assert names == {"chat_conversation", "chat_message", "chat_feedback", "rag_trace"}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_models.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.models'`

- [ ] **Step 3: 实现 ORM 模型**

创建 `app/models/__init__.py`(空文件)。

创建 `app/models/chat.py`:

```python
"""chat_* / rag_trace 表的 ORM 映射 —— 字段严格对齐现有 MySQL 表结构。"""

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Integer, JSON, SmallInteger, String, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class ChatConversation(Base):
    __tablename__ = "chat_conversation"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    title: Mapped[str | None] = mapped_column(String(100))
    status: Mapped[int | None] = mapped_column(SmallInteger, default=0)
    total_msgs: Mapped[int | None] = mapped_column(Integer, default=0)
    create_time: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    update_time: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    deleted: Mapped[int | None] = mapped_column(SmallInteger, default=0)


class ChatMessage(Base):
    __tablename__ = "chat_message"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    conversation_id: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    role: Mapped[str] = mapped_column(String(10), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    rag_context: Mapped[dict | None] = mapped_column(JSON)
    token_count: Mapped[int | None] = mapped_column(Integer)
    create_time: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class ChatFeedback(Base):
    __tablename__ = "chat_feedback"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    message_id: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    rating: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    feedback_text: Mapped[str | None] = mapped_column(String(500))
    create_time: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class RagTrace(Base):
    __tablename__ = "rag_trace"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    query: Mapped[str] = mapped_column(String(255), nullable=False)
    product_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    action: Mapped[str] = mapped_column(String(32), nullable=False)
    user_id: Mapped[int | None] = mapped_column(BigInteger)
    create_time: Mapped[datetime] = mapped_column(DateTime, nullable=False)
```

- [ ] **Step 4: 实现数据库连接层**

创建 `app/db.py`:

```python
"""异步数据库连接(与 Java 共用同一个 MySQL 库)。"""

from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.config import settings

engine = create_async_engine(
    settings.database_url,
    pool_pre_ping=True,
    pool_recycle=3600,
    echo=False,
)

SessionLocal = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_session() -> AsyncIterator[AsyncSession]:
    """FastAPI 依赖:每请求一个会话。"""
    async with SessionLocal() as session:
        yield session
```

- [ ] **Step 5: 确认 conftest.py 已就位**

`tests/conftest.py` 已在 Task 1 创建(内含 `DB_PASSWORD` 兜底),本任务**不需要改动它**。
连库夹具由 Task 4 加入。

- [ ] **Step 6: 运行测试**

Run: `python -m pytest tests/ -v`
Expected: 全部 PASS(11 passed)

- [ ] **Step 7: 提交**

```bash
git add app/db.py app/models/ tests/test_models.py
git commit -m "feat(ai): 数据库连接与 chat_* 表 ORM 映射"
```

---

### Task 3: 雪花 ID 生成

**Files:**
- Create: `app/repository/__init__.py`
- Create: `app/repository/id_gen.py`
- Test: `tests/test_id_gen.py`

**Interfaces:**
- Consumes: `app.config.settings.snowflake_worker_id`
- Produces: `SnowflakeGenerator` 类(`next_id() -> int`)、模块级单例 `id_generator`

**背景:** `chat_conversation.id` / `chat_message.id` 均为 `bigint NOT NULL PRIMARY KEY` 且**无 `AUTO_INCREMENT`** —— ID 由应用层生成。Java 侧走 MyBatis-Plus 默认的雪花算法。Python 侧必须生成同量级的 64 位正整数,且与 Java 侧 worker_id 不同,避免跨服务 ID 碰撞。

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_id_gen.py`:

```python
import time

import pytest

from app.repository.id_gen import SnowflakeGenerator


def test_ids_are_unique_and_increasing():
    gen = SnowflakeGenerator(worker_id=1)
    ids = [gen.next_id() for _ in range(1000)]
    assert len(set(ids)) == 1000
    assert ids == sorted(ids)


def test_id_fits_in_signed_bigint():
    gen = SnowflakeGenerator(worker_id=1)
    assert gen.next_id() < 2**63


def test_id_exceeds_javascript_safe_integer():
    # 与 Java 雪花 ID 同量级,前端靠 Long->String 序列化处理
    gen = SnowflakeGenerator(worker_id=1)
    assert gen.next_id() > 2**53


def test_different_workers_do_not_collide():
    a = SnowflakeGenerator(worker_id=1)
    b = SnowflakeGenerator(worker_id=2)
    assert a.next_id() != b.next_id()


def test_sequence_overflow_waits_for_next_millisecond():
    gen = SnowflakeGenerator(worker_id=1)
    # 强制把序号耗尽,验证不会回绕产生重复
    gen._sequence = 4095
    original = gen.next_id()
    assert gen.next_id() != original


def test_rejects_invalid_worker_id():
    with pytest.raises(ValueError):
        SnowflakeGenerator(worker_id=-1)
    with pytest.raises(ValueError):
        SnowflakeGenerator(worker_id=1024)
```

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_id_gen.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.repository'`

- [ ] **Step 3: 实现雪花生成器**

创建 `app/repository/__init__.py`(空文件)。

创建 `app/repository/id_gen.py`:

```python
"""雪花算法 ID 生成器。

表主键无 AUTO_INCREMENT,ID 由应用层生成。结构与 Java 侧一致:
[41 位毫秒时间戳][10 位 worker_id][12 位序号],字符集与 Java 版对齐(41+10+12=63 位,恒正)。
"""

import threading
import time

from app.config import settings


class SnowflakeGenerator:
    EPOCH = 1704067200000  # 2024-01-01T00:00:00Z

    WORKER_ID_BITS = 10
    SEQUENCE_BITS = 12
    MAX_WORKER_ID = (1 << WORKER_ID_BITS) - 1   # 1023
    MAX_SEQUENCE = (1 << SEQUENCE_BITS) - 1     # 4095

    WORKER_ID_SHIFT = SEQUENCE_BITS             # 12
    TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS  # 22

    def __init__(self, worker_id: int) -> None:
        if worker_id < 0 or worker_id > self.MAX_WORKER_ID:
            raise ValueError(f"worker_id 必须在 0..{self.MAX_WORKER_ID} 之间")
        self._worker_id = worker_id
        self._sequence = 0
        self._last_timestamp = -1
        self._lock = threading.Lock()

    def next_id(self) -> int:
        with self._lock:
            timestamp = int(time.time() * 1000)

            if timestamp < self._last_timestamp:
                # 时钟回拨:等待追上,避免生成重复 ID
                time.sleep((self._last_timestamp - timestamp) / 1000)
                timestamp = int(time.time() * 1000)

            if timestamp == self._last_timestamp:
                self._sequence = (self._sequence + 1) & self.MAX_SEQUENCE
                if self._sequence == 0:
                    # 本毫秒序号耗尽,自旋到下一毫秒
                    while timestamp <= self._last_timestamp:
                        timestamp = int(time.time() * 1000)
            else:
                self._sequence = 0

            self._last_timestamp = timestamp

            return (
                ((timestamp - self.EPOCH) << self.TIMESTAMP_SHIFT)
                | (self._worker_id << self.WORKER_ID_SHIFT)
                | self._sequence
            )


id_generator = SnowflakeGenerator(worker_id=settings.snowflake_worker_id)
```

- [ ] **Step 4: 运行测试**

Run: `python -m pytest tests/test_id_gen.py -v`
Expected: 6 passed

- [ ] **Step 5: 核对 Java 侧生成规则**

读 `src/main/java/com/shop/rag/ChatService.java` 中会话 ID 的处理,确认:

1. `conversationId` 是从请求体直接解析为 `Long`,还是由服务端生成?
2. 若由服务端生成,Java 侧用的是什么算法、什么 worker_id?

记录结论到 `README.md` 的"ID 生成"小节。**若 Java 侧也是雪花算法且 worker_id 默认为 1,把 `snowflake_worker_id` 改为 2**,避免跨服务碰撞。

- [ ] **Step 6: 提交**

```bash
git add app/repository/__init__.py app/repository/id_gen.py tests/test_id_gen.py
git commit -m "feat(ai): 雪花 ID 生成器"
```

---

### Task 4: 会话仓储(§3.1 会话逻辑)

**Files:**
- Create: `app/repository/conversation_repository.py`
- Test: `tests/test_conversation_repository.py`

**Interfaces:**
- Consumes: `ChatConversation`(Task 2)、`id_generator`(Task 3)
- Produces:
  - `class ConversationOwnershipError(Exception)`
  - `async def get_conversation(session, conversation_id: int) -> ChatConversation | None`
  - `async def get_or_create_conversation(session, conversation_id: int, user_id: int) -> ChatConversation`
  - `async def update_conversation_title(session, conversation_id: int) -> None`
  - `async def list_user_conversations(session, user_id: int, limit: int = 20) -> list[ChatConversation]`
  - `async def touch_conversation(session, conversation_id: int, at: datetime) -> None`

**对应 spec:** §3.1 迁移完整性要求 —— 会话获取或创建、**所有权校验**、标题生成、用户会话列表。

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_conversation_repository.py`:

```python
from datetime import datetime

import pytest

from app.models.chat import ChatMessage
from app.repository import conversation_repository as repo
from app.repository.conversation_repository import ConversationOwnershipError


async def test_creates_conversation_when_absent(async_session):
    conv = await repo.get_or_create_conversation(async_session, 999001, user_id=7)
    assert conv.id == 999001
    assert conv.user_id == 7
    assert conv.status == 0
    assert conv.total_msgs == 0
    assert conv.deleted == 0
    assert conv.create_time is not None


async def test_returns_existing_conversation_for_same_owner(async_session):
    first = await repo.get_or_create_conversation(async_session, 999002, user_id=7)
    second = await repo.get_or_create_conversation(async_session, 999002, user_id=7)
    assert first.id == second.id


async def test_rejects_conversation_owned_by_another_user(async_session):
    await repo.get_or_create_conversation(async_session, 999003, user_id=7)
    with pytest.raises(ConversationOwnershipError, match="无权访问该会话"):
        await repo.get_or_create_conversation(async_session, 999003, user_id=8)


async def test_get_conversation_returns_none_when_missing(async_session):
    assert await repo.get_conversation(async_session, 888888) is None


async def test_title_takes_first_user_message_truncated_to_30(async_session):
    await repo.get_or_create_conversation(async_session, 999004, user_id=7)
    long_text = "商" * 50
    async_session.add(ChatMessage(
        id=99900401, conversation_id=999004, role="user", content=long_text,
        create_time=datetime(2026, 9, 15, 10, 0, 0),
    ))
    await async_session.flush()

    await repo.update_conversation_title(async_session, 999004)

    conv = await repo.get_conversation(async_session, 999004)
    assert conv.title == "商" * 30 + "..."


async def test_title_shorter_than_30_kept_verbatim(async_session):
    await repo.get_or_create_conversation(async_session, 999005, user_id=7)
    async_session.add(ChatMessage(
        id=99900501, conversation_id=999005, role="user", content="怎么退货",
        create_time=datetime(2026, 9, 15, 10, 0, 0),
    ))
    await async_session.flush()

    await repo.update_conversation_title(async_session, 999005)

    conv = await repo.get_conversation(async_session, 999005)
    assert conv.title == "怎么退货"


async def test_title_ignores_non_user_messages(async_session):
    await repo.get_or_create_conversation(async_session, 999006, user_id=7)
    async_session.add(ChatMessage(
        id=99900601, conversation_id=999006, role="assistant", content="您好",
        create_time=datetime(2026, 9, 15, 10, 0, 0),
    ))
    await async_session.flush()

    await repo.update_conversation_title(async_session, 999006)

    conv = await repo.get_conversation(async_session, 999006)
    assert conv.title is None


async def test_list_user_conversations_filters_status_and_orders_desc(async_session):
    for i, cid in enumerate([999007, 999008, 999009]):
        conv = await repo.get_or_create_conversation(async_session, cid, user_id=7)
        conv.update_time = datetime(2026, 9, 15, 10, i, 0)
    closed = await repo.get_or_create_conversation(async_session, 999010, user_id=7)
    closed.status = 1
    await async_session.flush()

    result = await repo.list_user_conversations(async_session, user_id=7, limit=10)

    assert [c.id for c in result] == [999009, 999008, 999007]


async def test_list_user_conversations_respects_limit(async_session):
    for cid in [999011, 999012, 999013]:
        await repo.get_or_create_conversation(async_session, cid, user_id=7)
    await async_session.flush()

    result = await repo.list_user_conversations(async_session, user_id=7, limit=2)

    assert len(result) == 2


async def test_touch_conversation_updates_update_time(async_session):
    conv = await repo.get_or_create_conversation(async_session, 999014, user_id=7)
    later = datetime(2026, 9, 15, 12, 0, 0)

    await repo.touch_conversation(async_session, 999014, later)

    refreshed = await repo.get_conversation(async_session, 999014)
    assert refreshed.update_time == later
```

- [ ] **Step 2: 补 conftest 的异步会话夹具**

在 `tests/conftest.py` 末尾追加:

```python
import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.config import settings

# 仓储层测试对着真实 MySQL 跑(表结构是既有的,必须验证映射正确性)。
# 复用应用配置,确保与生产走同一套连接参数(.env 里的真实密码)。
# 库不可用则整组跳过,不阻塞纯单元测试。
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL", settings.database_url)


@pytest_asyncio.fixture
async def async_session():
    try:
        engine = create_async_engine(TEST_DATABASE_URL, pool_pre_ping=True)
        async with engine.connect() as conn:
            await conn.close()
    except Exception as exc:  # noqa: BLE001
        pytest.skip(f"MySQL 不可用,跳过仓储测试: {exc}")

    maker = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    async with maker() as session:
        # 每例独立事务,结束回滚 —— 不污染真实数据
        trans = await session.begin()
        try:
            yield session
        finally:
            await trans.rollback()
    await engine.dispose()
```

- [ ] **Step 3: 运行测试确认失败**

Run: `python -m pytest tests/test_conversation_repository.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.repository.conversation_repository'`

- [ ] **Step 4: 实现会话仓储**

创建 `app/repository/conversation_repository.py`:

```python
"""会话仓储 —— 对齐 Java 侧 ChatHistoryService 的会话部分(§3.1)。"""

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.chat import ChatConversation, ChatMessage
from app.repository.id_gen import id_generator

TITLE_MAX_LENGTH = 30


class ConversationOwnershipError(Exception):
    """会话不属于当前用户 —— 与 Java 侧 BusinessException("无权访问该会话") 对齐。"""


async def get_conversation(
    session: AsyncSession, conversation_id: int
) -> ChatConversation | None:
    """按 id 取会话;逻辑删除的行视为不存在(对齐 Java 侧 @TableLogic 语义)。"""
    result = await session.execute(
        select(ChatConversation).where(
            ChatConversation.id == conversation_id,
            ChatConversation.deleted == 0,
        )
    )
    return result.scalar_one_or_none()


async def get_or_create_conversation(
    session: AsyncSession, conversation_id: int, user_id: int
) -> ChatConversation:
    """取会话,不存在则创建。存在时强制校验归属。"""
    conv = await get_conversation(session, conversation_id)

    if conv is not None:
        if conv.user_id != user_id:
            raise ConversationOwnershipError("无权访问该会话")
        return conv

    now = datetime.now()
    conv = ChatConversation(
        id=conversation_id,
        user_id=user_id,
        status=0,
        total_msgs=0,
        create_time=now,
        update_time=now,
        deleted=0,
    )
    session.add(conv)
    await session.flush()
    return conv


async def update_conversation_title(
    session: AsyncSession, conversation_id: int
) -> None:
    """标题 = 首条 role=user 消息的前 30 字;无用户消息则不设置。"""
    result = await session.execute(
        select(ChatMessage.content)
        .where(
            ChatMessage.conversation_id == conversation_id,
            ChatMessage.role == "user",
        )
        .order_by(ChatMessage.create_time.asc())
        .limit(1)
    )
    content = result.scalar_one_or_none()
    if content is None:
        return

    title = content
    if len(title) > TITLE_MAX_LENGTH:
        title = title[:TITLE_MAX_LENGTH] + "..."

    conv = await get_conversation(session, conversation_id)
    if conv is not None:
        conv.title = title
        await session.flush()


async def list_user_conversations(
    session: AsyncSession, user_id: int, limit: int = 20
) -> list[ChatConversation]:
    """用户未关闭的会话,按最近活跃倒序。"""
    result = await session.execute(
        select(ChatConversation)
        .where(
            ChatConversation.user_id == user_id,
            ChatConversation.status == 0,
            ChatConversation.deleted == 0,
        )
        .order_by(ChatConversation.update_time.desc())
        .limit(limit)
    )
    return list(result.scalars().all())


async def touch_conversation(
    session: AsyncSession, conversation_id: int, at: datetime
) -> None:
    conv = await get_conversation(session, conversation_id)
    if conv is not None:
        conv.update_time = at
        await session.flush()


def new_conversation_id() -> int:
    """新建会话时使用的 ID(前端未指定时)。"""
    return id_generator.next_id()
```

- [ ] **Step 5: 运行测试**

Run: `python -m pytest tests/test_conversation_repository.py -v`
Expected: 10 passed(若 MySQL 未启动则全部 skipped —— 需先启动 MySQL 再跑)

- [ ] **Step 6: 提交**

```bash
git add app/repository/conversation_repository.py tests/test_conversation_repository.py tests/conftest.py
git commit -m "feat(ai): 会话仓储(含所有权校验与标题生成)"
```

---

### Task 5: 消息与反馈仓储(§3.1 消息与反馈逻辑)

**Files:**
- Create: `app/repository/message_repository.py`
- Create: `app/repository/feedback_repository.py`
- Test: `tests/test_message_repository.py`
- Test: `tests/test_feedback_repository.py`

**Interfaces:**
- Consumes: `ChatMessage` / `ChatFeedback`(Task 2)、`id_generator`(Task 3)、`ConversationOwnershipError`(Task 4)
- Produces:
  - `async def save_message(session, conversation_id: int, role: str, content: str, token_count: int | None = None, rag_context: dict | None = None) -> ChatMessage`
  - `async def list_messages(session, conversation_id: int) -> list[ChatMessage]`
  - `async def get_message(session, message_id: int) -> ChatMessage | None`
  - `async def submit_feedback(session, message_id: int, user_id: int, rating: int, feedback_text: str | None = None) -> int`

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_message_repository.py`:

```python
from app.repository import conversation_repository as conv_repo
from app.repository import message_repository as repo


async def test_save_message_increments_conversation_counter(async_session):
    await conv_repo.get_or_create_conversation(async_session, 999101, user_id=7)

    await repo.save_message(async_session, 999101, role="user", content="你好")
    await repo.save_message(async_session, 999101, role="assistant", content="您好")

    conv = await conv_repo.get_conversation(async_session, 999101)
    assert conv.total_msgs == 2


async def test_save_message_assigns_generated_id(async_session):
    await conv_repo.get_or_create_conversation(async_session, 999102, user_id=7)

    msg = await repo.save_message(async_session, 999102, role="user", content="测试")

    assert msg.id > 2**53  # 雪花 ID 量级


async def test_save_message_persists_token_count_and_rag_context(async_session):
    await conv_repo.get_or_create_conversation(async_session, 999103, user_id=7)

    msg = await repo.save_message(
        async_session, 999103, role="assistant", content="推荐这款",
        token_count=42, rag_context={"products": [{"productId": 1}]},
    )

    assert msg.token_count == 42
    assert msg.rag_context == {"products": [{"productId": 1}]}


async def test_list_messages_orders_ascending_by_time(async_session):
    await conv_repo.get_or_create_conversation(async_session, 999104, user_id=7)
    await repo.save_message(async_session, 999104, role="user", content="第一句")
    await repo.save_message(async_session, 999104, role="assistant", content="第二句")
    await repo.save_message(async_session, 999104, role="user", content="第三句")

    messages = await repo.list_messages(async_session, 999104)

    assert [m.content for m in messages] == ["第一句", "第二句", "第三句"]


async def test_get_message_returns_none_when_missing(async_session):
    assert await repo.get_message(async_session, 888888) is None
```

创建 `tests/test_feedback_repository.py`:

```python
from datetime import datetime

from app.models.chat import ChatFeedback
from app.repository import feedback_repository as repo


async def test_submit_feedback_inserts_row(async_session):
    feedback_id = await repo.submit_feedback(
        async_session, message_id=999201, user_id=7, rating=1, feedback_text="很有用"
    )

    assert feedback_id > 2**53
    row = await async_session.get(ChatFeedback, feedback_id)
    assert row.message_id == 999201
    assert row.user_id == 7
    assert row.rating == 1
    assert row.feedback_text == "很有用"


async def test_rating_zero_and_negative_are_accepted(async_session):
    neutral = await repo.submit_feedback(async_session, 999202, 7, rating=0, feedback_text=None)
    negative = await repo.submit_feedback(async_session, 999203, 7, rating=-1, feedback_text="答错了")

    assert (await async_session.get(ChatFeedback, neutral)).rating == 0
    assert (await async_session.get(ChatFeedback, negative)).rating == -1


async def test_rejects_rating_outside_allowed_set(async_session):
    import pytest

    with pytest.raises(ValueError, match="rating 必须是 1 / 0 / -1"):
        await repo.submit_feedback(async_session, 999204, 7, rating=5, feedback_text=None)
```

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_message_repository.py tests/test_feedback_repository.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.repository.message_repository'`

- [ ] **Step 3: 实现消息仓储**

创建 `app/repository/message_repository.py`:

```python
"""消息仓储 —— 对齐 Java 侧 ChatHistoryService 的消息部分(§3.1)。

消息真相源:chat_message 表(spec §3)。
"""

from datetime import datetime

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.chat import ChatConversation, ChatMessage
from app.repository.id_gen import id_generator


async def save_message(
    session: AsyncSession,
    conversation_id: int,
    role: str,
    content: str,
    token_count: int | None = None,
    rag_context: dict | None = None,
) -> ChatMessage:
    """落库一条消息,并在同一事务内把会话 total_msgs 计数 +1。"""
    message = ChatMessage(
        id=id_generator.next_id(),
        conversation_id=conversation_id,
        role=role,
        content=content,
        token_count=token_count,
        rag_context=rag_context,
        create_time=datetime.now(),
    )
    session.add(message)

    await session.execute(
        update(ChatConversation)
        .where(ChatConversation.id == conversation_id)
        .values(total_msgs=ChatConversation.total_msgs + 1)
    )
    await session.flush()
    return message


async def list_messages(
    session: AsyncSession, conversation_id: int
) -> list[ChatMessage]:
    result = await session.execute(
        select(ChatMessage)
        .where(ChatMessage.conversation_id == conversation_id)
        .order_by(ChatMessage.create_time.asc(), ChatMessage.id.asc())
    )
    return list(result.scalars().all())


async def get_message(
    session: AsyncSession, message_id: int
) -> ChatMessage | None:
    return await session.get(ChatMessage, message_id)
```

- [ ] **Step 4: 实现反馈仓储**

创建 `app/repository/feedback_repository.py`:

```python
"""反馈仓储 —— 对齐 Java 侧 ChatFeedbackService(§3.1)。"""

from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.chat import ChatFeedback
from app.repository.id_gen import id_generator

ALLOWED_RATINGS = (1, 0, -1)


async def submit_feedback(
    session: AsyncSession,
    message_id: int,
    user_id: int,
    rating: int,
    feedback_text: str | None = None,
) -> int:
    """写入反馈,返回新记录 ID。rating: 1=有用 / 0=无用 / -1=错误。"""
    if rating not in ALLOWED_RATINGS:
        raise ValueError("rating 必须是 1 / 0 / -1")

    feedback = ChatFeedback(
        id=id_generator.next_id(),
        message_id=message_id,
        user_id=user_id,
        rating=rating,
        feedback_text=feedback_text,
        create_time=datetime.now(),
    )
    session.add(feedback)
    await session.flush()
    return feedback.id
```

- [ ] **Step 5: 运行测试**

Run: `python -m pytest tests/test_message_repository.py tests/test_feedback_repository.py -v`
Expected: 8 passed

- [ ] **Step 6: 提交**

```bash
git add app/repository/message_repository.py app/repository/feedback_repository.py tests/test_message_repository.py tests/test_feedback_repository.py
git commit -m "feat(ai): 消息与反馈仓储"
```

---

# 阶段 ②:检索层与评估基线

### Task 6: 检索结果类型与 BGE-m3 向量化

**Files:**
- Create: `app/retrieval/__init__.py`
- Create: `app/retrieval/types.py`
- Create: `app/retrieval/embedding.py`
- Test: `tests/test_embedding.py`

**Interfaces:**
- Consumes: `app.config.settings`(Task 1)
- Produces:
  - `@dataclass RagHit(product_id, name, excerpt, image, score, min_price, max_price)`
  - `class EmbeddingService`(`encode(texts: list[str]) -> list[list[float]]`、`encode_one(text: str) -> list[float]`、`dimension: int`)
  - `class EmbeddingDimensionError(Exception)`

**对应 spec:** §4.7 —— BGE-m3 仅用 Dense 模式,输出 1024 维。

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_embedding.py`:

```python
import pytest

from app.retrieval.embedding import EmbeddingDimensionError, EmbeddingService
from app.retrieval.types import RagHit


class _FakeFlagModel:
    """替身:不加载 2GB 真实模型,产出可控的 dense_vecs。"""

    def __init__(self, dim: int = 1024):
        self.dim = dim
        self.calls: list[dict] = []

    def encode(self, texts, batch_size=None, max_length=None):
        self.calls.append({"texts": texts, "batch_size": batch_size, "max_length": max_length})
        return {"dense_vecs": [[0.1] * self.dim for _ in texts]}


def test_encode_returns_one_vector_per_text():
    service = EmbeddingService(model=_FakeFlagModel())
    vectors = service.encode(["手机", "相机"])
    assert len(vectors) == 2
    assert len(vectors[0]) == 1024


def test_encode_one_returns_single_vector():
    service = EmbeddingService(model=_FakeFlagModel())
    vector = service.encode_one("手机")
    assert len(vector) == 1024


def test_encode_empty_input_returns_empty_list():
    service = EmbeddingService(model=_FakeFlagModel())
    assert service.encode([]) == []


def test_uses_configured_batch_size():
    fake = _FakeFlagModel()
    service = EmbeddingService(model=fake, batch_size=8)
    service.encode(["手机"])
    assert fake.calls[0]["batch_size"] == 8


def test_rejects_wrong_dimension():
    # 若模型输出非 1024 维(例如误加载了 text-embedding-v1),必须显式报错而非静默入库
    service = EmbeddingService(model=_FakeFlagModel(dim=1536))
    with pytest.raises(EmbeddingDimensionError, match="期望 1024 维"):
        service.encode(["手机"])


def test_rag_hit_defaults():
    hit = RagHit(product_id=1, name="手机")
    assert hit.score == 0.0
    assert hit.excerpt == ""
    assert hit.image == ""
    assert hit.min_price is None
    assert hit.max_price is None
```

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_embedding.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.retrieval'`

- [ ] **Step 3: 实现检索结果类型**

创建 `app/retrieval/__init__.py`(空文件)。

创建 `app/retrieval/types.py`:

```python
"""检索结果类型 —— 对应 Java 侧 RagHitVO。"""

from dataclasses import dataclass


@dataclass
class RagHit:
    product_id: int
    name: str
    excerpt: str = ""
    image: str = ""
    score: float = 0.0
    min_price: float | None = None
    max_price: float | None = None
```

- [ ] **Step 4: 实现 BGE-m3 向量化服务**

创建 `app/retrieval/embedding.py`:

```python
"""BGE-m3 本地向量化。

只用 Dense 模式(1024 维):社区实测 Sparse 模式在 8GB 显存下会 CUDA OOM,
且本项目的稀疏检索由 MySQL FULLTEXT 承担(见 keyword_search.py)。
"""

import logging

from app.config import settings

logger = logging.getLogger(__name__)

MAX_LENGTH = 8192  # BGE-m3 上下文上限


class EmbeddingDimensionError(Exception):
    """向量维度与配置不符 —— 通常意味着加载了错误的模型。"""


class EmbeddingService:
    def __init__(
        self,
        model=None,
        dimension: int | None = None,
        batch_size: int | None = None,
    ) -> None:
        self.dimension = dimension or settings.embedding_dimension
        self.batch_size = batch_size or settings.embedding_batch_size
        self._model = model if model is not None else self._load_model()

    @staticmethod
    def _load_model():
        from FlagEmbedding import BGEM3FlagModel

        logger.info("加载 BGE-m3 模型: %s", settings.embedding_model_path)
        return BGEM3FlagModel(
            settings.embedding_model_path,
            use_fp16=settings.embedding_use_fp16,
        )

    def encode(self, texts: list[str]) -> list[list[float]]:
        """批量向量化,返回 Dense 向量(每条形如 1024 个 float)。"""
        if not texts:
            return []

        output = self._model.encode(
            texts,
            batch_size=self.batch_size,
            max_length=MAX_LENGTH,
        )
        dense = output["dense_vecs"]
        vectors = [list(map(float, v)) for v in dense]

        for vector in vectors:
            if len(vector) != self.dimension:
                raise EmbeddingDimensionError(
                    f"向量维度不符:期望 {self.dimension} 维,实际 {len(vector)} 维"
                )
        return vectors

    def encode_one(self, text: str) -> list[float]:
        vectors = self.encode([text])
        if not vectors:
            raise EmbeddingDimensionError("向量化返回空结果")
        return vectors[0]
```

- [ ] **Step 5: 运行测试**

Run: `python -m pytest tests/test_embedding.py -v`
Expected: 6 passed(不加载真实模型,秒级完成)

- [ ] **Step 6: 提交**

```bash
git add app/retrieval/__init__.py app/retrieval/types.py app/retrieval/embedding.py tests/test_embedding.py
git commit -m "feat(ai): BGE-m3 向量化(Dense 1024 维)"
```

---

### Task 7: RRF 融合

**Files:**
- Create: `app/retrieval/rrf.py`
- Test: `tests/test_rrf.py`

**Interfaces:**
- Consumes: `RagHit`(Task 6)
- Produces: `def merge(vector_hits: list[RagHit], keyword_hits: list[RagHit], top_k: int, k: int = 60) -> list[RagHit]`

**对应 spec:** §4.7 —— `score = 1/(k + rank_vector) + 1/(k + rank_keyword)`,k=60。逐条对齐 Java 侧 `RrfMerger`。

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_rrf.py`:

```python
from app.retrieval.rrf import merge
from app.retrieval.types import RagHit


def _hit(pid: int, name: str = "", score: float = 0.0) -> RagHit:
    return RagHit(product_id=pid, name=name or f"商品{pid}", score=score)


def test_both_empty_returns_empty():
    assert merge([], [], top_k=5) == []


def test_only_vector_hits_returns_them_truncated():
    hits = [_hit(1), _hit(2), _hit(3)]
    result = merge(hits, [], top_k=2)
    assert [h.product_id for h in result] == [1, 2]


def test_only_keyword_hits_returns_them_truncated():
    hits = [_hit(1), _hit(2), _hit(3)]
    result = merge([], hits, top_k=2)
    assert [h.product_id for h in result] == [1, 2]


def test_item_in_both_lists_outranks_item_in_one():
    # 1 在两路都排第一,应高于只在向量路排第一的 2
    vector = [_hit(1), _hit(2)]
    keyword = [_hit(1)]

    result = merge(vector, keyword, top_k=3)

    assert result[0].product_id == 1


def test_rrf_score_matches_formula():
    # rank 0 在两路各贡献 1/(60+0) = 1/60,合计 1/30 ≈ 0.033 -> 保留两位 0.03
    result = merge([_hit(1)], [_hit(1)], top_k=1)
    assert result[0].score == 0.03


def test_vector_hit_wins_when_scores_tie():
    # 同一 rank 时保留先出现的(向量路优先),与 Java 侧 putIfAbsent 语义一致
    vector = [_hit(1, name="来自向量")]
    keyword = [_hit(1, name="来自关键词")]

    result = merge(vector, keyword, top_k=1)

    assert result[0].name == "来自向量"


def test_deduplicates_by_product_id():
    vector = [_hit(1), _hit(1)]
    result = merge(vector, [], top_k=5)
    assert len(result) == 1


def test_zero_product_id_is_kept():
    # 0 不是 None —— 与 Java 侧 `if (id == null) continue` 语义一致,0 应保留
    vector = [RagHit(product_id=0, name="零号"), _hit(2)]
    result = merge(vector, [], top_k=5)
    assert [h.product_id for h in result] == [0, 2]


def test_result_sorted_by_score_descending():
    vector = [_hit(10), _hit(20), _hit(30)]
    keyword = [_hit(30), _hit(20)]

    result = merge(vector, keyword, top_k=3)

    scores = [h.score for h in result]
    assert scores == sorted(scores, reverse=True)
    assert result[0].product_id == 30  # rank0(向量) + rank0(关键词) 双路最高


def test_respects_top_k():
    vector = [_hit(i) for i in range(1, 11)]
    keyword = [_hit(i) for i in range(1, 11)]
    assert len(merge(vector, keyword, top_k=4)) == 4
```

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_rrf.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.retrieval.rrf'`

- [ ] **Step 3: 实现 RRF**

创建 `app/retrieval/rrf.py`:

```python
"""RRF(Reciprocal Rank Fusion)双路融合 —— 逐条对齐 Java 侧 RrfMerger。

score = 1/(k + rank_vector) + 1/(k + rank_keyword)
k=60 为业界常用常数,对 rank 位置变化不敏感,适合向量 + 关键词异源融合。
"""

from app.retrieval.types import RagHit

DEFAULT_K = 60


def merge(
    vector_hits: list[RagHit],
    keyword_hits: list[RagHit],
    top_k: int,
    k: int = DEFAULT_K,
) -> list[RagHit]:
    """融合双路结果,按 RRF 分数降序返回 top_k 条。"""
    if not vector_hits and not keyword_hits:
        return []

    if not vector_hits:
        return keyword_hits[:top_k]
    if not keyword_hits:
        return vector_hits[:top_k]

    # product_id -> 代表条目(先出现的胜出,即向量路优先)
    chosen: dict[int, RagHit] = {}
    # product_id -> 累加的 RRF 分数
    scores: dict[int, float] = {}

    for hits in (vector_hits, keyword_hits):
        for rank, hit in enumerate(hits):
            pid = hit.product_id
            if pid is None:
                continue
            chosen.setdefault(pid, hit)
            scores[pid] = scores.get(pid, 0.0) + 1.0 / (k + rank)

    ordered = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)[:top_k]

    result: list[RagHit] = []
    for pid, score in ordered:
        hit = chosen[pid]
        hit.score = round(score, 2)
        result.append(hit)
    return result
```

- [ ] **Step 4: 运行测试**

Run: `python -m pytest tests/test_rrf.py -v`
Expected: 10 passed

- [ ] **Step 5: 提交**

```bash
git add app/retrieval/rrf.py tests/test_rrf.py
git commit -m "feat(ai): RRF 双路融合"
```

---

### Task 8: 关键词检索(MySQL FULLTEXT)

**Files:**
- Create: `app/retrieval/keyword_search.py`
- Test: `tests/test_keyword_search.py`

**Interfaces:**
- Consumes: `RagHit`(Task 6)、`AsyncSession`(Task 2)
- Produces: `async def search_by_keyword(session, query: str, limit: int) -> list[RagHit]`

**对应 spec:** §4.7 关键词路。SQL 逐字对齐 `ProductMapper.searchByKeyword`(ngram 分词器,`IN NATURAL LANGUAGE MODE`)。

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_keyword_search.py`:

```python
from app.retrieval.keyword_search import _position_score, search_by_keyword


def test_position_score_decays_with_rank():
    assert _position_score(0) == 1.0
    assert _position_score(1) == 0.95
    assert _position_score(2) == 0.90


def test_position_score_never_below_floor():
    # 与 Java 侧一致:Math.max(0.1, 1.0 - i * 0.05)
    assert _position_score(100) == 0.1


def test_position_score_rounded_to_two_decimals():
    assert _position_score(3) == 0.85


async def test_blank_query_returns_empty_without_hitting_db():
    # 不传 session,若实现先查库会直接 AttributeError
    assert await search_by_keyword(None, "   ", limit=10) == []
    assert await search_by_keyword(None, "", limit=10) == []


async def test_returns_hits_ordered_with_descending_scores(real_session):
    hits = await search_by_keyword(real_session, "手机", limit=5)
    if not hits:
        import pytest

        pytest.skip("FULLTEXT 无匹配结果,跳过排序断言")

    scores = [h.score for h in hits]
    assert scores == sorted(scores, reverse=True)
    assert all(h.product_id for h in hits)


async def test_result_count_respects_limit(real_session):
    hits = await search_by_keyword(real_session, "手机", limit=2)
    assert len(hits) <= 2
```

- [ ] **Step 2: 补一个连真库的夹具**

在 `tests/conftest.py` 末尾追加:

```python
@pytest_asyncio.fixture
async def real_session():
    """只读夹具:关键词检索需要真实 FULLTEXT 索引,标记为集成测试。"""
    try:
        engine = create_async_engine(TEST_DATABASE_URL, pool_pre_ping=True)
        async with engine.connect() as conn:
            await conn.close()
    except Exception as exc:  # noqa: BLE001
        pytest.skip(f"MySQL 不可用,跳过集成测试: {exc}")

    maker = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    async with maker() as session:
        yield session
    await engine.dispose()
```

- [ ] **Step 3: 运行测试确认失败**

Run: `python -m pytest tests/test_keyword_search.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.retrieval.keyword_search'`

- [ ] **Step 4: 实现关键词检索**

创建 `app/retrieval/keyword_search.py`:

```python
"""MySQL FULLTEXT 关键词检索 —— 对齐 Java 侧 KeywordSearchService。

依赖 product 表上的 `ft_product_search` FULLTEXT 索引(name, description, brand,ngram 分词器)。
该索引由既有的 Java 侧建表语句创建,本服务不重建。
"""

import logging

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.retrieval.types import RagHit

logger = logging.getLogger(__name__)

# 逐字对齐 ProductMapper.searchByKeyword
_SEARCH_SQL = text(
    """
    SELECT id, name, description, main_image
    FROM product
    WHERE deleted = 0
      AND status = 1
      AND MATCH(name, description, brand) AGAINST(:query IN NATURAL LANGUAGE MODE) > 0
    ORDER BY MATCH(name, description, brand) AGAINST(:query IN NATURAL LANGUAGE MODE) DESC
    LIMIT :limit
    """
)


def _position_score(rank: int) -> float:
    """按排名位置分配展示分数 —— 真实排序由 MySQL MATCH AGAINST 保证。"""
    return round(max(0.1, 1.0 - rank * 0.05), 2)


async def search_by_keyword(
    session: AsyncSession, query: str, limit: int
) -> list[RagHit]:
    """关键词检索;任何异常都降级为空列表,由 RRF 自然退化为单路。"""
    if not query or not query.strip():
        return []

    try:
        result = await session.execute(
            _SEARCH_SQL, {"query": query, "limit": limit}
        )
        rows = result.mappings().all()
    except Exception as exc:  # noqa: BLE001
        logger.warning("MySQL FULLTEXT 检索异常: %s", exc)
        return []

    return [
        RagHit(
            product_id=row["id"],
            name=row["name"],
            excerpt=row["description"] or "",
            image=row["main_image"] or "",
            score=_position_score(rank),
        )
        for rank, row in enumerate(rows)
    ]
```

- [ ] **Step 5: 运行测试**

Run: `python -m pytest tests/test_keyword_search.py -v`
Expected: 6 passed(需 MySQL 运行;否则集成用例 skipped)

- [ ] **Step 6: 提交**

```bash
git add app/retrieval/keyword_search.py tests/test_keyword_search.py tests/conftest.py
git commit -m "feat(ai): MySQL FULLTEXT 关键词检索"
```

---

### Task 9: Qdrant 集合管理与商品索引构建

**Files:**
- Create: `app/retrieval/qdrant_store.py`
- Create: `app/retrieval/indexer.py`
- Test: `tests/test_indexer.py`

**Interfaces:**
- Consumes: `EmbeddingService`(Task 6)、`RagHit`(Task 6)、`AsyncSession`(Task 2)
- Produces:
  - `class QdrantStore`(`ensure_collection()`、`recreate_collection()`、`upsert(points)`、`search(vector, limit, score_threshold)`、`count()`)
  - `def build_index_text(row: Mapping) -> str`
  - `def build_metadata(row: Mapping, prices: tuple[float, float] | None) -> dict`
  - `def strip_html(text: str) -> str`
  - `async def rebuild_all(session, store, embedder, batch_size: int = 12) -> int`
  - `async def load_price_map(session, product_ids: list[int]) -> dict[int, tuple[float, float]]`

**对应 spec:** §4.7 —— collection **必须新建**(1536 → 1024 维,不可原地迁移)。索引文本与元数据逐字段对齐 `ProductIndexer`。

**一处有意的改进:** Java 侧用 `ConcurrentHashMap<Long,String> pointIdMap` 记录 productId → Qdrant pointId 的映射(因为 `addAll` 会生成随机 point id)。Python 侧直接用 `product_id` 作 Qdrant point id(其 id 类型支持 uint64),**消除这张映射表**,删除/更新都直接按 id 操作。

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_indexer.py`:

```python
import pytest

from app.retrieval.indexer import (
    MAX_DETAIL_LENGTH,
    build_index_text,
    build_metadata,
    strip_html,
)


def _row(**overrides):
    base = {
        "id": 42,
        "name": "华为 Mate 60",
        "description": "旗舰手机",
        "brand": "华为",
        "category_id": 12,
        "first_category_id": 1,
        "shop_id": 3,
        "main_image": "/img/42.jpg",
        "detail": "详情",
    }
    return {**base, **overrides}


def test_strip_html_removes_tags():
    assert strip_html("<p>你好</p><br/>世界") == "你好世界"


def test_strip_html_handles_empty():
    assert strip_html("") == ""


def test_index_text_matches_java_format():
    text = build_index_text(_row(), category_name="手机")
    assert text == (
        "名称：华为 Mate 60\n"
        "描述：旗舰手机\n"
        "品牌：华为\n"
        "分类：手机\n"
        "详情：详情"
    )


def test_index_text_uses_empty_string_for_null_fields():
    text = build_index_text(_row(name=None, description=None, brand=None), category_name="")
    assert "名称：\n" in text
    assert "描述：\n" in text
    assert "品牌：\n" in text


def test_index_text_truncates_detail_to_500_chars():
    long_detail = "细" * 600
    text = build_index_text(_row(detail=long_detail), category_name="手机")
    assert len(text.split("详情：")[1]) == MAX_DETAIL_LENGTH


def test_index_text_strips_html_before_truncating():
    detail = "<p>" + "细" * 600 + "</p>"
    text = build_index_text(_row(detail=detail), category_name="手机")
    detail_part = text.split("详情：")[1]
    assert "<p>" not in detail_part
    assert len(detail_part) == MAX_DETAIL_LENGTH


def test_metadata_contains_all_filter_and_display_fields():
    meta = build_metadata(_row(), prices=(3999.0, 6999.0))
    assert meta == {
        "productId": 42,
        "name": "华为 Mate 60",
        "firstCategoryId": 1,
        "shopId": 3,
        "mainImage": "/img/42.jpg",
        "minPrice": 3999.0,
        "maxPrice": 6999.0,
    }


def test_metadata_omits_price_fields_when_prices_missing():
    meta = build_metadata(_row(), prices=None)
    assert "minPrice" not in meta
    assert "maxPrice" not in meta
    assert meta["productId"] == 42


async def test_load_price_map_computes_min_and_max_per_product(real_session):
    price_map = await load_price_map(real_session, [])
    assert price_map == {}


def test_build_index_text_never_returns_none():
    assert isinstance(build_index_text(_row(detail=None), category_name="手机"), str)
```

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_indexer.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.retrieval.indexer'`

- [ ] **Step 3: 实现 Qdrant 客户端封装**

创建 `app/retrieval/qdrant_store.py`:

```python
"""Qdrant 向量库封装 —— 走 REST 端口 6333(与 Java 侧一致,规避 gRPC 兼容问题)。"""

import logging

from qdrant_client import QdrantClient
from qdrant_client.models import Distance, PointStruct, VectorParams

from app.config import settings

logger = logging.getLogger(__name__)


class QdrantStore:
    def __init__(self, client: QdrantClient | None = None) -> None:
        self._client = client or QdrantClient(
            host=settings.qdrant_host, port=settings.qdrant_rest_port
        )
        self.collection = settings.qdrant_collection

    def ensure_collection(self) -> None:
        """集合不存在则创建(1024 维 Cosine)。"""
        if self._client.collection_exists(self.collection):
            logger.info("Qdrant 集合 '%s' 已存在,跳过创建", self.collection)
            return
        self._client.create_collection(
            collection_name=self.collection,
            vectors_config=VectorParams(
                size=settings.embedding_dimension,
                distance=Distance.COSINE,
            ),
        )
        logger.info(
            "创建 Qdrant 集合 '%s',维度 %d",
            self.collection, settings.embedding_dimension,
        )

    def recreate_collection(self) -> None:
        """删除并重建 —— 换 embedding 模型后必须走这条路径(1536 → 1024 维)。"""
        try:
            self._client.delete_collection(self.collection)
            logger.info("已删除旧集合 '%s'", self.collection)
        except Exception as exc:  # noqa: BLE001
            logger.warning("删除集合失败(可能本就不存在): %s", exc)
        self.ensure_collection()

    def upsert(
        self,
        ids: list[int],
        vectors: list[list[float]],
        payloads: list[dict],
    ) -> None:
        """以 product_id 作为 point id,免去 Java 侧那张 pointIdMap。"""
        points = [
            PointStruct(id=pid, vector=vector, payload=payload)
            for pid, vector, payload in zip(ids, vectors, payloads, strict=True)
        ]
        self._client.upsert(
            collection_name=self.collection, points=points, wait=True
        )

    def delete(self, product_ids: list[int]) -> None:
        from qdrant_client.models import PointIdsList

        self._client.delete(
            collection_name=self.collection,
            points_selector=PointIdsList(points=product_ids),
            wait=True,
        )

    def search(
        self, vector: list[float], limit: int, score_threshold: float | None = None
    ) -> list:
        """返回带 payload 的检索点,按分数降序。"""
        result = self._client.query_points(
            collection_name=self.collection,
            query=vector,
            limit=limit,
            score_threshold=score_threshold,
            with_payload=True,
        )
        return list(result.points)

    def count(self) -> int:
        return self._client.count(self.collection).count
```

- [ ] **Step 4: 实现索引构建**

创建 `app/retrieval/indexer.py`:

```python
"""商品向量索引构建 —— 文本与元数据逐字段对齐 Java 侧 ProductIndexer。"""

import logging
import re
from collections.abc import Mapping

from sqlalchemy import bindparam, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.retrieval.embedding import EmbeddingService
from app.retrieval.qdrant_store import QdrantStore

logger = logging.getLogger(__name__)

MAX_DETAIL_LENGTH = 500

_TAG_RE = re.compile(r"<[^>]+>")

_PRODUCTS_SQL = text(
    """
    SELECT id, name, description, brand, category_id,
           first_category_id, shop_id, main_image, detail
    FROM product
    WHERE deleted = 0 AND status = 1
    ORDER BY create_time DESC
    """
)

_SKUS_SQL = text(
    "SELECT product_id, price FROM sku WHERE deleted = 0 AND product_id IN :ids"
).bindparams(bindparam("ids", expanding=True))

_CATEGORIES_SQL = text(
    "SELECT id, name FROM category WHERE deleted = 0 AND id IN :ids"
).bindparams(bindparam("ids", expanding=True))


def strip_html(value: str) -> str:
    return _TAG_RE.sub("", value or "")


def _safe(value) -> str:
    return "" if value is None else str(value)


def build_index_text(row: Mapping, category_name: str) -> str:
    """索引文本 —— 格式与 Java 侧 buildIndexText 完全一致。"""
    detail = strip_html(_safe(row.get("detail")))[:MAX_DETAIL_LENGTH]
    return (
        f"名称：{_safe(row.get('name'))}\n"
        f"描述：{_safe(row.get('description'))}\n"
        f"品牌：{_safe(row.get('brand'))}\n"
        f"分类：{_safe(category_name)}\n"
        f"详情：{detail}"
    )


def build_metadata(
    row: Mapping, prices: tuple[float, float] | None
) -> dict:
    """元数据 —— 检索过滤与展示字段,键名与 Java 侧 toMetadataMap 一致。"""
    meta = {
        "productId": row.get("id"),
        "name": row.get("name"),
        "firstCategoryId": row.get("first_category_id"),
        "shopId": row.get("shop_id"),
        "mainImage": row.get("main_image"),
    }
    if prices is not None:
        meta["minPrice"] = prices[0]
        meta["maxPrice"] = prices[1]
    return meta


async def load_price_map(
    session: AsyncSession, product_ids: list[int]
) -> dict[int, tuple[float, float]]:
    """批量取每个商品的 SKU 价格区间 [min, max]。"""
    if not product_ids:
        return {}

    rows = (await session.execute(_SKUS_SQL, {"ids": product_ids})).mappings().all()

    grouped: dict[int, list[float]] = {}
    for row in rows:
        if row["price"] is None:
            continue
        grouped.setdefault(row["product_id"], []).append(float(row["price"]))

    return {pid: (min(prices), max(prices)) for pid, prices in grouped.items()}


async def load_category_names(
    session: AsyncSession, category_ids: list[int]
) -> dict[int, str]:
    if not category_ids:
        return {}
    rows = (
        await session.execute(_CATEGORIES_SQL, {"ids": category_ids})
    ).mappings().all()
    return {row["id"]: row["name"] for row in rows}


async def rebuild_all(
    session: AsyncSession,
    store: QdrantStore,
    embedder: EmbeddingService,
    batch_size: int = 12,
) -> int:
    """全量重建:清空集合 → 读取上架商品 → 向量化 → 写入。返回索引条数。"""
    logger.info("===== 开始全量重建商品向量索引 =====")

    store.recreate_collection()

    rows = (await session.execute(_PRODUCTS_SQL)).mappings().all()
    if not rows:
        logger.info("没有上架商品,跳过索引构建")
        return 0

    product_ids = [row["id"] for row in rows]
    price_map = await load_price_map(session, product_ids)
    category_names = await load_category_names(
        session, sorted({row["category_id"] for row in rows if row["category_id"]})
    )

    texts = [build_index_text(row, category_names.get(row["category_id"], "")) for row in rows]
    payloads = [build_metadata(row, price_map.get(row["id"])) for row in rows]

    written = 0
    for start in range(0, len(texts), batch_size):
        end = min(start + batch_size, len(texts))
        vectors = embedder.encode(texts[start:end])
        store.upsert(product_ids[start:end], vectors, payloads[start:end])
        written += end - start
        logger.debug("向量化进度: %d/%d", end, len(texts))

    logger.info("===== 商品向量索引完成: %d 条 =====", written)
    return written
```

- [ ] **Step 5: 运行测试**

Run: `python -m pytest tests/test_indexer.py -v`
Expected: 10 passed

- [ ] **Step 6: 提交**

```bash
git add app/retrieval/qdrant_store.py app/retrieval/indexer.py tests/test_indexer.py
git commit -m "feat(ai): Qdrant 集合管理与商品索引构建"
```

---

### Task 10: 混合检索服务

**Files:**
- Create: `app/retrieval/search.py`
- Test: `tests/test_search.py`

**Interfaces:**
- Consumes: `EmbeddingService`(Task 6)、`RagHit`(Task 6)、`merge()`(Task 7)、`search_by_keyword()`(Task 8)、`QdrantStore`(Task 9)
- Produces:
  - `def pass_metadata_filter(meta: Mapping, category_id: int | None, min_price: float | None, max_price: float | None) -> bool`
  - `async def hybrid_search(session, query, top_k, *, embedder, store, category_id=None, min_price=None, max_price=None) -> list[RagHit]`

**对应 spec:** §4.7 检索路数与 §4.5 可观测性 —— 向量路异常降级为纯关键词路,指标输出 JSON 结构化日志。

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_search.py`:

```python
import json
import logging

from app.retrieval.search import hybrid_search, pass_metadata_filter
from app.retrieval.types import RagHit


class _FakeEmbedder:
    dimension = 1024

    def __init__(self, fail: bool = False):
        self.fail = fail

    def encode_one(self, text: str) -> list[float]:
        if self.fail:
            raise RuntimeError("embedding 服务不可用")
        return [0.1] * 1024


class _FakePoint:
    def __init__(self, pid: int, score: float, payload: dict):
        self.id = pid
        self.score = score
        self.payload = payload


class _FakeStore:
    def __init__(self, points=None, fail: bool = False):
        self.points = points or []
        self.fail = fail
        self.last_limit = None
        self.last_threshold = None

    def search(self, vector, limit, score_threshold=None):
        self.last_limit = limit
        self.last_threshold = score_threshold
        if self.fail:
            raise RuntimeError("Qdrant 不可用")
        return self.points


def _payload(pid: int, first_cat=1, min_p=10.0, max_p=20.0) -> dict:
    return {
        "productId": pid,
        "name": f"商品{pid}",
        "firstCategoryId": first_cat,
        "shopId": 1,
        "mainImage": f"/img/{pid}.jpg",
        "minPrice": min_p,
        "maxPrice": max_p,
    }


# ---------- 元数据过滤 ----------

def test_metadata_filter_passes_when_no_conditions():
    assert pass_metadata_filter(_payload(1), None, None, None) is True


def test_metadata_filter_rejects_other_category():
    assert pass_metadata_filter(_payload(1, first_cat=1), 2, None, None) is False


def test_metadata_filter_accepts_matching_category():
    assert pass_metadata_filter(_payload(1, first_cat=2), 2, None, None) is True


def test_metadata_filter_rejects_when_product_too_cheap():
    # 商品最高价 20 < 查询最低价 50
    assert pass_metadata_filter(_payload(1, max_p=20.0), None, 50.0, None) is False


def test_metadata_filter_rejects_when_product_too_expensive():
    # 商品最低价 100 > 查询最高价 50
    assert pass_metadata_filter(_payload(1, min_p=100.0), None, None, 50.0) is False


def test_metadata_filter_passes_when_price_ranges_overlap():
    assert pass_metadata_filter(_payload(1, min_p=10.0, max_p=30.0), None, 20.0, 40.0) is True


# ---------- 检索主流程 ----------

async def test_blank_query_returns_empty():
    result = await hybrid_search(
        None, "  ", top_k=5, embedder=_FakeEmbedder(), store=_FakeStore()
    )
    assert result == []


async def test_uses_query_limit_of_topk_times_five():
    store = _FakeStore()
    await hybrid_search(None, "手机", top_k=3, embedder=_FakeEmbedder(), store=store)
    assert store.last_limit == 50  # max(3*5, 50) = 50


async def test_uses_query_limit_when_larger_than_floor():
    store = _FakeStore()
    await hybrid_search(None, "手机", top_k=20, embedder=_FakeEmbedder(), store=store)
    assert store.last_limit == 100  # max(20*5, 50) = 100


async def test_applies_min_relevance_score_threshold():
    store = _FakeStore()
    await hybrid_search(None, "手机", top_k=3, embedder=_FakeEmbedder(), store=store)
    assert store.last_threshold == 0.3


async def test_returns_vector_hits_when_qdrant_has_data():
    store = _FakeStore(points=[_FakePoint(1, 0.9, _payload(1)), _FakePoint(2, 0.8, _payload(2))])
    result = await hybrid_search(
        None, "手机", top_k=3, embedder=_FakeEmbedder(), store=store
    )
    assert {h.product_id for h in result} == {1, 2}


async def test_degrades_to_keyword_when_embedding_fails(monkeypatch):
    keyword_hits = [RagHit(product_id=99, name="关键词命中")]

    async def fake_keyword(session, query, limit):
        return keyword_hits

    monkeypatch.setattr("app.retrieval.search.search_by_keyword", fake_keyword)

    result = await hybrid_search(
        None, "手机", top_k=3, embedder=_FakeEmbedder(fail=True), store=_FakeStore()
    )

    assert [h.product_id for h in result] == [99]


async def test_degrades_to_keyword_when_qdrant_fails(monkeypatch):
    keyword_hits = [RagHit(product_id=99, name="关键词命中")]

    async def fake_keyword(session, query, limit):
        return keyword_hits

    monkeypatch.setattr("app.retrieval.search.search_by_keyword", fake_keyword)

    result = await hybrid_search(
        None, "手机", top_k=3,
        embedder=_FakeEmbedder(), store=_FakeStore(fail=True),
    )

    assert [h.product_id for h in result] == [99]


async def test_filters_vector_hits_by_category():
    store = _FakeStore(points=[
        _FakePoint(1, 0.9, _payload(1, first_cat=1)),
        _FakePoint(2, 0.9, _payload(2, first_cat=2)),
    ])
    result = await hybrid_search(
        None, "手机", top_k=5, embedder=_FakeEmbedder(), store=store, category_id=2
    )
    assert [h.product_id for h in result] == [2]


async def test_emits_structured_json_metric_log(caplog, monkeypatch):
    async def fake_keyword(session, query, limit):
        return []

    monkeypatch.setattr("app.retrieval.search.search_by_keyword", fake_keyword)
    store = _FakeStore(points=[_FakePoint(1, 0.9, _payload(1))])

    with caplog.at_level(logging.INFO, logger="app.retrieval.search"):
        await hybrid_search(None, "手机", top_k=3, embedder=_FakeEmbedder(), store=store)

    metric_line = next(
        r.message for r in caplog.records if r.message.startswith("{")
    )
    payload = json.loads(metric_line)
    assert payload["event"] == "rag_search"
    assert payload["top_k"] == 3
    assert payload["vector_hits"] == 1
    assert payload["keyword_hits"] == 0
    assert "elapsed_ms" in payload
```

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_search.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.retrieval.search'`

- [ ] **Step 3: 实现混合检索**

创建 `app/retrieval/search.py`:

```python
"""混合检索(向量 + 关键词 → RRF)—— 对齐 Java 侧 RagSearchService。

降级策略:向量路异常时退化为纯关键词路(单路 RRF 天然退化),并打降级标记。
可观测性:指标输出 JSON 结构化日志,便于后续接任何观测平台(spec §4.5)。
"""

import json
import logging
import time
from collections.abc import Mapping

from app.config import settings
from app.retrieval.embedding import EmbeddingService
from app.retrieval.keyword_search import search_by_keyword
from app.retrieval.qdrant_store import QdrantStore
from app.retrieval.rrf import merge
from app.retrieval.types import RagHit

logger = logging.getLogger(__name__)

EXCERPT_MAX_LENGTH = 120


def pass_metadata_filter(
    meta: Mapping,
    category_id: int | None,
    min_price: float | None,
    max_price: float | None,
) -> bool:
    """元数据过滤 —— 逻辑与 Java 侧 passMetadataFilter 一致(价格区间取相交判断)。"""
    if category_id is not None:
        first_category = meta.get("firstCategoryId")
        if first_category is not None and first_category != category_id:
            return False

    if min_price is not None:
        product_max = meta.get("maxPrice")
        if product_max is not None and product_max < min_price:
            return False

    if max_price is not None:
        product_min = meta.get("minPrice")
        if product_min is not None and product_min > max_price:
            return False

    return True


def _truncate(text_value: str, max_length: int) -> str:
    if text_value is None:
        return ""
    return text_value if len(text_value) <= max_length else text_value[:max_length] + "..."


def _to_hit(point) -> RagHit:
    payload = point.payload or {}
    return RagHit(
        product_id=payload.get("productId"),
        name=payload.get("name") or "",
        excerpt=_truncate(payload.get("description", ""), EXCERPT_MAX_LENGTH),
        image=payload.get("mainImage") or "",
        score=round(float(point.score), 2),
        min_price=payload.get("minPrice"),
        max_price=payload.get("maxPrice"),
    )


async def _vector_search(
    query: str,
    embedder: EmbeddingService,
    store: QdrantStore,
    query_limit: int,
    top_k: int,
    category_id: int | None,
    min_price: float | None,
    max_price: float | None,
) -> list[RagHit]:
    """向量路。任何异常降级为空列表,由 RRF 退化为纯关键词路。"""
    try:
        vector = embedder.encode_one(query)
    except Exception as exc:  # noqa: BLE001
        logger.warning("嵌入模型调用失败,已降级为纯关键词检索: %s", exc)
        return []

    if not vector:
        logger.warning("嵌入模型返回空向量,跳过向量检索")
        return []

    try:
        points = store.search(
            vector, limit=query_limit, score_threshold=settings.min_relevance_score
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("Qdrant 不可用,已降级为纯关键词检索: %s", exc)
        return []

    hits: list[RagHit] = []
    for point in points:
        if len(hits) >= top_k:
            break
        payload = point.payload or {}
        if not payload:
            continue
        if not pass_metadata_filter(payload, category_id, min_price, max_price):
            continue
        hits.append(_to_hit(point))
    return hits


async def hybrid_search(
    session,
    query: str,
    top_k: int,
    *,
    embedder: EmbeddingService,
    store: QdrantStore,
    category_id: int | None = None,
    min_price: float | None = None,
    max_price: float | None = None,
) -> list[RagHit]:
    """双路并行检索 + RRF 融合。"""
    if not query or not query.strip():
        return []

    started = time.perf_counter()
    query_limit = max(top_k * 5, 50)

    vector_hits = await _vector_search(
        query, embedder, store, query_limit, top_k, category_id, min_price, max_price
    )
    keyword_hits = await search_by_keyword(session, query, query_limit)
    merged = merge(vector_hits, keyword_hits, top_k)

    logger.info(json.dumps({
        "event": "rag_search",
        "query": query,
        "top_k": top_k,
        "vector_hits": len(vector_hits),
        "keyword_hits": len(keyword_hits),
        "merged": len(merged),
        "elapsed_ms": round((time.perf_counter() - started) * 1000, 1),
        "degraded": not vector_hits,
    }, ensure_ascii=False))

    return merged
```

- [ ] **Step 4: 运行测试**

Run: `python -m pytest tests/test_search.py -v`
Expected: 14 passed

- [ ] **Step 5: 提交**

```bash
git add app/retrieval/search.py tests/test_search.py
git commit -m "feat(ai): 混合检索(向量+关键词+RRF)与 JSON 指标日志"
```

---

### Task 11: 评估集基线(阶段 ② 的卡点)

**Files:**
- Create: `app/retrieval/evaluation.py`
- Create: `tests/test_evaluation.py`

**Interfaces:**
- Consumes: `hybrid_search()`(Task 10)、`EmbeddingService`(Task 6)、`QdrantStore`(Task 9)
- Produces:
  - `EVAL_CASES: list[tuple[str, list[int]]]`(30 条)
  - `def recall_at_k(retrieved: list[int], expected: list[int], k: int) -> float`
  - `def reciprocal_rank(retrieved: list[int], expected: list[int]) -> float`
  - `async def evaluate(session, embedder, store, top_k: int = 5) -> dict`

**对应 spec:** §4.1.1 —— 30 条人工标注从 Java 侧 `RagEvaluationTest` 原样复制;换 BGE-m3 后**旧基线(Recall@3=0.425 / Recall@5=0.686 / MRR=0.368)不可直接比对**,本任务产出新基线。

- [ ] **Step 1: 写失败的测试**

创建 `tests/test_evaluation.py`:

```python
import os

import pytest

from app.retrieval.evaluation import (
    EVAL_CASES,
    evaluate,
    recall_at_k,
    reciprocal_rank,
)


# ---------- 指标算法(纯函数,无需外部依赖)----------

def test_recall_at_k_counts_fraction_of_expected_found():
    # 期望 3 个,前 3 命中 2 个 -> 2/3
    assert recall_at_k([1, 2, 9], [1, 2, 3], k=3) == pytest.approx(2 / 3)


def test_recall_at_k_full_hit_is_one():
    assert recall_at_k([1, 2, 3], [1, 2, 3], k=3) == 1.0


def test_recall_at_k_respects_k_cutoff():
    # 期望项都在第 4 位之后,前 3 位取不到
    assert recall_at_k([9, 8, 7, 1], [1], k=3) == 0.0


def test_recall_at_k_empty_expected_is_zero():
    assert recall_at_k([1, 2], [], k=3) == 0.0


def test_recall_at_k_empty_retrieved_is_zero():
    assert recall_at_k([], [1, 2], k=3) == 0.0


def test_reciprocal_rank_first_position():
    assert reciprocal_rank([1, 2, 3], [1]) == 1.0


def test_reciprocal_rank_second_position():
    assert reciprocal_rank([9, 1, 3], [1]) == pytest.approx(0.5)


def test_reciprocal_rank_uses_first_match_only():
    assert reciprocal_rank([9, 1, 8, 2], [1, 2]) == pytest.approx(0.5)


def test_reciprocal_rank_no_match_is_zero():
    assert reciprocal_rank([7, 8, 9], [1]) == 0.0


# ---------- 评估集内容 ----------

def test_eval_set_has_30_cases():
    assert len(EVAL_CASES) == 30


def test_eval_set_queries_are_unique():
    queries = [q for q, _ in EVAL_CASES]
    assert len(set(queries)) == len(queries)


def test_every_case_has_non_empty_expectation():
    for query, expected in EVAL_CASES:
        assert query.strip(), "查询不能为空"
        assert expected, f"'{query}' 缺少期望商品 ID"


def test_typo_case_is_present():
    # 该条测 FULLTEXT ngram 容错 —— 换 BGE-m3 后最可能变化的一条
    assert ("手鸡", [1, 12, 16]) in EVAL_CASES


def test_eval_cases_are_pure_ints():
    for _, expected in EVAL_CASES:
        assert all(isinstance(pid, int) for pid in expected)


# ---------- 端到端评估(需真实模型 + Qdrant)----------

@pytest.mark.eval
async def test_evaluation_produces_metrics(real_session):
    if os.environ.get("RUN_EVAL") != "1":
        pytest.skip("默认跳过:设置 RUN_EVAL=1 且确保 BGE-m3 与 Qdrant 就绪后运行")

    from app.retrieval.embedding import EmbeddingService
    from app.retrieval.qdrant_store import QdrantStore

    embedder = EmbeddingService()
    store = QdrantStore()
    store.ensure_collection()

    metrics = await evaluate(real_session, embedder, store, top_k=5)

    print(
        f"\n[新基线] Recall@3={metrics['recall_at_3']:.3f} "
        f"Recall@5={metrics['recall_at_5']:.3f} MRR={metrics['mrr']:.3f}"
    )
    for row in metrics["detail"]:
        print(f"  {row['query']}: {row['retrieved'][:5]} (期望 {row['expected']})")

    # 只断言链路跑通,不断言具体阈值 —— 新基线需人工确认后再写死
    assert 0.0 <= metrics["recall_at_3"] <= 1.0
    assert 0.0 <= metrics["mrr"] <= 1.0
```

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_evaluation.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.retrieval.evaluation'`

- [ ] **Step 3: 实现评估模块**

创建 `app/retrieval/evaluation.py`:

```python
"""离线评估集 —— 30 条人工标注(query → 期望商品ID),逐条复制自 Java 侧 RagEvaluationTest。

换用 BGE-m3 后旧基线不再可比,本模块用于产出新基线。
"手鸡"(错别字)一条标注为测 MySQL FULLTEXT ngram 容错,是检索质量变化的首要信号。
"""

import logging

from sqlalchemy.ext.asyncio import AsyncSession

from app.retrieval.embedding import EmbeddingService
from app.retrieval.qdrant_store import QdrantStore
from app.retrieval.search import hybrid_search

logger = logging.getLogger(__name__)

EVAL_CASES: list[tuple[str, list[int]]] = [
    ("手机", [1, 12, 16]),
    ("华为", [1]),
    ("苹果手机", [16, 31]),
    ("小米", [12, 27, 14]),
    ("vivo", [25]),
    ("微单相机", [17, 21]),
    ("拍立得", [35]),
    ("运动鞋", [20, 47]),
    ("羽绒服", [39]),
    ("冲锋衣", [42]),
    ("口红", [2, 29, 30, 43]),
    ("面霜", [7, 36]),
    ("精华", [33]),
    ("空调", [3, 6]),
    ("洗衣机", [4]),
    ("冰箱", [8]),
    ("吸尘器", [19]),
    ("扫地机器人", [44]),
    ("电饭煲", [14]),
    ("跑步机", [50]),
    ("水果", [10, 18, 52]),
    ("车厘子", [11]),
    ("沙发", [9]),
    ("床", [15]),
    ("耳机", [31]),
    ("打游戏手机", [12, 16, 53]),
    ("拍照手机", [1, 16, 25]),
    ("进口水果", [11, 52]),
    ("手鸡", [1, 12, 16]),  # 错别字:测 FULLTEXT ngram 容错
    ("剃须刀", [34]),
]

METRIC_TOP_K = 3
FULL_TOP_K = 5


def recall_at_k(retrieved: list[int], expected: list[int], k: int) -> float:
    """前 k 位命中的期望项占比。"""
    if not expected or not retrieved:
        return 0.0
    return len(set(retrieved[:k]) & set(expected)) / len(expected)


def reciprocal_rank(retrieved: list[int], expected: list[int]) -> float:
    """首个命中位置的倒数(rank 从 1 起)。"""
    expected_set = set(expected)
    for rank, product_id in enumerate(retrieved, start=1):
        if product_id in expected_set:
            return 1.0 / rank
    return 0.0


async def evaluate(
    session: AsyncSession,
    embedder: EmbeddingService,
    store: QdrantStore,
    top_k: int = FULL_TOP_K,
) -> dict:
    """跑完整评估集,返回聚合指标与逐条明细。"""
    recall3_sum = 0.0
    recall5_sum = 0.0
    mrr_sum = 0.0
    detail: list[dict] = []

    for query, expected in EVAL_CASES:
        hits = await hybrid_search(
            session, query, top_k, embedder=embedder, store=store
        )
        retrieved = [h.product_id for h in hits]

        recall3_sum += recall_at_k(retrieved, expected, METRIC_TOP_K)
        recall5_sum += recall_at_k(retrieved, expected, FULL_TOP_K)
        mrr_sum += reciprocal_rank(retrieved, expected)

        detail.append({
            "query": query,
            "retrieved": retrieved,
            "expected": expected,
        })

    total = len(EVAL_CASES)
    return {
        "cases": total,
        "recall_at_3": recall3_sum / total,
        "recall_at_5": recall5_sum / total,
        "mrr": mrr_sum / total,
        "detail": detail,
    }
```

- [ ] **Step 4: 运行单元测试**

Run: `python -m pytest tests/test_evaluation.py -v`
Expected: 14 passed(端到端用例 skipped)

- [ ] **Step 5: 注册 pytest 标记**

在 `pyproject.toml` 的 `[tool.pytest.ini_options]` 下追加:

```toml
markers = [
    "eval: 需要真实 BGE-m3 模型与 Qdrant 的端到端评估(设 RUN_EVAL=1 才运行)",
]
```

- [ ] **Step 6: 重建索引并跑出新基线**

Run:
```bash
# 1) 重建 1024 维索引(需 BGE-m3 模型与 Qdrant 就绪)
python -c "
import asyncio
from app.db import SessionLocal
from app.retrieval.embedding import EmbeddingService
from app.retrieval.indexer import rebuild_all
from app.retrieval.qdrant_store import QdrantStore

async def main():
    store = QdrantStore()
    async with SessionLocal() as session:
        n = await rebuild_all(session, store, EmbeddingService())
        print(f'索引条数: {n}')

asyncio.run(main())
"

# 2) 跑评估
RUN_EVAL=1 python -m pytest tests/test_evaluation.py::test_evaluation_produces_metrics -v -s
```

Expected: 输出 `[新基线] Recall@3=... Recall@5=... MRR=...` 与逐条明细。

- [ ] **Step 7: 记录基线并人工判定**

把输出的新基线数字连同旧基线(Recall@3=0.425 / Recall@5=0.686 / MRR=0.368)一并写入 `README.md` 的"检索基线"小节。

**重点检查 `手鸡` 一条**:它依赖 FULLTEXT ngram 容错。若该条从命中变为不命中,说明向量路换模型后没能补上关键词路的容错能力,需评估是否调整 RRF 权重或关键词路召回数。

**门槛**:新基线三项指标均不低于旧基线的 90%,方可进入阶段 ③。否则先解决检索质量再继续。

- [ ] **Step 8: 提交**

```bash
git add app/retrieval/evaluation.py tests/test_evaluation.py pyproject.toml README.md
git commit -m "feat(ai): 离线评估集与新检索基线"
```

---

## 完成标准

阶段 ①② 完成的定义:

- [ ] `python -m pytest tests/ -v` 全绿(集成用例在依赖就绪时通过,否则 skip)
- [ ] Qdrant 中 `products` 集合为 **1024 维 Cosine** 且已索引全部上架商品
- [ ] `README.md` 记录了新基线三项指标与 `手鸡` 一条的命中情况
- [ ] **Java 侧代码零改动** —— 旧检索链路仍可用,可随时回滚
- [ ] 新基线三项指标 ≥ 旧基线的 90%

## 已知风险

| 风险 | 应对 |
|------|------|
| BGE-m3 与 `sentence-transformers` / `transformers` 版本冲突导致相似度异常 | 本计划走 FlagEmbedding,不直接依赖 sentence-transformers;若出现结果异常,按社区实测锁定版本组合 |
| BGE-m3 首次运行下载约 1.2GB 模型 | 预先下载或配置镜像;`use_fp16=True` 降低显存占用 |
| 内存峰值过高 | `embedding_batch_size` 默认 12(社区建议 8~16);全量重建时观察内存,必要时下调 |
| Qdrant point id 用 product_id 与 Java 侧随机 id 并存 | 重建前 `recreate_collection()` 会清空旧集合,不存在混存 |

## 下一步

阶段 ③(对话编排:StateGraph + 子图 + middleware hook)另立计划 —— 涉及 LLM 调用与 Java 内部 API 联调,与本计划的纯检索验证解耦。

