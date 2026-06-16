## ADDED Requirements

### Requirement: 内置问好素材库
系统 SHALL 维护一个内置的中文欢迎语素材库（30 条），每条模板使用 `{nickname}` 和 `{avatar}` 占位符表示用户名和头像 emoji。素材库 MUST 不依赖 LLM、数据库或外部服务。

#### Scenario: 素材库存在且可访问
- **WHEN** `AvatarService.generateGreeting()` 被调用
- **THEN** 系统从素材库中直接获取模板
- **AND** 不发起任何网络请求或 LLM 调用

### Requirement: 随机选取问好模板
系统 SHALL 从素材库中随机选取一条模板，使用 `Math.random()` 均匀分布。

#### Scenario: 随机选取模板
- **WHEN** `generateGreeting(nickname, avatar)` 被调用
- **THEN** 系统从素材库中等概率随机选取一条模板
- **AND** 将模板中的 `{nickname}` 替换为实际昵称
- **AND** 将模板中的 `{avatar}` 替换为实际头像 emoji

#### Scenario: 昵称为空时的兜底
- **WHEN** `nickname` 为空字符串或 null/undefined
- **THEN** 系统返回固定欢迎语 `"欢迎回来！"`（不经过模板选取）

### Requirement: 问好同步即时返回
`generateGreeting()` SHALL 以同步方式完成并立即返回（无需异步等待），确保新对话建立后欢迎语无延迟展示。

#### Scenario: 即时返回无延迟
- **WHEN** `generateGreeting(nickname, avatar)` 被调用
- **THEN** 结果在 1ms 级别内返回
- **AND** 不包含任何 `await` 长耗时操作（网络、IO 等）
