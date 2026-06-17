## Context

当前系统需要统一的Excel文件解析能力，支持xls、xlsx、csv三种格式，提取结构化元数据信息。

## Goals / Non-Goals

**Goals:**
- 支持 xls、xlsx、csv 文件格式的解析
- 提取文件元数据（sheet信息、列定义、数据类型推断）
- 提供数据预览功能（最多前10行）
- 与现有文件解析架构集成

**Non-Goals:**
- 不支持Excel公式计算
- 不支持宏执行
- 不支持加密文件解密

## Decisions

1. **解析器架构**: 采用策略模式，通过 FileParserRouter 根据文件扩展名分发到对应解析器
2. **Excel解析库**: 使用 Apache POI 4.1.2 作为解析引擎，支持 xls (HSSF) 和 xlsx (XSSF)
3. **CSV解析**: 自定义解析逻辑，支持带引号字段和自动类型推断
4. **列类型推断**: 通过采样前20行数据自动推断类型（string/float/datetime/category）
5. **Java版本兼容**: 代码遵循 Java 8 语法规范，不使用 var、List.of()、String.formatted() 等特性

## Risks / Trade-offs

- **大文件内存占用**: 使用流式解析减少内存压力，但对于超大文件仍需考虑分片处理
- **CSV编码检测**: 默认使用UTF-8，特殊编码可能需要额外处理
- **日期格式兼容性**: 仅支持常见日期格式（yyyy-MM-dd、MM/dd/yyyy等）
