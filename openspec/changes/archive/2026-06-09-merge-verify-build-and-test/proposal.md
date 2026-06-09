## Why

合并方案 Step 1-3 已完成（搬运新文件、无冲突修改、7 个冲突文件手动融合），需执行 Step 4（构建验证）和 Step 5（运行测试）来确认合并后的代码在三个子系统（Java / TypeScript / 前端）中均能正常编译和运行，确保没有遗漏 import、类型不匹配或运行时错误。

## What Changes

- 执行 Maven 编译验证 Java 模块无编译错误（skill-gateway）
- 执行 TypeScript 类型检查验证 agent-core 类型安全
- 执行 Vite 前端构建验证前端包完整
- 执行前端单元测试
- 执行后端 Maven 测试
- 记录编译/构建/测试过程中的任何错误并修复

## Capabilities

### New Capabilities
- `merge-build-verification`: 三步编译构建验证（Java / TypeScript / 前端），确保合并后零编译错误
- `merge-test-verification`: 两步测试验证（agent-core / skill-gateway），确保合并后测试全部通过

### Modified Capabilities
<!-- None - this is a verification task, not a requirement change -->

## Impact

- `backend/skill-gateway/` — Maven 编译 + 单元测试
- `backend/agent-core/` — TypeScript 编译 + 单元测试
- `frontend/` — Vite 生产构建
- 若编译/测试失败，可能需要修复 Step 1-3 搬运的任意文件
