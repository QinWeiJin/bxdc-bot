## ADDED Requirements

### Requirement: 异步任务通知中心时间字段按 Asia/Shanghai 序列化为 ISO 8601 字符串

异步任务通知中心（`/api/async-tasks/my`）返回的 `startedAt` / `completedAt` / `createdAt` / `notifiedAt` 字段，MUST 按 Asia/Shanghai 时区序列化为 ISO 8601 字符串（pattern `yyyy-MM-dd'T'HH:mm:ssXXX`，例：`2026-06-10T10:54:58+08:00`），由前端 `utils/datetime.ts` 的 `parseBackendTimeAsUtc` 反序列化为本地时间显示。

#### Scenario: 字段类型必须是 String 而不是 LocalDateTime

- **WHEN** `AsyncTaskNotificationDto.from(AsyncTask, ...)` 被调用
- **THEN** 时间字段（MUST）赋值 String 类型（不是 `java.time.LocalDateTime`）
- **AND**（MUST）通过 `formatShanghai(LocalDateTime)` 静态方法格式化输出
- **AND** null 时间字段（MUST）序列化为 JSON `null`（不是空字符串、不是 timestamp 数组）

#### Scenario: DTO 不依赖 Spring Boot auto-config 的 JavaTimeModule

- **WHEN** 项目以 Spring Boot 2.7+ 启动
- **THEN** `JacksonConfig`（MUST）提供 `@Primary @Bean ObjectMapper` 显式注册 `JavaTimeModule`
- **AND** 即使 `JavaTimeModule` 未生效（auto-config 失败场景），DTO 时间字段（MUST）依然能正常序列化（因为已经是 String）

### Requirement: Skill entity 时间字段按 Asia/Shanghai 格式化

`Skill` entity 的 `createdAt` / `updatedAt` 字段（MUST）加 `@JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss", timezone="Asia/Shanghai")` 注解，让 Jackson 走 JSR-310 serializer 输出 `"2026-06-01T10:33:40"` 格式字符串。pattern **不能**带 `XXX`（因为 `LocalDateTime` 本身不带 timezone offset，带 `XXX` 会触发 Jackson 的 `Unsupported field: OffsetSeconds` bug）。

#### Scenario: /api/skills 返回的 Skill 列表

- **WHEN** 调用 `GET /api/skills` 返回技能列表
- **THEN** 每个 Skill 的 `createdAt` / `updatedAt` 字段 MUST 输出 `"2026-06-01T10:33:40"` 格式（不含 `+08:00` offset）
- **AND**（MUST）HTTP 200，不是 500
