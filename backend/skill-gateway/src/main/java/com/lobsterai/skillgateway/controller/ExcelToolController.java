package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.dto.ExcelOperationResult;
import com.lobsterai.skillgateway.dto.ExcelOperationResult.*;
import com.lobsterai.skillgateway.service.ExcelToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/excel-tool")
public class ExcelToolController {

    private static final Logger log = LoggerFactory.getLogger(ExcelToolController.class);

    private final ExcelToolService excelToolService;

    public ExcelToolController(ExcelToolService excelToolService) {
        this.excelToolService = excelToolService;
    }

    @GetMapping("/read")
    public ResponseEntity<ExcelOperationResult> read(
            @RequestParam Long fileId,
            @RequestParam String userId) {
        log.info("Excel tool operation: read, fileId={}, userId={}", fileId, userId);
        ExcelOperationResult result = excelToolService.read(fileId, userId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/write")
    public ResponseEntity<ExcelOperationResult> write(
            @RequestBody SheetData sheetData,
            @RequestParam String fileName,
            @RequestParam String userId) {
        log.info("Excel tool operation: write, fileName={}, userId={}", fileName, userId);
        ExcelOperationResult result = excelToolService.write(sheetData, fileName, userId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/filter")
    public ResponseEntity<ExcelOperationResult> filter(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestBody List<FilterCriteria> criteria) {
        log.info("Excel tool operation: filter, fileId={}, userId={}", fileId, userId);
        ExcelOperationResult result = excelToolService.filter(fileId, userId, criteria);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sort")
    public ResponseEntity<ExcelOperationResult> sort(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestBody List<SortSpec> sortSpecs) {
        log.info("Excel tool operation: sort, fileId={}, userId={}", fileId, userId);
        ExcelOperationResult result = excelToolService.sort(fileId, userId, sortSpecs);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/aggregate")
    public ResponseEntity<ExcelOperationResult> aggregate(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestParam String groupBy,
            @RequestBody List<AggregationSpec> aggregations) {
        log.info("Excel tool operation: aggregate, fileId={}, userId={}, groupBy={}", fileId, userId, groupBy);
        ExcelOperationResult result = excelToolService.aggregate(fileId, userId, groupBy, aggregations);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/pivot")
    public ResponseEntity<ExcelOperationResult> pivot(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestParam String rowField,
            @RequestParam String colField,
            @RequestParam String valueField) {
        log.info("Excel tool operation: pivot, fileId={}, userId={}", fileId, userId);
        ExcelOperationResult result = excelToolService.pivot(fileId, userId, rowField, colField, valueField);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/calculate")
    public ResponseEntity<ExcelOperationResult> calculate(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestBody List<CalculateExpression> expressions) {
        log.info("Excel tool operation: calculate, fileId={}, userId={}", fileId, userId);
        ExcelOperationResult result = excelToolService.calculate(fileId, userId, expressions);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/select-columns")
    public ResponseEntity<ExcelOperationResult> selectColumns(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestBody List<String> columnNames) {
        log.info("Excel tool operation: select_columns, fileId={}, userId={}", fileId, userId);
        ExcelOperationResult result = excelToolService.selectColumns(fileId, userId, columnNames);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/clean")
    public ResponseEntity<ExcelOperationResult> clean(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestParam String strategy) {
        log.info("Excel tool operation: clean, fileId={}, userId={}, strategy={}", fileId, userId, strategy);
        ExcelOperationResult result = excelToolService.clean(fileId, userId, strategy);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/merge")
    public ResponseEntity<ExcelOperationResult> merge(
            @RequestParam Long fileId1,
            @RequestParam Long fileId2,
            @RequestParam String userId,
            @RequestParam String joinKey) {
        log.info("Excel tool operation: merge, fileId1={}, fileId2={}, userId={}", fileId1, fileId2, userId);
        ExcelOperationResult result = excelToolService.merge(fileId1, fileId2, userId, joinKey);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/convert-format")
    public ResponseEntity<ExcelOperationResult> convertFormat(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestParam String targetFormat) {
        log.info("Excel tool operation: convert_format, fileId={}, userId={}, format={}", fileId, userId, targetFormat);
        ExcelOperationResult result = excelToolService.convertFormat(fileId, userId, targetFormat);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/apply-style")
    public ResponseEntity<ExcelOperationResult> applyStyle(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestParam String conditionColumn,
            @RequestParam String conditionValue,
            @RequestParam String color) {
        log.info("Excel tool operation: apply_style, fileId={}, userId={}", fileId, userId);
        ExcelOperationResult result = excelToolService.applyStyle(fileId, userId, conditionColumn, conditionValue, color);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/validate")
    public ResponseEntity<ExcelOperationResult> validate(
            @RequestParam Long fileId,
            @RequestParam String userId,
            @RequestBody List<Map<String, Object>> rules) {
        log.info("Excel tool operation: validate, fileId={}, userId={}", fileId, userId);
        ExcelOperationResult result = excelToolService.validate(fileId, userId, rules);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/execute")
    public ResponseEntity<ExcelOperationResult> execute(
            @RequestBody Map<String, Object> request) {
        String operation = (String) request.get("operation");
        Long fileId = request.get("fileId") != null ? ((Number) request.get("fileId")).longValue() : null;
        String userId = (String) request.get("userId");

        log.info("Excel tool execute: operation={}, fileId={}, userId={}", operation, fileId, userId);

        switch (operation) {
            case "read":
                return ResponseEntity.ok(excelToolService.read(fileId, userId));
            case "filter":
                List<FilterCriteria> criteria = parseFilterCriteria(request);
                return ResponseEntity.ok(excelToolService.filter(fileId, userId, criteria));
            case "sort":
                List<SortSpec> sortSpecs = parseSortSpecs(request);
                return ResponseEntity.ok(excelToolService.sort(fileId, userId, sortSpecs));
            case "aggregate":
                String groupBy = (String) request.get("groupBy");
                List<AggregationSpec> aggs = parseAggregationSpecs(request);
                return ResponseEntity.ok(excelToolService.aggregate(fileId, userId, groupBy, aggs));
            case "pivot":
                String rowField = (String) request.get("rowField");
                String colField = (String) request.get("colField");
                String valueField = (String) request.get("valueField");
                return ResponseEntity.ok(excelToolService.pivot(fileId, userId, rowField, colField, valueField));
            case "calculate":
                List<CalculateExpression> exprs = parseExpressions(request);
                return ResponseEntity.ok(excelToolService.calculate(fileId, userId, exprs));
            case "select_columns":
                @SuppressWarnings("unchecked")
                List<String> columns = (List<String>) request.get("columns");
                return ResponseEntity.ok(excelToolService.selectColumns(fileId, userId, columns));
            case "clean":
                String strategy = (String) request.get("strategy");
                return ResponseEntity.ok(excelToolService.clean(fileId, userId, strategy));
            case "merge":
                Long fileId2 = request.get("fileId2") != null ? ((Number) request.get("fileId2")).longValue() : null;
                String joinKey = (String) request.get("joinKey");
                return ResponseEntity.ok(excelToolService.merge(fileId, fileId2, userId, joinKey));
            case "convert_format":
                String format = (String) request.get("targetFormat");
                return ResponseEntity.ok(excelToolService.convertFormat(fileId, userId, format));
            case "apply_style":
                String condCol = (String) request.get("conditionColumn");
                String condVal = (String) request.get("conditionValue");
                String color = (String) request.get("color");
                return ResponseEntity.ok(excelToolService.applyStyle(fileId, userId, condCol, condVal, color));
            case "validate":
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rules = (List<Map<String, Object>>) request.get("rules");
                return ResponseEntity.ok(excelToolService.validate(fileId, userId, rules));
            default:
                ExcelOperationResult result = ExcelOperationResult.failure(operation, "不支持的操作: " + operation);
                return ResponseEntity.ok(result);
        }
    }

    @SuppressWarnings("unchecked")
    private List<FilterCriteria> parseFilterCriteria(Map<String, Object> request) {
        List<Map<String, Object>> criteriaList = (List<Map<String, Object>>) request.get("criteria");
        return criteriaList.stream()
                .map(c -> new FilterCriteria(
                        (String) c.get("column"),
                        (String) c.get("operator"),
                        c.get("value")))
                .collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<SortSpec> parseSortSpecs(Map<String, Object> request) {
        List<Map<String, Object>> sortList = (List<Map<String, Object>>) request.get("sortSpecs");
        return sortList.stream()
                .map(s -> new SortSpec((String) s.get("column"), (String) s.get("order")))
                .collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<AggregationSpec> parseAggregationSpecs(Map<String, Object> request) {
        List<Map<String, Object>> aggList = (List<Map<String, Object>>) request.get("aggregations");
        return aggList.stream()
                .map(a -> new AggregationSpec((String) a.get("column"), (String) a.get("function")))
                .collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<CalculateExpression> parseExpressions(Map<String, Object> request) {
        List<Map<String, Object>> exprList = (List<Map<String, Object>>) request.get("expressions");
        return exprList.stream()
                .map(e -> new CalculateExpression((String) e.get("newColumn"), (String) e.get("expression")))
                .collect(java.util.stream.Collectors.toList());
    }
}