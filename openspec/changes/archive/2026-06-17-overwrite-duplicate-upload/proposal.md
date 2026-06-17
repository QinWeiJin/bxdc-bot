## Why

当前 `POST /api/files/upload` 对同名文件采取"新增副本"策略：每次都生成新的 UUID `file_name` 存到 FTP，并 `INSERT` 一条新的 `user_files` 记录。结果是同一个用户同一个原始文件名下产生多条历史记录，FTP 上也留下多个孤儿文件，浪费存储且列表里看着混乱。

用户上传同名文件点"确定"时，应该按"覆盖"语义处理：用新文件替换 FTP 上的旧文件，同时 `user_files` 表里只保留最新的一条记录。

## What Changes

- **`POST /api/files/upload`** 新增请求参数 `overwrite`（boolean，默认 `false`）。当 `overwrite=true` 时：
  1. 先按 `(user_id, original_file_name)` 查出旧 `UserFile` 记录
  2. 调用 `FtpFileService.deleteFile(userId, oldFileName)` 删掉 FTP 上的旧文件
  3. `DELETE FROM user_files WHERE id = oldId`（同时清掉引用此文件的外键记录，如 `enabled_files` 等）
  4. 再走正常上传流程，INSERT 新记录（保持 `original_file_name` 不变，`file_name` 是新 UUID）
- **`GET /api/files/check-duplicate`** 行为不变（前端用它判断要不要弹"是否覆盖"确认框）
- **前端**：上传组件检测到 `exists=true` 时弹确认框，用户点"覆盖"时给 `POST /api/files/upload` 加 `overwrite=true` 参数
- **回退语义**：删除 FTP 旧文件失败时回滚整个请求（不上传新文件、不删 DB 旧记录），返回 502 错误
- **不破坏**：`overwrite=false`（默认）行为完全不变，旧的"新增副本"逻辑保留

## Capabilities

### New Capabilities
- `file-upload-dedup`: 同名文件覆盖上传的端到端行为（FTP 删除 + DB 旧记录清理 + 新文件上传）

### Modified Capabilities
- （无现有 spec 需要修改；如果有 `file-upload` spec，需要新增覆盖场景的 delta，但当前没找到对应 spec，所以放在新 capability 里）

## Impact

- **后端**：
  - `FileUploadController.upload()` — 新增 `overwrite` 参数和覆盖分支
  - `UserFileMapper` — 新增 `deleteById` 或 `deleteByUserIdAndOriginalFileName`
  - `FtpFileService` — 已有 `deleteFile()` 可直接复用
  - `enabled_files` 等引用 `user_files.id` 的表需要级联清理（或在 controller 显式 delete）
- **前端**：
  - `MessageInput.vue` / `useFileUpload.ts` — 检测重复时弹确认框，确认后带 `overwrite=true` 上传
- **测试**：
  - 单测：覆盖上传后 `user_files` 表只剩 1 条
  - 单测：覆盖上传后 FTP 目录只有 1 个文件
  - 集成：用户 A 和用户 B 的同名文件互不影响
  - 集成：`overwrite=false` 行为完全不变（旧用例继续通过）
