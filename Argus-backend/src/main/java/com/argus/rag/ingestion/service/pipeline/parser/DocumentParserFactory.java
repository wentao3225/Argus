package com.argus.rag.ingestion.service.pipeline.parser;

import com.argus.rag.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 文档解析器简单工厂，负责按扩展名选择对应的解析策略。
 */
@Component
@RequiredArgsConstructor
public class DocumentParserFactory {

    private final List<DocumentParser> parsers;


    /**
     * 根据文件扩展名获取对应的文档解析器。
     *
     * <p>扩展名会先经过规范化处理：去除前导点、转为小写。
     *
     * @param extension 文件扩展名，如 "txt"、"pdf"、".docx"
     * @return 匹配的文档解析器
     * @throws BusinessException 当扩展名为空或不支持该类型时抛出
     */
    public DocumentParser getParser(String extension) {
        String normalizedExtension = normalizeExtension(extension);
        for (DocumentParser parser : parsers) {
            if (parser.supports(normalizedExtension)) {
                return parser;
            }
        }
        throw new BusinessException("不支持的文档类型: " + normalizedExtension);
    }

    /**
     * 规范化文件扩展名：去除前导点、统一转为小写英文。
     *
     * @param extension 原始扩展名，可能包含前导点和大写字母
     * @return 规范化后的扩展名（纯小写、无前导点）
     * @throws BusinessException 当扩展名为 null 或空白时抛出
     */
    private String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            throw new BusinessException("文档扩展名不能为空");
        }
        return extension.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
    }
}
