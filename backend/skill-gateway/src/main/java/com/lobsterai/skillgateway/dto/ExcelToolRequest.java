package com.lobsterai.skillgateway.dto;

import java.util.List;

/**
 * Excel 工具操作请求 DTO
 */
public class ExcelToolRequest {

    /** 文件 ID */
    private Long fileId;

    /** 用户 ID */
    private String userId;

    /** 文件名（新建文件时使用） */
    private String fileName;

    /** 操作类型：filter/sort/aggregate 等 */
    private String operation;

    /** 筛选条件列表 */
    private List<ExcelOperationResult.FilterCriteria> criteria;

    /** 排序条件列表 */
    private List<ExcelOperationResult.SortSpec> sortSpecs;

    /** 聚合条件列表 */
    private List<ExcelOperationResult.AggregationSpec> aggregations;

    /** 列选择列表 */
    private List<String> columns;

    /** 计算表达式列表 */
    private List<ExcelOperationResult.CalculateExpression> expressions;

    /** 校验规则列表 */
    private List<java.util.Map<String, Object>> rules;

    /** 清洗类型：trim/deduplicate/removeEmpty */
    private String cleanType;

    /** 目标格式：xlsx/xls/csv */
    private String targetFormat;

    /** 表头（用于写入操作） */
    private List<String> headers;

    /** 数据行（用于写入操作） */
    private List<List<Object>> rows;

    // ========== Getters & Setters ==========

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public List<ExcelOperationResult.FilterCriteria> getCriteria() {
        return criteria;
    }

    public void setCriteria(List<ExcelOperationResult.FilterCriteria> criteria) {
        this.criteria = criteria;
    }

    public List<ExcelOperationResult.SortSpec> getSortSpecs() {
        return sortSpecs;
    }

    public void setSortSpecs(List<ExcelOperationResult.SortSpec> sortSpecs) {
        this.sortSpecs = sortSpecs;
    }

    public List<ExcelOperationResult.AggregationSpec> getAggregations() {
        return aggregations;
    }

    public void setAggregations(List<ExcelOperationResult.AggregationSpec> aggregations) {
        this.aggregations = aggregations;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<ExcelOperationResult.CalculateExpression> getExpressions() {
        return expressions;
    }

    public void setExpressions(List<ExcelOperationResult.CalculateExpression> expressions) {
        this.expressions = expressions;
    }

    public List<java.util.Map<String, Object>> getRules() {
        return rules;
    }

    public void setRules(List<java.util.Map<String, Object>> rules) {
        this.rules = rules;
    }

    public String getCleanType() {
        return cleanType;
    }

    public void setCleanType(String cleanType) {
        this.cleanType = cleanType;
    }

    public String getTargetFormat() {
        return targetFormat;
    }

    public void setTargetFormat(String targetFormat) {
        this.targetFormat = targetFormat;
    }

    public List<String> getHeaders() {
        return headers;
    }

    public void setHeaders(List<String> headers) {
        this.headers = headers;
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    public void setRows(List<List<Object>> rows) {
        this.rows = rows;
    }
}
