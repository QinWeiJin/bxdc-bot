## Why

skill-gateway 在同事 `intelligent-file-center` commit（aaec3b5/65e2982）合并后，`/api/skills` 和 `/api/async-tasks/my` 接口返回 HTTP 500，错误：

```
HttpMessageNotWritableException: Could not write JSON: Unsupported field: OffsetSeconds
through reference chain: ...AsyncTaskNotificationDto["startedAt"]
```

根因是 Jackson 2.13.3 + `JavaTimeModule` + `@JsonFormat(pattern="...XXX", timezone="Asia/Shanghai")` 在 `LocalDateTime` 上有兼容性问题：
- pattern 里的 `XXX` 需要 timezone offset（`+08:00`）
- `LocalDateTime` 本身没有 timezone offset
- Jackson 走 bean serializer 兜底，调用 `getOffset()`（`TemporalAccessor` 接口），得到 `ZoneOffset` 序列化又失败
- `XXX` + `LocalDateTime` 这个组合无法稳定序列化

外加同事 `SkillGatewayHttpClientConfig.java` 用了一个 HttpClient 5.x 的类（`SystemDefaultRoutePlanner` 在 spring-web 2.7 自带的 4.x 不存在），并且重复声明了 `noProxyRoutePlanner` 变量，导致 gateway **无法编译**。

## What Changes

修复 4 个 Java 文件的 Jackson 序列化问题，**不改产品功能**，只修复序列化层稳定性：

- `dto/AsyncTaskNotificationDto.java` — 时间字段 `LocalDateTime` → `String`，在 `.from()` 中用 `formatShanghai(LocalDateTime)` 静态方法格式化为 `"yyyy-MM-dd'T'HH:mm:ssXXX"`（如 `2024-01-15T10:30:00+08:00`）
- `entity/Skill.java` — 给 `createdAt` / `updatedAt` 加 `@JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss", timezone="Asia/Shanghai")`（**不带 XXX**，因为 entity 字段是 `LocalDateTime` 不能有 offset）
- `config/JacksonConfig.java`（新增）— `@Primary @Bean ObjectMapper` 显式注册 `JavaTimeModule` + 自定义 `LocalDateTimeSerializer`，覆盖 Spring Boot auto-config（因为 auto-config 的 `JavaTimeModule` 在本项目不生效）
- `config/SkillGatewayHttpClientConfig.java` — 删除重复的 `noProxyRoutePlanner` 声明 + 错误 import（`SystemDefaultRoutePlanner` 是 HttpClient 5.x API）；保留 `DefaultRoutePlanner(null)` 作为 JDK 1.8 兼容方案

## Capabilities

### Modified Capabilities

- `api-extension-skill-llm-tool-call`: 异步任务通知中心返回的时间字段格式由 LocalDateTime timestamp 数组改为 Asia/Shanghai 字符串（前端 utils/datetime.ts 已支持解析）

## Impact

- **后端 skill-gateway**: 4 个 Java 文件（1 新增 + 3 修改），启动时间 9.5s 不变
- **后端 agent-core**: 不变
- **前端 frontend**: 不变（`MessageInput.vue` / `NotificationCenter.vue` 等组件无修改）
- **数据库**: 不变
- **配置**: 不变（application.properties 未 commit 的 MySQL 密码改动是本地环境配置，跟此 fix 无关）

测试验证：
- `GET /api/skills` → HTTP 200，`createdAt/updatedAt` 格式 `"2026-06-01T10:33:40"`
- `GET /api/async-tasks/my` → HTTP 200，`startedAt` 格式 `"2026-06-10T10:54:58+08:00"`
- `GET /api/async-tasks/my/unread-count` → HTTP 200
