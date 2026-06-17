package com.lobsterai.skillgateway.dto;

import java.util.ArrayList;
import java.util.List;

public class ExcelMetadata {

    private String file_type;
    private int sheet_count;
    private List<SheetMetadata> sheets;

    public ExcelMetadata() {
        this.file_type = "excel";
        this.sheets = new ArrayList<>();
    }

    public String getFile_type() {
        return file_type;
    }

    public void setFile_type(String file_type) {
        this.file_type = file_type;
    }

    public int getSheet_count() {
        return sheet_count;
    }

    public void setSheet_count(int sheet_count) {
        this.sheet_count = sheet_count;
    }

    public List<SheetMetadata> getSheets() {
        return sheets;
    }

    public void setSheets(List<SheetMetadata> sheets) {
        this.sheets = sheets;
    }

    public void addSheet(SheetMetadata sheet) {
        this.sheets.add(sheet);
    }
}