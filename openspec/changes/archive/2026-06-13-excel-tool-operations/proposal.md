## Why

当前系统已具备 Excel 文件元数据解析能力，但缺少完整的数据操作能力。模型需要能够对 Excel 文件进行各种数据处理操作（筛选、排序、聚合等），并支持多步混合操作后将结果写入新文件。

## What Changes

- 实现 Excel 文件的原子性工具操作，封装为适合模型调用的工具
- 支持的操作包括：读、写、筛选、排序、聚合统计、透视分析、列运算、列选择、数据清洗、多表合并、格式转换、条件格式/样式着色、合规校验
- 支持模型多步混合操作，最终结果写入新文件并返回下载链接

## Capabilities

### New Capabilities
- `excel-tool-operations`: Excel 文件原子性操作工具集

### Modified Capabilities
- `excel-unified-parser`: 扩展解析能力，支持数据操作后的结果生成

## Impact

- 新增文件：ExcelToolService.java、ExcelToolController.java、相关 DTO
- 修改文件：FileToolService.java（集成新工具）
- 影响模块：文件操作服务、智能文件中心
