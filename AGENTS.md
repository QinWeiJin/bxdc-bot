# AGENTS.md — bxdc-bot 项目 AI agent 工作规则

> 写给以后接手这个项目的 AI agent（或断点重启后的我自己）的工作约定。
> 补充项目本身不具备、但需要长期记住的上下文。

---

## 1. 部署规则 ⚠️ 最重要

**用户内网生产部署靠 `git pull` + 拿 `frontend/dist/` 和 `backend/agent-core/dist/` 直接跑。**

所以**任何代码改动合并后，必须在本地 rebuild + commit + push 产物**，否则同事部署会拿到旧版本。

### 触发条件
- 同事 PR 合并后
- 自己改了 `frontend/src/` 或 `backend/agent-core/src/` 后
- 用户说"传一下代码"/"重新拉取"/"重启"等交付动词
- OpenSpec 归档前（如 archive 一个含代码改动的 change 时）

### 不会触发
- 纯文档改动（`openspec/`、`.md`、`.gitignore`、`*.example`）
- `skill-gateway` Java 端代码改动（产物在 `target/`，不通过 dist 部署）
- 没动 `frontend/agent-core src` 时

### 完整工作流
```bash
# 1. 拉最新代码
git pull --rebase myfork low-version

# 2. 本地 build
cd frontend && npm run build           # vue-tsc -b && vite build
cd backend/agent-core && npm run build  # nest build (incremental)

# 3. commit + push
git add -A
git commit -m "build: 同步 dist 产物（<说明>）"
git push myfork low-version
```

---

## 2. 本地开发配置

### 数据库配置
- `backend/skill-gateway/src/main/resources/application.properties` **已 gitignore**
- 每个开发者自己 cp `.example` 模板 → 填入本机 MySQL 密码：
  ```bash
  cp backend/skill-gateway/src/main/resources/application.properties.example \
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
cd backend/skill-gateway && ./apache-maven-3.9.6/bin/mvn spring-boot:run
cd backend/agent-core && npm run start:dev
cd frontend && npm run dev
```

---

## 3. OpenSpec 工作流

每次有"功能/改动/归档"需求，先建 change：
```bash
npx openspec new change <name>     # 创建
npx openspec archive <name> -y     # 归档（-y 跳过交互）
```

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

## 5. 提交与分支约定

- **集成分支：`low-version`**（不是 main，所有改动先到这里）
- **远端：`myfork`** = `lijianlong1/bxdc-bot.git`（用 token 推送）
  - token 写在 git 命令里：`git push https://<token>@github.com/lijianlong1/bxdc-bot.git low-version`
- **Conventional Commits 风格**：
  - `feat(scope): 新功能`
  - `fix(scope): bug 修复`
  - `chore: 杂项`（配置、cleanup）
  - `docs: 文档`
  - `build: 构建产物`
  - `refactor: 重构`
  - 归档 OpenSpec change 用 `docs(openspec): ...`

---

## 6. 容易踩的坑

- **agent-core 残留进程**：`ps -ef | grep nest` 抓不到 `node` 启动的子进程，kill 时要按 PID 单独杀（grep 模式有 "node" 不一定匹配命令行）。
- **GitHub 推送限流**：经常 `Operation too slow` 或 `port 443 timeout`，sleep 30-50s 重试基本能过。
- **HEREDOC 在 zsh 里被破坏**：commit message 写 `/tmp/commit-msg.txt`，用 `git commit -F /tmp/commit-msg.txt`。
- **TypeScript `erasableSyntaxOnly: true` 禁用 enum**：用 union type + `as const satisfies Record<...>` 对象模式代替。
- **dist hash 变化**：Vite 会给主入口 css/js 换 hash，commit 时会删一堆旧 hash 文件 + 加新 hash 文件，正常。
- **应用 `192.168.65.1` client IP**：MySQL 看到的客户端 IP，Docker 桥接网络常见，可忽略。
