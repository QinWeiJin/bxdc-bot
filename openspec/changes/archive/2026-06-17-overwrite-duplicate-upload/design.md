## Context

`POST /api/files/upload` 当前对每个上传文件无脑 INSERT `user_files` 记录 + 新 UUID `file_name` 存 FTP。同名重复上传会产生多条历史记录，FTP 留下孤儿文件，浪费存储且列表混乱。

`FtpFileService.deleteFile(userId, fileName)` 已存在可直接复用；`UserFileMapper.findByUserIdAndOriginalFileName()` 也已存在。基础设施完整，缺的是 controller 层的"覆盖"分支。

## Goals / Non-Goals

**Goals:**
- 用户在确认对话框中点"覆盖"后，FTP 旧文件 + DB 旧记录同时被清理，新文件上传
- 默认行为（`overwrite=false`）完全不变，旧用例 0 回归
- 删除 FTP 失败时整个请求回滚（不上传新文件、不删 DB 旧记录）
- 用户隔离：A 的同名文件覆盖不会影响 B

**Non-Goals:**
- 不实现"软删除/历史版本"功能（用户明确要求只保留最新一条）
- 不修改 `file-management` 列表/删除相关逻辑
- 不改 FTP 存储路径规则
- 不改 schema-mysql.sql（现有 `idx_user_files_user_orig_name` 索引已够用）

## Decisions

### Decision 1: 新增 `overwrite` 请求参数，不修改现有端点签名

`POST /api/files/upload?overwrite=true&file=...&conversationId=...`

**理由**：保持现有端点 URL 稳定，旧调用方 0 改动。`overwrite` 默认 `false`，未传 = 保持旧行为（无回归风险）。

**Alternatives considered:**
- 用新端点 `POST /api/files/upload-overwrite`：多一个端点增加维护成本，且前端要切
- 用 HTTP header `X-Overwrite: true`：不够显式，query string 更直接

### Decision 2: 删 FTP → 删 DB → 上传新文件（严格顺序）

```java
if (overwrite) {
    Optional<UserFile> old = userFileMapper.findByUserIdAndOriginalFileName(userId, originalFileName);
    if (old.isPresent()) {
        // 1. 删 FTP 旧文件（失败 → 直接 502 抛出，不进入后续步骤）
        ftpFileService.deleteFile(userId, old.get().getFileName());
        // 2. 删 DB 旧记录（含 enabled_files 引用等）
        userFileMapper.deleteById(old.get().getId());
    }
}
// 3. 上传新文件（用新 UUID file_name）
String ftpPath = ftpFileService.uploadFile(...);
// 4. INSERT 新 UserFile 记录
```

**理由**：FTP 删失败回滚最干净 — 旧文件还在，新文件不传。DB 删失败时旧文件已删但记录还在，可用 `/api/files/{id}` 重新触发清理（acceptable degrade）。

**Alternatives considered:**
- 用事务包住整个流程：FTP 操作不在 DB 事务内，`@Transactional` 包不住 FTP，事务边界难画
- 先 INSERT 新记录再处理旧的：旧记录残留期更长，列表会出现"两条同名词条"过渡态

### Decision 3: `enabled_files` 等引用 `user_files.id` 的表由 DB 外键 ON DELETE CASCADE 处理

需检查 schema-mysql.sql 中 `enabled_files` 等表的外键定义。如果已有 `ON DELETE CASCADE`，无需改代码。如果没有，需要在 controller 显式删引用。

**理由**：避免遗漏引用导致孤儿引用。

### Decision 4: 前端"覆盖"确认弹框

`MessageInput.vue` / `useFileUpload.ts` 在选择文件后调 `GET /api/files/check-duplicate?fileName=xxx`，如果 `exists=true` 弹 TDesign `t-dialog`：

- 标题："文件已存在"
- 内容："已存在同名文件 xxx.docx，上传时间为 YYYY-MM-DD HH:mm。是否覆盖？"
- 按钮：取消 / 覆盖
- 覆盖按钮点击 → `POST /api/files/upload?overwrite=true`

**理由**：和现有"上传中"、"解析失败"等 toast 风格统一，t-dialog 是项目已有的 UI 组件。

## Risks / Trade-offs

- **[Risk] 覆盖过程中前端用户取消操作** → 当前没有"上传中取消"功能，POST 一旦开始就跑到结束。如果用户在弹窗点完"覆盖"后悔了，只能等覆盖完后再用 `/api/files/{id}` 删掉。**Mitigation**: 短期接受，长期可加"取消上传"按钮。
- **[Risk] `enabled_files` 等表没有 `ON DELETE CASCADE`，DB 删除时外键报错** → 需在实施时检查 schema。**Mitigation**: 实施第一步先检查 schema-mysql.sql，有 CASCADE 就走 DB 自动清理，没有就 controller 显式清理引用。
- **[Risk] 旧文件被覆盖但解析中的异步任务引用了旧 fileId** → `fileParseService.parseAndPersistAsync()` 异步跑，可能在覆盖后才写 parsed_summary 到旧 fileId。**Mitigation**: 检查 `FileParseService` 是否按 fileId 写 DB；如果是，解析结果写到旧 fileId 但旧记录已删，结果丢失。**Acceptable degrade**：日志 warning 即可，parsed_summary 是 best-effort。
- **[Trade-off] 旧文件已经存在的场景下用户其实想看历史版本** → 本次不做，按用户明确要求"只保留最新一条"。

## Migration Plan

无需数据库迁移（`user_files` schema 不变）。无需新部署配置。

部署：
1. 后端：`backend/skill-gateway` mvn 重启生效
2. 前端：HMR 自动刷新
3. 回滚：revert commit 即可，旧行为完全保留

## Open Questions

- Q1: `enabled_files` 等引用 `user_files.id` 的表是否已有 `ON DELETE CASCADE`？实施第一步要确认
- Q2: 旧文件被异步解析时（race condition），解析结果写到已删的 `oldFileId`，是否需要在 DELETE 前先 `mark deleting=true`？当前方案选择"接受解析结果丢失"简化逻辑，确认是否可接受
