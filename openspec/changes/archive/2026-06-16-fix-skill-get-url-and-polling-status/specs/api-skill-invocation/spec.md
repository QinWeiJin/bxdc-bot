## ADDED Requirements

### Requirement: API Skill query 参数 URL 必须且仅能编码一次

对扩展 API Skill，在 `parameterContract` 校验通过且即将调用 Skill Gateway 的 HTTP 代理前，系统 MUST 对 query 参数的 key / value 各做**且仅做一次** `application/x-www-form-urlencoded` 编码（按 RFC 3986 percent-encoding）。

禁止在同一拼接循环中对同一 key 或 value 重复编码；禁止在已编码字符串上再做一次 percent-encoding。

#### Scenario: 普通标量参数拼接
- **WHEN** LLM 传入 query 参数 `{city: "北京", page: 1}`（skill config `endpoint` = `https://api.example.com/search`）
- **THEN** 系统 MUST 拼接出 `https://api.example.com/search?city=%E5%8C%97%E4%BA%AC&page=1`
- **AND** URL 中 MUST NOT 出现重复编码片段（如 `city=%E5%8C%97%E4%BA%ACcity=%25E5%258C%2597%25E4%25BA%25AC`）

#### Scenario: 含特殊字符的参数
- **WHEN** LLM 传入 query 参数 `{q: "hello world&foo=bar", tag: "a b"}`
- **THEN** 系统 MUST 将空格编码为 `%20` 或 `+`（按规范统一即可），将 `&`、`=` 编码为 `%26` / `%3D`
- **AND** 系统 MUST NOT 把同一 `&` / `=` 编码两次

#### Scenario: 端点本身已带 query 串
- **WHEN** skill config `endpoint` 已含 `?token=xxx`（如 `https://api.example.com/search?token=xxx`）
- **AND** LLM 传入额外 query 参数 `{q: "test"}`
- **THEN** 系统 MUST 拼出 `https://api.example.com/search?token=xxx&q=test`（用 `&` 连接，不引入 `??`）
- **AND** 已存在的 `token=xxx` 部分 MUST NOT 被重复编码

#### Scenario: 回归保护（之前双重编码 bug）
- **WHEN** 修复前的版本在 `{city: "北京"}` 入参上拼接
- **THEN** 修复前 MUST 输出 `https://api.example.com/search?city=%E5%8C%97%E4%BA%ACcity=%25E5%258C%2597%25E4%25BA%25AC`（双重编码 bug）
- **AND** 修复后 MUST 输出 `https://api.example.com/search?city=%E5%8C%97%E4%BA%AC`（单次编码）
- **AND** 后端（FastAPI / Spring 接收方）解析时 MUST 能正确拿到 `city=北京`
