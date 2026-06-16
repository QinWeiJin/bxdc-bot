# AGENTS.md — bxdc-bot 项目 AI agent 工作规则

> 写给以后接手这个项目的 AI agent（或断点重启后的我自己）的工作约定。
> 补充项目本身不具备、但需要长期记住的上下文。

---

## 1. 部署规则 ⚠️ 最重要

**dist/ 已被 .gitignore 排除（commit c5d6ff3 之后）**。

- `frontend/dist/` 和 `backend/agent-core/dist/` **不进 git**
- `application.properties` **不进 git**（用 `.example` 模板 + 各人 cp）
- 部署/同事 clone 下来**自己 `npm run build` 生成 dist**

### 工作流

**本地开发（不需要 dist）：**
```bash
# 启动 3 个服务（都走源码，不走 dist）
cd backend/skill-gateway && ./apache-maven-3.8.5/bin/mvn -s ./settings.xml spring-boot:run
cd backend/agent-core && npm run start:dev   # nest start --watch 走 ts-node
cd frontend && npm run dev                    # Vite 实时编译
```

### 提交源码时
- **只 commit 源码**（`frontend/src/`、`backend/agent-core/src/`、`backend/skill-gateway/src/main/java/`、OpenSpec、`.md`、`.gitignore` 等）
- **不 commit dist 产物**
- 跑 `git status` 确认没把 `dist/` 误带进来

---

## 2. 本地开发配置

### 数据库配置
- `backend/skill-gateway/src/main/resources/application.properties` **已 gitignore**（不进 git）
- 每个开发者自己 cp `.example` 模板 → 填入本机 MySQL 密码：
  ```bash
  # 模板文件名是 application-prod.example.properties
  cp backend/skill-gateway/src/main/resources/application-prod.example.properties \
     backend/skill-gateway/src/main/resources/application.properties
  # 改 password= 为本机 MySQL root 密码
```
- MySQL 跑在 Docker 容器 `bxdc-mysql`（已开 8 天，端口 3306）

### 服务端口
- skill-gateway: 18080（Spring Boot）
- agent-core: 3000（NestJS）
- frontend: 5173（Vite dev）
- MySQL: 3306（Docker）

### 一键启动（3 个服务）
```bash
# 不同 terminal
cd backend/skill-gateway && ./apache-maven-3.8.5/bin/mvn -s ./settings.xml spring-boot:run
cd backend/agent-core && npm run start:dev
cd frontend && npm run dev
```

### 2.4 Maven 离线仓库 .m2/（gitignore 内 + 内网部署规范）
- `backend/skill-gateway/.m2/` 目录**不进 git**（`.gitignore` 规则 `backend/skill-gateway/.m2/`)
- `backend/skill-gateway/settings.xml` **进 git**（项目自带，配置 localRepository 指向 `./.m2/repository`)
  - **不要**用 `${user.home}/.m2/repository`（用户家目录）—— 那意味着不同开发者共享用户家目录的 .m2，污染环境
  - 用相对路径 `./.m2/repository` 是因为 mvn 必须从 `backend/skill-gateway/` 启动（与上面启动命令约定一致），`./` 解析为 mvn 进程的工作目录
- settings.xml 同时配 aliyun maven mirror 作为兜底——`.m2/` 里**缺包**时 mvn 会自动从 aliyun 下载到 `.m2/repository/`
- **本地开发** `.m2` 会被 mvn 启动时自动补全（缺啥下啥，约 100-200MB）
- **内网部署**（生产环境无外网）：
  1. 把 `~/Desktop/m2-offline-bundle.tar.{xz,gz,zst}`（117MB，gitignored）拷到内网
  2. 解压到 `backend/skill-gateway/.m2/`
  3. 启动 mvn 时**不要**加 `-o`（offline）—— `-o` 模式下缺包会 BUILD FAILURE，让 aliyun mirror 兜底更安全
- 启动 mvn 必须用 `apache-maven-3.8.5`（与内网版本对齐）—— 3.9.x 的项目内 .m2 兼容，但内网只有 3.8.5

#### ⚠️ 启动 mvn 前必须 `cd backend/skill-gateway`（强警告）

`settings.xml` 的 `<localRepository>.m2/repository</localRepository>` 是**相对路径**，解析为 mvn 进程的工作目录（cwd）。**cwd 一旦跑偏，就会在其他位置创建出错的 .m2 目录**：

| 启动时的 cwd | localRepository 实际解析到 | 结果 |
|---|---|---|
| `<project_root>/backend/skill-gateway/` ✓ 正确 | `<project_root>/backend/skill-gateway/.m2/repository` | 正常 |
| `<project_root>/` ❌ | `<project_root>/.m2/repository` | **错**（在项目根多出一个 .m2）|
| `${HOME}/` ❌ | `${HOME}/.m2/repository` | **错**（污染用户家目录）|
| 任何其他 cwd ❌ | `<cwd>/.m2/repository` | **错**（mvn 自动创建新 .m2 位置）|

