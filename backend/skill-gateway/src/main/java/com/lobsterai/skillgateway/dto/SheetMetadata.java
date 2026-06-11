package com.lobsterai.skillgateway.dto;

import java.util.ArrayList;
import java.util.List;

public class SheetMetadata {

    private String sheet_name;
    private int sheet_index;
    private int row_count;
    private int col_count;
    private boolean has_header;
    private int header_row_index;
    private List<ColumnMetadata> columns;
    private List<RowData> rows;

    public SheetMetadata() {
        this.columns = new ArrayList<>();
        this.rows = new ArrayList<>();
        this.has_header = true;
        this.header_row_index = 0;
    }

    public String getSheet_name() {
        return sheet_name;
    }

    public void setSheet_name(String sheet_name) {
        this.sheet_name = sheet_name;
    }

    public int getSheet_index() {
        return sheet_index;
    }

    public void setSheet_index(int sheet_index) {
        this.sheet_index = sheet_index;
    }

    public int getRow_count() {
        return row_count;
    }

    public void setRow_count(int row_count) {
        this.row_count = row_count;
    }

    public int getCol_count() {
        return col_count;
    }

    public void setCol_count(int col_count) {
        this.col_count = col_count;
    }

    public boolean isHas_header() {
        return has_header;
    }

    public void setHas_header(boolean has_header) {
        this.has_header = has_header;
    }

    public int getHeader_row_index() {
        return header_row_index;
    }

    public void setHeader_row_index(int header_row_index) {
        this.header_row_index = header_row_index;
    }

    public List<ColumnMetadata> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnMetadata> columns) {
        this.columns = columns;
    }

    public void addColumn(ColumnMetadata column) {
        this.columns.add(column);
    }

    public List<RowData> getRows() {
        return rows;
    }

    public void setRows(List<RowData> rows) {
        this.rows = rows;
    }

    public void addRow(RowData row) {
        this.rows.add(row);
    }
}