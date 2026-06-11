## 1. 修复 HttpClient config 编译错误

- [x] 1.1 删除 `SkillGatewayHttpClientConfig.java` 中重复声明的 `noProxyRoutePlanner` 变量（line 32-37 那个用了 `SystemDefaultRoutePlanner`）
- [x] 1.2 验证保留的 `DefaultRoutePlanner(null)` 是 JDK 1.8 兼容写法（避开 `ProxySelector.of(null)` 这个 JDK 9+ API）
- [x] 1.3 `mvn compile` 验证编译通过

## 2. 修复 Jackson OffsetSeconds 序列化 bug

- [x] 2.1 新增 `config/JacksonConfig.java`：`@Primary @Bean ObjectMapper` 显式注册 `JavaTimeModule` + 自定义 `LocalDateTimeSerializer`（覆盖 Spring Boot auto-config，因为后者在本项目不生效）
- [x] 2.2 `dto/AsyncTaskNotificationDto.java` 4 个时间字段 `LocalDateTime` → `String`：`startedAt` / `completedAt` / `createdAt` / `notifiedAt`
- [x] 2.3 新增 `formatShanghai(LocalDateTime)` 静态方法，用 `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneId.of("Asia/Shanghai"))` 格式化
- [x] 2.4 `entity/Skill.java` `createdAt` / `updatedAt` 加 `@JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss", timezone="Asia/Shanghai")`（**不带 XXX**，因为 entity 字段是 LocalDateTime 不能有 offset）
- [x] 2.5 `entity/Skill.java` 加 `import com.fasterxml.jackson.annotation.JsonFormat`

## 3. 验证修复

- [x] 3.1 编译：`mvn -s ./settings.xml compile` BUILD SUCCESS
- [x] 3.2 启动 gateway：`Started SkillGatewayApplication in 9.5 seconds`
- [x] 3.3 `GET /api/skills` HTTP 200，`createdAt` 格式 `"2026-06-01T10:33:40"`
- [x] 3.4 `GET /api/async-tasks/my` HTTP 200，`startedAt` 格式 `"2026-06-10T10:54:58+08:00"`
- [x] 3.5 `GET /api/async-tasks/my/unread-count` HTTP 200

## 4. 提交

- [x] 4.1 `git add` 4 个 Java 文件（不 add application.properties 那个本地 MySQL 密码改动）
- [x] 4.2 commit `bd3baaa` `fix(gateway): Jackson OffsetSeconds 序列化修复 + HttpClient config 编译错误`
- [x] 4.3 force push 到 myfork/temp
