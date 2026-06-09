package com.lobsterai.skillgateway.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PPT 解析成功响应。
 *
 * - text       纯文本内容（已应用 80KB 截断）
 * - slideCount 原始 PPT 中 slide 总数（截断前）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PptParseResponse {

    /** 解析后的纯文本（已应用 80KB 截断标记） */
    private String text;

    /** PPT slide 总数 */
    private int slideCount;
}
