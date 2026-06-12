# user-profile Specification (delta)

## ADDED Requirements

### Requirement: 资料编辑页承载记忆管理入口

资料编辑弹窗 MUST 在 emoji 头像选择器下方承载记忆管理入口（详见 `memory-initialization-flow` 规范），受 `MEM0_ENABLED` 配置开关控制；记忆关闭时整个入口 MUST 不渲染（不是 disabled 也不是灰显，是**完全不出现**）。

#### Scenario: 记忆开启时入口可见
- **WHEN** `MEM0_ENABLED=true` 且用户已登录并打开资料编辑
- **THEN** 弹窗 emoji 网格下方显示「清除记忆」入口

#### Scenario: 记忆关闭时入口隐藏
- **WHEN** `MEM0_ENABLED=false`
- **THEN** 弹窗中**不出现**「清除记忆」入口及任何相关按钮
- **AND** 弹窗布局不为此预留空白

#### Scenario: 与其他资料字段的视觉关系
- **WHEN** 「清除记忆」入口可见
- **THEN** 入口 MUST NOT 占用与昵称 / 头像 / 预览区相同的视觉权重
- **AND** MUST NOT 使用主色或边框，仅在 hover / focus 时显示 danger 色
- **AND** MUST 位于弹窗 footer（取消 / 保存）**之上**
