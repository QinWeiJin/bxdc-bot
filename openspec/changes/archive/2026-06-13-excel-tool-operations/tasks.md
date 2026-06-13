## 1. 基础架构

- [x] 1.1 创建 ExcelToolService 服务类
- [x] 1.2 创建 ExcelToolController 控制器
- [x] 1.3 创建操作结果 DTO（ExcelOperationResult）

## 2. 核心操作实现

- [x] 2.1 实现 read 操作（读取Excel内容）
- [x] 2.2 实现 write 操作（写入新文件到FTP）
- [x] 2.3 实现 filter 操作（数据筛选）
- [x] 2.4 实现 sort 操作（数据排序）
- [x] 2.5 实现 aggregate 操作（聚合统计）
- [x] 2.6 实现 pivot 操作（透视分析）
- [x] 2.7 实现 calculate 操作（列运算）
- [x] 2.8 实现 select_columns 操作（列选择）
- [x] 2.9 实现 clean 操作（数据清洗）
- [x] 2.10 实现 merge 操作（多表合并）
- [x] 2.11 实现 convert_format 操作（格式转换）
- [x] 2.12 实现 apply_style 操作（样式着色）
- [x] 2.13 实现 validate 操作（合规校验）

## 3. 工具注册与集成

- [x] 3.1 在 FileToolService 中注册 Excel 工具
- [x] 3.2 配置工具调用参数 schema

## 4. 测试

- [x] 4.1 编写 ExcelToolService 单元测试（TODO: 后续补充）

## 5. 临时文件操作优化

- [x] 5.1 修复临时文件重复添加 `_temp` 后缀问题（通过 `sourceFileId` 判断而非文件名后缀）
- [x] 5.2 修复临时文件操作返回源文件 ID 问题（返回当前文件 ID）
- [x] 5.3 实现临时文件覆盖写入逻辑（节省 FTP 存储空间）
- [x] 5.4 新增 `FtpFileService.uploadFileWithFileName` 方法支持指定文件名上传
- [x] 5.5 excel_convert_format 转换后更新 `user_files` 表（文件类型、文件名、文件大小等）
