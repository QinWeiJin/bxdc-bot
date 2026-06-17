package com.lobsterai.skillgateway.dto;

public class ColumnMetadata {

    private int col_index;
    private String col_letter;
    private String header_name;
    private String inferred_type;

    public ColumnMetadata() {
    }

    public int getCol_index() {
        return col_index;
    }

    public void setCol_index(int col_index) {
        this.col_index = col_index;
        this.col_letter = indexToLetter(col_index);
    }

    public String getCol_letter() {
        return col_letter;
    }

    public void setCol_letter(String col_letter) {
        this.col_letter = col_letter;
    }

    public String getHeader_name() {
        return header_name;
    }

    public void setHeader_name(String header_name) {
        this.header_name = header_name;
    }

    public String getInferred_type() {
        return inferred_type;
    }

    public void setInferred_type(String inferred_type) {
        this.inferred_type = inferred_type;
    }

    private String indexToLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int num = index;
        while (num >= 0) {
            sb.insert(0, (char) ('A' + (num % 26)));
            num = num / 26 - 1;
        }
        return sb.toString();
    }
}