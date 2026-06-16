---
name: "agent-browser"
description: "使用 agent-browser CLI 工具进行浏览器自动化测试。专为 AI Agent 设计，通过 CLI 命令操作浏览器，支持 ref-based 元素选择。当用户要求浏览器测试、预览网页、或代码修改后需要验证效果时触发。"
---

# Agent Browser 自动化测试

这个 skill 使用 [agent-browser](https://github.com/vercel-labs/agent-browser) 开源工具，提供浏览器自动化能力。agent-browser 是由 Vercel Labs 开发的、专为 AI Agent 设计的浏览器自动化 CLI 工具，使用 Rust 编写，通过命令行操作浏览器。

## 主要功能

1. **自动化浏览器测试** - 通过 CLI 命令进行端到端测试
2. **网页预览** - 打开浏览器预览本地开发服务器
3. **UI交互验证** - 自动化点击、输入、截图等操作
4. **网页抓取** - 提取页面内容、数据采集
5. **Ref-based 元素选择** - 使用 `@e1`, `@e2` 等引用选择元素，无需复杂选择器

## 核心特性

- **Agent-first 设计** - 紧凑的文本输出，专为 AI Agent 优化
- **Ref-based 交互** - snapshot 返回无障碍访问树，每个元素有唯一 ref
- **100% 原生 Rust** - 无需 Node.js 运行时，安装体积仅 7MB
- **150+ 命令** - 支持导航、表单、截图、网络、存储等所有操作
- **跨平台支持** - macOS、Linux、Windows 原生二进制

## 触发条件

此 skill 会在以下情况下被触发：

- ✅ 用户主动要求时（如"打开浏览器"、"预览网页"、"测试页面"）
- ✅ 代码修改后需要验证效果时
- ✅ 需要进行UI自动化测试时
- ✅ 需要抓取网页数据时

## 使用示例

### 示例 1：基本工作流程

```bash
# 1. 打开页面
agent-browser open http://localhost:3456/mock.html

# 2. 获取页面快照（查看可交互元素）
agent-browser snapshot
# 输出：
# - button "📋 全部请求日志" [ref=e5]
# - button "+ 创建端点" [ref=e6]

# 3. 使用 ref 点击元素
agent-browser click @e5

# 4. 截图保存
agent-browser screenshot result.png

# 5. 关闭浏览器
agent-browser close
```

### 示例 2：表单交互

```bash
# 打开页面
agent-browser open http://example.com/form

# 获取快照
agent-browser snapshot

# 输入文本
agent-browser type @e1 "username"
agent-browser type @e2 "password"

# 点击提交按钮
agent-browser click @e3

# 等待结果
agent-browser wait 2000

# 截图验证
agent-browser screenshot form-submitted.png
```

### 示例 3：自动化UI测试

```bash
# 打开登录页面
agent-browser open http://localhost:3456/login

# 获取快照
agent-browser snapshot

# 填充表单
agent-browser type @username "admin"
agent-browser type @password "123456"

# 点击登录
agent-browser click @login-btn

# 验证结果
agent-browser snapshot | grep "欢迎"

# 截图
agent-browser screenshot login-success.png
```

## 安装方法

### 方式 1：npm 全局安装（推荐）

```bash
npm install -g agent-browser
```

### 方式 2：Homebrew（macOS）

```bash
brew install agent-browser
```

### 方式 3：npx（无需安装）

```bash
npx agent-browser open example.com
```

### 首次使用

安装后首次使用需要初始化：

```bash
# 创建配置目录
mkdir -p ~/.agent-browser

# 如果需要下载 Chrome for Testing
agent-browser install
```

## 技术实现

此 skill 使用以下技术：

- **agent-browser CLI** - Vercel Labs 开发的浏览器自动化工具
- **Chrome DevTools Protocol (CDP)** - 与 Chrome 浏览器通信
- **Rust** - 100% 原生 Rust 实现，无需 Node.js 运行时
- **Client-Daemon 架构** - CLI 发送命令给后台守护进程，守护进程通过 CDP WebSocket 与浏览器通信

## 核心命令

| 命令 | 说明 | 示例 |
|------|------|------|
| `open <url>` | 打开页面 | `agent-browser open http://localhost:3456` |
| `snapshot` | 获取页面快照（返回元素 ref） | `agent-browser snapshot` |
| `click <ref>` | 点击元素 | `agent-browser click @e5` |
| `type <ref> <text>` | 输入文本 | `agent-browser type @e1 "hello"` |
| `select <ref> <value>` | 选择下拉框选项 | `agent-browser select @e27 "普通 Mock"` |
| `screenshot [path]` | 截图 | `agent-browser screenshot test.png` |
| `wait <ms\|ref>` | 等待时间或元素 | `agent-browser wait 2000` |
| `close` | 关闭浏览器 | `agent-browser close` |
| `doctor` | 诊断安装问题 | `agent-browser doctor` |

## 注意事项

1. **首次使用** - 需要创建 `~/.agent-browser` 目录
2. **Chrome 安装** - agent-browser 会自动检测系统 Chrome 或下载 Chrome for Testing
3. **权限问题** - 如果遇到权限错误，手动创建配置目录：`mkdir -p ~/.agent-browser`
4. **守护进程** - agent-browser 使用后台守护进程，在命令之间保持活跃
5. **Ref 选择器** - 使用 `snapshot` 获取元素 ref，然后用 `@e1`, `@e2` 等引用

## 最佳实践

1. **先获取快照** - 使用 `snapshot` 命令查看页面元素和 ref
2. **使用 ref 选择器** - 用 `@e1`, `@e2` 等引用，比 CSS 选择器更稳定
3. **适当等待** - 在操作之间使用 `wait` 确保页面加载完成
4. **截图验证** - 关键步骤后截图保存证据
5. **错误诊断** - 遇到问题时使用 `agent-browser doctor` 诊断

## 与 Puppeteer/Playwright 的对比

| 特性 | agent-browser | Puppeteer/Playwright |
|------|--------------|---------------------|
| 运行时 | 无需 Node.js | 需要 Node.js |
| 安装体积 | 7 MB | 100+ MB |
| 内存占用 | 8 MB | 150+ MB |
| 启动速度 | 617ms | 1000ms+ |
| 输出格式 | 紧凑文本 | JSON/HTML |
| 元素选择 | Ref-based (@e1) | CSS/XPath |
| AI 友好度 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |

## 参考资源

- [官方文档](https://agent-browser.dev/)
- [GitHub 仓库](https://github.com/vercel-labs/agent-browser)
- [npm 包](https://www.npmjs.com/package/agent-browser)
- [核心使用指南](https://agent-browser.dev/skills/core)
