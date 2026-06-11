## 1. ExcelParser 实现

- [x] 1.1 实现 xlsx 文件解析（XSSFWorkbook）
- [x] 1.2 实现 xls 文件解析（HSSFWorkbook）
- [x] 1.3 实现 csv 文件解析（自定义解析逻辑）
- [x] 1.4 实现列类型推断（string/float/datetime/category）

## 2. FileParserRouter 集成

- [x] 2.1 注册 ExcelParser 到解析器路由
- [x] 2.2 支持 xls、xlsx、csv 文件扩展名

## 3. DTO 定义

- [x] 3.1 定义 FileParseResult DTO
- [x] 3.2 定义 SheetInfo 内部类
- [x] 3.3 定义 ColumnInfo 内部类

## 4. 测试编写

- [x] 4.1 编写 ExcelParserTest 测试类
- [x] 4.2 编写 FileParserRouterTest 测试类
- [x] 4.3 修复 Java 8 兼容性问题（String.formatted → String.format）

## 5. 验证

- [x] 5.1 运行所有测试验证功能
- [x] 5.2 修复测试中发现的问题
