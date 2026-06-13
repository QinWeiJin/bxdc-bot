## Context

当前系统需要为模型提供完整的 Excel 文件操作能力，支持读、写、筛选、排序、聚合等原子操作，并支持多步混合操作。

## Goals / Non-Goals

**Goals:**
- 实现 12 种 Excel 原子操作工具
- 支持 xls、xlsx、csv 三种格式
- 工具封装适合模型调用（JSON 格式输入输出）
- 支持多步操作链式调用
- 结果写入 FTP 并返回下载链接

**Non-Goals:**
- 不支持宏执行
- 不支持复杂图表操作

## Decisions

1. **架构模式**: 采用工具服务模式，每个操作作为独立方法，统一通过 ExcelToolService 暴露
2. **数据模型**: 使用 Apache POI 的 Sheet/Row/Cell 模型进行内存操作
3. **状态管理**: 支持操作链状态保持，通过 sessionId 关联多步操作
4. **文件存储**: 操作结果写入 FTP，生成唯一文件名，返回下载 URL
5. **工具注册**: 在 FileToolService 中注册为系统工具，供模型调用

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     ExcelToolService                        │
├─────────────────────────────────────────────────────────────┤
│  read(fileId)         → 返回文件内容JSON                    │
│  write(data, fileName) → 返回新文件下载URL                  │
│  filter(fileId, criteria) → 返回过滤后数据                 │
│  sort(fileId, columns, orders) → 返回排序后数据             │
│  aggregate(fileId, groupBy, aggregations) → 聚合结果        │
│  pivot(fileId, rows, cols, values) → 透视表结果            │
│  calculate(fileId, expressions) → 列运算结果               │
│  select_columns(fileId, columns) → 返回指定列              │
│  clean(fileId, rules) → 数据清洗结果                       │
│  merge(fileIds, joinKey) → 多表合并结果                    │
│  convert_format(fileId, format) → 格式转换                 │
│  apply_style(fileId, styles) → 应用样式后的文件            │
│  validate(fileId, rules) → 合规校验结果                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    FileToolService (注册)                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    FTP File Storage                         │
└─────────────────────────────────────────────────────────────┘
```

## Risks / Trade-offs

- **内存占用**: 大文件操作可能占用较多内存，需限制单文件最大大小
- **并发安全**: 多用户同时操作时需保证 session 隔离
- **操作链管理**: 需定期清理过期的中间状态

## API 设计

### 工具调用格式
```json
{
  "tool_name": "excel_operation",
  "params": {
    "operation": "filter",
    "file_id": "123",
    "session_id": "abc-123",
    "args": {
      "column": "status",
      "operator": "equals",
      "value": "正常"
    }
  }
}
```

### 响应格式
```json
{
  "success": true,
  "session_id": "abc-123",
  "result": { ... },
  "download_url": "http://..."
}
```
