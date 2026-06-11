## Why

当前系统缺少统一的Excel文件解析能力，无法有效提取xls/xlsx/csv文件的结构化元数据信息（如sheet信息、列定义、数据类型推断等），影响了文件操作功能的完整性和用户体验。

## What Changes

- 新增 ExcelParser 组件，支持 xls、xlsx、csv 三种文件格式的解析
- 新增 FileParserRouter 统一路由层，根据文件扩展名分发到对应解析器
- 提取文件元数据：sheet数量、名称、行列数、表头信息、列类型推断（string/float/datetime/category）
- 支持数据预览功能（最多前10行数据）
- 修复测试类的Java 8兼容性问题

## Capabilities

### New Capabilities
- `excel-unified-parser`: 统一Excel文件解析能力，支持xls/xlsx/csv格式，提取结构化元数据

### Modified Capabilities
- `file-parser-router`: 扩展路由能力，新增csv文件支持

## Impact

- 修改文件：`ExcelParser.java`、`FileParserRouter.java`、`FileParseResult.java`
- 新增测试：`ExcelParserTest.java`、`FileParserRouterTest.java`
- 影响模块：文件操作服务（FileOperationService）