**因此启动 mvn 之前必须先 `cd backend/skill-gateway`**。**不要**用以下方式启动：
- ❌ `cd bxdc-bot && mvn -s backend/skill-gateway/settings.xml -f backend/skill-gateway/pom.xml ...`
- ❌ `mvn -s /abs/path/to/settings.xml ...` （在错误 cwd 下用绝对路径 settings.xml 但 relative path localRepository 仍然错）
- ❌ 任何 cwd 不是 `backend/skill-gateway/` 的 mvn 命令

**正确启动**（用 `cd` 进入 skill-gateway 目录）：
```bash
cd backend/skill-gateway
./apache-maven-3.8.5/bin/mvn -s ./settings.xml spring-boot:run
# 或者一行：
(cd backend/skill-gateway && ./apache-maven-3.8.5/bin/mvn -s ./settings.xml spring-boot:run)
```

**为什么不改 settings.xml 用绝对路径**：
- 项目用相对路径是**有意为之**（AGENTS.md 2.4 顶部）—— 保证每个开发者有自己的项目内 .m2，不与系统其他 maven 项目共享
- 改成 `${user.home}` 会跟系统其他项目共享 jar，可能被覆盖
- 改成本机绝对路径（不可移植）写死对同事不通用

**所以约束"启动 mvn 前必须 cd 到 skill-gateway"是项目内部约定**，跟 settings.xml 的相对路径配合使用。

#### ⚠️ AI agent 启动 mvn 必须用 `bash -c 'cd ... && mvn ...'` 模式

**坑**：`nohup mvn -s ./settings.xml ...` 或 `mvn ...` **mvn 进程继承父 shell 的 cwd**，即使 `-s` 用绝对路径指向 settings.xml，**settings.xml 里的相对路径 `<localRepository>.m2/repository</localRepository>` 仍然按 mvn 进程的 cwd 解析**，所以 cwd 错了仍会在错误位置生成 .m2。

**唯一可靠的启动方式**（用 `bash -c` 或 subshell 把 `cd` 一起带上）：
```bash
# 方式 1：bash -c
nohup bash -c 'cd backend/skill-gateway && ./apache-maven-3.8.5/bin/mvn -s ./settings.xml spring-boot:run' > /tmp/gw.log 2>&1 &

# 方式 2：subshell
(cd backend/skill-gateway && nohup ./apache-maven-3.8.5/bin/mvn -s ./settings.xml spring-boot:run > /tmp/gw.log 2>&1 &)
```

**错误方式**（cwd 在项目根或别处）：
```bash
# ❌ 错误：mvn 进程 cwd 仍是项目根
cd /Users/me/myproject
nohup mvn -s /Users/me/myproject/backend/skill-gateway/settings.xml ...

# ❌ 错误：cwd 是用户家
cd ~
mvn -s /Users/me/myproject/backend/skill-gateway/settings.xml ...
```

---

## 3. OpenSpec 工作流

每次有"功能/改动/归档"需求，遵循 OpenSpec 规约。

- 归档时如果报 `REMOVED failed for header "X" - not found`：原 spec 里没有这条 requirement，不能 REMOVE。删掉 `## REMOVED Requirements` 段，只保留 `## ADDED Requirements`。
- 归档产物落在 `openspec/changes/archive/YYYY-MM-DD-<name>/`。
- 主 spec 落地在 `openspec/specs/<capability>/spec.md`。

---

## 4. 异步任务系统（项目核心特性）

5 个原子 + 2 种模式：

| 原子 | 职责 |
|------|------|
| Submit | gateway 同步收一次 third-party，拿到 externalTaskId |
| Wait  | 客户端轮询 gateway 拿结果 |
| Audit | 写 `async_polling_audit_log` 记每步 phase |
| Dedup | SHA-256 签名 per-session 1h + no-session 60s 窗口去重 |
| Notify | 任务完成推前端通知中心（`/api/async-tasks/my`）|

**两种模式都走 fire-and-forget 立即返回**（不再阻塞 LLM）：
- `SINGLE_CALL`（单次长调用）：agent-core 提交完立即返回 `status: SINGLE_CALLED`
- `PERIODIC`（带 `pollEndpoint` 的轮询）：agent-core 提交完立即返回 `status: POLLING`

主 spec：`openspec/specs/api-extension-skill-llm-tool-call/spec.md`

---

## 5. 编程约束规范

团队代码规范，遵循这 5 条：

### 5.1 尽量不新增第三方包
- 新增第三方包意味着 supply chain 风险 + 依赖升级成本 + 团队学习成本
- 优先用 JDK 17 / NestJS 11 / Vue 3 / Spring Boot 2.7 自带的标准库
- 必须新增时需要评审：能不能用现有工具实现？有没有轻量级替代？

### 5.2 尽量不要新增环境变量配置
- 新的 env 变量意味着部署同事多配一项、新同事上手成本高
- 优先用代码内的合理默认值 + 配置文件覆盖
- 必须新增时需要用户明确要求，且要有默认值兜底

