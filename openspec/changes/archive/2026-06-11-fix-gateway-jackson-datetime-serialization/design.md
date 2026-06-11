## 设计

### Jackson 序列化问题分析

`LocalDateTime` 序列化有 3 种路径，**只有 JavaTimeModule 注册的 LocalDateTimeSerializer 稳定**：

| 路径 | LocalDateTime 怎么被序列化 | @JsonFormat(timezone, pattern) 行为 |
|------|---------------------------|-------------------------------------|
| **有 JavaTimeModule** ✓ | `LocalDateTimeSerializer` 用 formatter 格式化为字符串 | 正常 |
| **没 JavaTimeModule** ❌ | bean serializer 兜底，调用 `getYear/getMonth/getHour/getOffset` 等所有 getter | @JsonFormat 注解被忽略，输出结构化对象 |
| **JavaTimeModule 缺失 + @JsonFormat(pattern="XXX")** ❌❌ | `getOffset()` 返回 `ZoneOffset`，ZoneOffset 序列化为 `{totalSeconds, id, rules}`，其中 `offsetSeconds` 字段在 Jackson 2.13.3 中不被识别 | 触发 `Unsupported field: OffsetSeconds` |

**Spring Boot 2.7+ 理论上自动注册 JavaTimeModule**，但本项目实测不生效（`jackson-datatype-jsr310-2.13.3.jar` 在 classpath，但 `JacksonAutoConfiguration` 没注册成功）。原因待深挖（可能跟 spring-boot-starter-json 的模块 SPI 配置有关），但绕过办法是显式 `@Primary @Bean ObjectMapper`。

### 双层修复策略

| 层级 | 修改 | 原因 |
|------|------|------|
| **ObjectMapper**（全局） | 新增 `JacksonConfig.java` 显式 `@Primary @Bean ObjectMapper` | 兜底：保证 JavaTimeModule 真正生效，所有 DTO/Entity 都能用 JSR-310 serializer |
| **DTO 字段类型**（具体到 AsyncTaskNotificationDto） | `LocalDateTime` → `String` + `formatShanghai()` 静态方法 | 绕过 `@JsonFormat(pattern="XXX")` 的 bug（即使 JavaTimeModule 注册了，XXX + LocalDateTime 也不稳定）|
| **Entity 字段注解**（具体到 Skill） | 加 `@JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss", timezone="Asia/Shanghai")`（**不带 XXX**）| entity 字段是 `LocalDateTime`，不能有 offset，所以 pattern 不带 XXX |

### HttpClient 编译错误分析

`SkillGatewayHttpClientConfig.java` 同事写了 2 个 `noProxyRoutePlanner` 变量（line 35 + line 44）：

```java
// line 32-37（错误）
HttpRoutePlanner noProxyRoutePlanner = new SystemDefaultRoutePlanner(new ProxySelector() {...});
//  ↑ SystemDefaultRoutePlanner 是 org.apache.http.impl.conn.SystemDefaultRoutePlanner
//    仅在 HttpClient 5.x 存在，spring-web 2.7.3 自带 4.5.13（4.x）没有

// line 41-44（正确）
HttpRoutePlanner noProxyRoutePlanner = new DefaultRoutePlanner(null);
//  ↑ DefaultRoutePlanner(null) 等价于"不通过任何代理直连"
//    用 null HttpHost 是 JDK 1.8 兼容写法（避开 JDK 9+ 的 ProxySelector.of(null)）
```

修复：删 line 32-37，保留 line 41-44。

### 序列化格式约定

| 字段类型 | 序列化格式 | 示例 |
|----------|-----------|------|
| DTO 字段（`AsyncTaskNotificationDto`）| `"yyyy-MM-dd'T'HH:mm:ssXXX"` 带 `+08:00` | `"2026-06-10T10:54:58+08:00"` |
| Entity 字段（`Skill`）| `"yyyy-MM-dd'T'HH:mm:ss"` 不带 offset | `"2026-06-01T10:33:40"` |

不一致是**有意为之**：
- DTO 是给前端通知中心用的，前端 `parseBackendTimeAsUtc` 按 Asia/Shanghai 解析
- Entity 字段是给 LLM tool 列表用的，LLM 只需要"创建时间"概念，不在意时区

### 文件改动详情

| 文件 | + | − | 改动 |
|------|---|---|------|
| `config/JacksonConfig.java` | 39 | 0 | **新增**（`@Primary @Bean ObjectMapper`）|
| `config/SkillGatewayHttpClientConfig.java` | 0 | 7 | 删 1 个重复变量 + 1 个错误 import |
| `dto/AsyncTaskNotificationDto.java` | 27 | 32 | 4 个字段 `LocalDateTime` → `String`，加 `formatShanghai` 静态方法 |
| `entity/Skill.java` | 3 | 0 | 加 `@JsonFormat` 注解 + 1 行 import |
| **合计** | **69** | **39** | 4 个文件 |

### 未来 Java 9+ 升级路径

本项目用 JDK 1.8 编译目标（AGENTS.md 7.4 规约）。如果将来升级到 JDK 11+：
1. `DefaultRoutePlanner(null)` 可改为 `ProxySelector.of(null)`（JDK 9+ API）
2. `LocalDateTime` 序列化在 JDK 9+ + JavaTimeModule 下通常稳定，可考虑回退 `AsyncTaskNotificationDto` 字段类型为 `LocalDateTime`
3. `Skill.java` 字段的 `@JsonFormat` 可加 `XXX` 改成带 offset

升级需团队评审（AGENTS.md 7.4）。
