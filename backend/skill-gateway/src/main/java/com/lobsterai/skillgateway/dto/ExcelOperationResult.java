package com.lobsterai.skillgateway.dto;

import java.util.List;
import java.util.Map;

public class ExcelOperationResult {

    private boolean success;
    private String sessionId;
    private String operation;
    private SheetData result;
    private String downloadUrl;
    private String message;
    private List<String> errors;
    private Integer rowCount;
    private Integer colCount;

    public ExcelOperationResult() {
    }

    public static ExcelOperationResult success(String operation, SheetData result) {
        ExcelOperationResult r = new ExcelOperationResult();
        r.success = true;
        r.operation = operation;
        r.result = result;
        if (result != null) {
            r.rowCount = result.getRows() != null ? result.getRows().size() : 0;
            r.colCount = result.getColumns() != null ? result.getColumns().size() : 0;
        }
        return r;
    }

    public static ExcelOperationResult successWithFile(String operation, String downloadUrl) {
        ExcelOperationResult r = new ExcelOperationResult();
        r.success = true;
        r.operation = operation;
        r.downloadUrl = downloadUrl;
        return r;
    }

    public static ExcelOperationResult failure(String operation, String message) {
        ExcelOperationResult r = new ExcelOperationResult();
        r.success = false;
        r.operation = operation;
        r.message = message;
        return r;
    }

    public static ExcelOperationResult failure(String operation, List<String> errors) {
        ExcelOperationResult r = new ExcelOperationResult();
        r.success = false;
        r.operation = operation;
        r.errors = errors;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public SheetData getResult() {
        return result;
    }

    public void setResult(SheetData result) {
        this.result = result;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public Integer getColCount() {
        return colCount;
    }

    public void setColCount(Integer colCount) {
        this.colCount = colCount;
    }

    public static class SheetData {
        private String sheetName;
        private List<String> columns;
        private List<List<Object>> rows;

        public SheetData() {
        }

        public SheetData(String sheetName, List<String> columns, List<List<Object>> rows) {
            this.sheetName = sheetName;
            this.columns = columns;
            this.rows = rows;
        }

        public String getSheetName() {
            return sheetName;
        }

        public void setSheetName(String sheetName) {
            this.sheetName = sheetName;
        }

        public List<String> getColumns() {
            return columns;
        }

        public void setColumns(List<String> columns) {
            this.columns = columns;
        }

        public List<List<Object>> getRows() {
            return rows;
        }

        public void setRows(List<List<Object>> rows) {
            this.rows = rows;
        }
    }

    public static class FilterCriteria {
        private String column;
        private String operator;
        private Object value;

        public FilterCriteria() {
        }

        public FilterCriteria(String column, String operator, Object value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }
    }

    public static class SortSpec {
        private String column;
        private String order;

        public SortSpec() {
        }

        public SortSpec(String column, String order) {
            this.column = column;
            this.order = order;
        }

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public String getOrder() {
            return order;
        }

        public void setOrder(String order) {
            this.order = order;
        }
    }

    public static class AggregationSpec {
        private String column;
        private String function;

        public AggregationSpec() {
        }

        public AggregationSpec(String column, String function) {
            this.column = column;
            this.function = function;
        }

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public String getFunction() {
            return function;
        }

        public void setFunction(String function) {
            this.function = function;
        }
    }

    public static class CalculateExpression {
        private String newColumn;
        private String expression;

        public CalculateExpression() {
        }

        public CalculateExpression(String newColumn, String expression) {
            this.newColumn = newColumn;
            this.expression = expression;
        }

        public String getNewColumn() {
            return newColumn;
        }

        public void setNewColumn(String newColumn) {
            this.newColumn = newColumn;
        }

        public String getExpression() {
            return expression;
        }

        public void setExpression(String expression) {
            this.expression = expression;
        }
    }

    public static class ValidationResult {
        private boolean valid;
        private List<ValidationError> errors;

        public ValidationResult() {
        }

        public boolean isValid() {
            return valid;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public List<ValidationError> getErrors() {
            return errors;
        }

        public void setErrors(List<ValidationError> errors) {
            this.errors = errors;
        }
    }

    public static class ValidationError {
        private int rowIndex;
        private String column;
        private String message;

        public ValidationError() {
        }

        public ValidationError(int rowIndex, String column, String message) {
            this.rowIndex = rowIndex;
            this.column = column;
            this.message = message;
        }

        public int getRowIndex() {
            return rowIndex;
        }

        public void setRowIndex(int rowIndex) {
            this.rowIndex = rowIndex;
        }

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}