### 5.3 涉及数据库表操作可以使用 Java 代码做，尽量不增量运行 SQL 文件
- schema 变更优先用 Flyway / Liquibase 或 Spring Data JPA 自动 ddl，或者 `schema-mysql.sql` 一次性初始化（Spring `spring.sql.init.mode=always` 自动跑）
- **不要**在多个增量 commit 里改 `schema-mysql.sql` 让用户手动 `mysql -e "..."` 跑
- 复杂 schema 变更（加索引 / 改字段类型 / 数据迁移）走 Java migration 类（参考 `StartupRecoveryRunner` 模式）

### 5.4 Java 版本与 language level 必须保持 JDK 1.8
- 编译目标统一为 **JDK 1.8**（`pom.xml` 的 `<java.version>1.8</java.version>` 与 `maven-compiler-plugin` 的 `source/target` 同步）
- IntelliJ Project language level 必须设为 **8 - Lambdas, type annotations etc.**（与 JDK 1.8 严格对应）
  - 不要选 `11 - Local variable syntax for lambda parameters`、`14 - Switch expressions` 等更高 level
  - 不要选 `7 - Diamonds, ARM, multi-catch etc.`（过老，缺 type annotations）
- 代码里**禁止**出现 `var`、Records、`sealed interface`、`switch` 表达式（Java 14+ 特性）、`instanceof` 模式匹配（Java 16+）、text block `"""`（Java 13+ preview / Java 15+ GA）等高版本语法
- 如果用到了 JDK 1.8 不支持的新 API（例如 `List.of(...)` 是 Java 9+、`var` 是 Java 10+），需要**降级**到 1.8 兼容写法（如 `Arrays.asList(...)`、显式类型）
- 升级 JDK 需要团队评审 + 同步修改 IDEA 项目 language level + 更新本规约

### 5.5 新功能架构约束：不改 agent-core，走 Tool 接入 + Schema 动态渲染
- **尽量不修改 agent-core（NestJS）代码**：agent-core 作为 LLM 调度层应保持稳定，新增能力优先在 gateway（Java）侧以 Tool 形式接入
- **新能力 = 新 Skill 类型**：参照 `api`（API 代理）、`ssh`（SSH 执行）的模式，在 `SystemSkillController.buildXxxConfigSchema()` 中定义配置 schema，在 gateway 侧实现执行逻辑
- **Skill 编辑页用 Schema 驱动动态渲染**：`ConfigFormRenderer` 基于后端 `/api/system-skills/execution-types` 返回的 `configSchema` 动态渲染表单，新增 Skill 类型只需扩展后端 schema + ConfigFormRenderer 的 UI 类型支持（如 checkbox / radio 等），**不要**在 `SkillManagementModal.vue` 中为每种类型硬编码模板
- **如需修改 agent-core（基础能力级别变更）**：如果需求确实无法通过 gateway Tool 接入实现（如修改 LLM 调度策略、tool-call 协议、SSE 通信机制等基础能力），必须在 OpenSpec proposal + design 中**明确说明**：
  - 为什么不能走 Tool 接入？（技术瓶颈在哪）
  - 对 agent-core 哪些模块有影响？（具体文件 / 类 / 函数）
  - 对现有 Skill 类型的兼容性影响？（是否破坏已有 api / ssh / template / openclaw 的行为）
  - 是否需要 agent-core 单独回归测试？
  以便开发人员评估是否接受这次架构侵入

### 5.6 前端 build 必须零 TS6133（vue-tsc -b 严格模式）
- `frontend/package.json` 的 `build` 脚本是 `vue-tsc -b && vite build --skipTypeCheck`，**先跑 vue-tsc -b（严格 build mode）再跑 vite**。vue-tsc -b 严格模式会报 TS6133（声明但未使用），一旦有 TS6133 整个 build 立即 exit code 2，**不进入 vite 阶段**
- 重构 / 删字段 / 改 composable 解构后 MUST 自查所有未用 declaration，**不能用 `vue-tsc --noEmit` 蒙混过关**（单文件模式比 -b 宽松，可能漏报；CI / 同事 build 才会触发）
- 常见触发点（已踩过）：
  - 从 composable 解构出来的方法 / ref（如 `useAsyncTaskNotifications` 的 `deleteTask`）
  - 写了但没在 template 引用的 computed（如 `childCountByParent`）
  - 写了但没在 script 调用的辅助函数（如 `hasParentContext`、`formatToolSummary`）
  - 删字段后没删对应 import
- **自查 checklist**（重构后跑一遍）：
  1. `cd frontend && npx vue-tsc -b` 必须静默通过（无输出）
  2. `cd frontend && npm run build` 必须 exit 0
  3. 关注 vite 输出的 chunk size warning（如新增大依赖需说明）
- 教训来源：commit `53def51` 一次性删了 4 处 TS6133 declaration 才让 build 通过；之前 `vue-tsc --noEmit` 不报，但 `vue-tsc -b` 必报
