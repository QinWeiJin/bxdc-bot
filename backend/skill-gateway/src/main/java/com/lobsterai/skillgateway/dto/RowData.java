package com.lobsterai.skillgateway.dto;

import java.util.ArrayList;
import java.util.List;

public class RowData {

    private int row_index;
    private List<Object> values;

    public RowData() {
        this.values = new ArrayList<>();
    }

    public int getRow_index() {
        return row_index;
    }

    public void setRow_index(int row_index) {
        this.row_index = row_index;
    }

    public List<Object> getValues() {
        return values;
    }

    public void setValues(List<Object> values) {
        this.values = values;
    }

    public void addValue(Object value) {
        this.values.add(value);
    }
}