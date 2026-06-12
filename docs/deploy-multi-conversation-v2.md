# v2 多对话 + API 发布 部署手册

> 适用版本：`temp` 分支 `8562ad0` 及之后
> 涵盖：数据库 schema 迁移、存量数据适配、构建部署

---

## 1. 前置条件

| 组件 | 版本/要求 |
|------|-----------|
| JDK | 1.8（`pom.xml` `<java.version>1.8</java.version>`） |
| Node.js | 18+ |
| MySQL | 8.0 |
| Maven | 3.8.5（内网）、本地可用 3.9.x |

---

## 2. 拉取代码 & 配置

```bash
git pull origin temp

# application.properties 被 .gitignore 排除，需要手动创建
cp backend/skill-gateway/src/main/resources/application.properties.example \
   backend/skill-gateway/src/main/resources/application.properties
# 修改 password= 为你的 MySQL 密码
```

---

## 3. 数据库 Schema 迁移

### 3.1 自动迁移（推荐）

**启动 gateway 时会自动执行**，无需手动操作。

`SchemaMigrationRunner` 在应用启动时（`afterPropertiesSet` 阶段）幂等执行以下 DDL：

| 表 | 操作 | DDL |
|----|------|-----|
| `conversations` | 加列 | `is_published TINYINT(1) DEFAULT 0` |
| `conversations` | 加列 | `api_description TEXT` |
| `conversations` | 加列 | `api_key VARCHAR(64)` |
| `conversations` | 加列 | `api_key_hash VARCHAR(64)` |
| `conversations` | 加索引 | `UNIQUE INDEX idx_api_key_hash ON (api_key_hash)` |
| `conversation_messages` | 加列 | `source VARCHAR(10) DEFAULT 'web'` |

`api_call_logs` 新表由 `schema-mysql.sql` 的 `CREATE TABLE IF NOT EXISTS` 自动创建。

### 3.2 手动执行（备选）

如果不想依赖自动迁移，在 MySQL 中手动执行：

```sql
USE fishtank;

ALTER TABLE conversations ADD COLUMN is_published TINYINT(1) NOT NULL DEFAULT 0
  COMMENT '是否已发布为API: 0=未发布, 1=已发布';
ALTER TABLE conversations ADD COLUMN api_description TEXT NULL
  COMMENT 'API描述文本，发布时填写，作为LLM对话上下文的系统消息';
ALTER TABLE conversations ADD COLUMN api_key VARCHAR(64) NULL
  COMMENT 'API调用密钥明文';
ALTER TABLE conversations ADD COLUMN api_key_hash VARCHAR(64) NULL
  COMMENT 'API调用密钥SHA-256哈希';
CREATE UNIQUE INDEX idx_api_key_hash ON conversations(api_key_hash);

ALTER TABLE conversation_messages ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'web'
  COMMENT '消息来源: web=网页端, api=API调用';
```

---

## 4. 构建 & 部署

```bash
# 前端
cd frontend && npm run build

# agent-core
cd backend/agent-core && npm run build

# skill-gateway（Maven）
cd backend/skill-gateway
./apache-maven-3.8.5/bin/mvn -s ./settings.xml package -DskipTests
# 或本地：
/Users/yangkai/Downloads/maven/apache-maven-3.9.11/bin/mvn -s ./settings.xml package -DskipTests
```

产物：
- `frontend/dist/` → nginx 静态文件
- `backend/agent-core/dist/` → NestJS 生产启动
- `backend/skill-gateway/target/*.jar` → JVM 运行

---

## 5. 存量 Skill 适配多对话模式

### 5.1 变更概述

旧模式：Skill 全局生效，所有对话共享同一套 Skill。

新模式（v2）：每个对话有独立的 `enabled_skills`（JSON 数组，如 `[1, 3, 5]`），
可在对话侧边栏的 **Skill配置** 面板中单独管理。

### 5.2 对存量 Skill 的影响

| 维度 | 影响 | 操作 |
|------|------|------|
| **Skill 数据** | 不受影响，`skills` 表无结构变更 | 无需操作 |
| **新对话的默认 Skill** | 新对话创建时 `enabled_skills = []`（空数组），不自动继承任何 Skill | 用户需手动在 Skill配置 面板中勾选需要的 Skill |
| **已有对话的 Skill** | 如果之前通过 API 直接修改了 `enabled_skills`，已保留；如果是旧架构的全局 Skill，对话的 `enabled_skills` 为空 | 用户需为每个对话手动配置一次 |
| **Skill 编辑（SkillManagementModal）** | 新建/编辑 Skill 后返回 SkillHub，不再影响当前对话的 Skill 配置 | 正常使用 |
| **扩展 Skill（extension 类型）** | conversation 的 `enabled_skills` 中勾选的扩展 Skill 会随对话上下文发给 agent-core | 无需额外操作 |

### 5.3 用户操作指南（可发给用户）

1. 打开目标对话
2. 点击对话侧边栏右侧的 **Skill配置** 按钮
3. 勾选该对话需要启用的扩展 Skill
4. 下次对话时，勾选的 Skill 会生效

每个对话的 Skill 是**独立的**——在不同对话中可以启用不同的 Skill 组合。

### 5.4 管理员操作（可选）

如果希望存量用户的**某类对话**默认启用特定 Skill，可以通过 SQL 批量设置：

```sql
-- 为所有 user_id=890728 的对话启用 skill_id=53
UPDATE conversations
SET enabled_skills = '[53]'
WHERE user_id = '890728';
```

---

## 6. 新特性：对话发布为 API

部署后即可使用以下功能：
- **发布**: 对话侧边栏 → 发布为API 图标 → 填写描述 → 生成 API Key
- **调用**: `curl -X POST http://<host>/api/agent-chat -H "Content-Type: application/json" -d '{"apiKey":"c_...","instruction":"你的问题"}'`
- **查看**: 已发布的对话页面自动切换为 API 详情视图（Key 管理 + 调用记录）
- **管理**: API 描述可编辑，Key 可重新生成

---

## 7. 验证清单

| 验证项 | 预期 |
|--------|------|
| Gateway 启动无报错 | `SchemaMigration` 日志正常 |
| 新建对话 | `enabled_skills` 为 `[]` |
| Skill配置面板 | 可勾选/取消 Skill，保存后生效 |
| 对话发布 | 发布弹窗 → 生成 Key → 页面自动切换为详情视图 |
| API 调用 | `curl POST /api/agent-chat` 返回 200 + 回复内容 |
| 调用记录 |详情页表格展示调用历史（输入/输出/耗时/状态） |
| 旧对话兼容 | 已有对话正常打开，消息历史完整 |
