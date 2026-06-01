package com.argus.rag.qa.constant;

/**
 * QA 常量
 */
public interface QaConstant {

    /**
     * 拒答原因编码：证据不足
     */
     String INSUFFICIENT_CODE = "INSUFFICIENT_EVIDENCE";
    /**
     * 拒答原因描述：证据不足
     */
     String INSUFFICIENT_MESSAGE = "检索到的有效证据不足，暂不回答。";
    /**
     * 拒答原因编码：回答格式错误
     */
     String FORMAT_ERROR_CODE = "ANSWER_FORMAT_ERROR";
    /**
     * 拒答原因描述：回答格式错误
     */
     String FORMAT_ERROR_MESSAGE = "模型返回格式错误，无法解析回答。";

     String MODEL_NAME = "qwen-plus";
}
