package com.lobsterai.skillgateway.service.parser;

import com.lobsterai.skillgateway.dto.FileParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件解析路由器。
 * <p>
 * 根据文件扩展名（小写）将文件分发到对应的解析器。
 * doc 和 docx 都路由到 WordParser；txt 和 md 都路由到 TxtMdParser。
 * 后续由启雷添加 Excel/CSV 解析器；由壮添加 MD 解析器。
 * </p>
 */
@Component
public class FileParserRouter {

    private static final Logger log = LoggerFactory.getLogger(FileParserRouter.class);

    private final Map<String, FileParser> parserMap = new ConcurrentHashMap<String, FileParser>();

    public FileParserRouter(WordParser wordParser, TxtMdParser txtMdParser, PyParser pyParser, ExcelParser excelParser) {
        // Word
        parserMap.put("doc", wordParser);
        parserMap.put("docx", wordParser);
        // TXT / MD
        parserMap.put("txt", txtMdParser);
        parserMap.put("md", txtMdParser);
        // Python
        parserMap.put("py", pyParser);
        // Excel
        parserMap.put("xls", excelParser);
        parserMap.put("xlsx", excelParser);
        parserMap.put("csv", excelParser);
    }

    /**
     * 注册额外的解析器（供启雷/壮实现后动态注入）。
     */
    public void registerParser(String extension, FileParser parser) {
        parserMap.put(extension.toLowerCase(), parser);
    }

    /**
     * 根据文件扩展名选择解析器并执行解析。
     *
     * @param fileBytes 文件字节数组
     * @param fileName  文件名（用于提取扩展名和解析时作为上下文）
     * @return 解析结果
     * @throws IllegalArgumentException 如果文件类型不支持
     * @throws Exception                解析失败
     */
    public FileParseResult parse(byte[] fileBytes, String fileName) throws Exception {
        String ext = extractExtension(fileName).toLowerCase();
        FileParser parser = parserMap.get(ext);
        if (parser == null) {
            throw new IllegalArgumentException("Unsupported file type: " + ext);
        }
        log.debug("Routing file '{}' (type={}) to parser {}", fileName, ext, parser.getClass().getSimpleName());
        return parser.parse(fileBytes, fileName);
    }

    /**
     * 检查文件扩展名是否有对应的解析器。
     */
    public boolean isSupported(String fileName) {
        String ext = extractExtension(fileName).toLowerCase();
        return parserMap.containsKey(ext);
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1);
    }
}
