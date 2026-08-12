package com.gewu.infrastructure.wenshi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * BGE 模型的 WordPiece 分词器。
 * <p>
 * 加载 tokenizer.json 构建词表，将文本转换为模型可接受的 token ID 序列。
 * 遵循 BERT 格式：以 CLS 开头，SEP 结尾，不足最大长度时 PAD 填充。
 * <p>
 * 容错策略：词表加载失败时，使用基于 charCode 的 fallback 分词，
 * 保证模型降级时仍能产出有效输入。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class BgeTokenizer {

    /** tokenizer.json 文件路径，支持文件系统路径和 classpath 资源 */
    @Value("${wenshi.embedding.tokenizer-path:models/tokenizer.json}")
    private String tokenizerPath;

    /** 词表：token 字符串 -> ID 映射 */
    private Map<String, Integer> vocab = new HashMap<>();

    /** 最大序列长度，BGE-small 支持 128 */
    private static final int MAX_SEQ_LENGTH = 128;
    /** 子词匹配最大长度，限制搜索范围以提升性能 */
    private static final int MAX_SUBWORD_LEN = 20;
    /** CLS token ID，BERT 序列起始标记 */
    private static final int CLS_TOKEN_ID = 101;
    /** SEP token ID，BERT 序列结束标记 */
    private static final int SEP_TOKEN_ID = 102;
    /** PAD token ID，填充标记 */
    private static final int PAD_TOKEN_ID = 0;
    /** UNK token ID，未知词标记 */
    private static final int UNK_TOKEN_ID = 100;
    private static final long[] PADDED_RESULT = createPaddedResult();

    /**
     * Spring 初始化后加载 tokenizer.json 词表。
     * <p>
     * 优先从文件系统加载，若不存在则尝试 classpath 资源。
     */
    @PostConstruct
    public void init() {
        try {
            JsonNode root = loadTokenizerJson();
            if (root == null) return;
            loadVocab(root);
            log.info("BgeTokenizer: loaded vocab size={}", vocab.size());
        } catch (Exception e) {
            log.warn("BgeTokenizer: failed to load tokenizer: {}", e.getMessage());
        }
    }

    /**
     * 加载 tokenizer.json 文件。
     * <p>
     * 优先从文件系统路径加载，若不存在则从 classpath 资源加载。
     *
     * @return JSON 根节点，未找到时返回 null
     * @throws Exception IO 或 JSON 解析异常
     */
    private JsonNode loadTokenizerJson() throws Exception {
        if (Files.exists(Paths.get(tokenizerPath))) {
            return new ObjectMapper().readTree(Files.newInputStream(Paths.get(tokenizerPath)));
        }
        InputStream is = getClass().getClassLoader().getResourceAsStream(tokenizerPath);
        if (is == null) {
            log.warn("BgeTokenizer: tokenizer.json not found at {}", tokenizerPath);
            return null;
        }
        return new ObjectMapper().readTree(is);
    }

    /**
     * 从 JSON 节点解析词表。
     * <p>
     * 支持两种格式：数组格式（索引即 ID）和对象格式（key=token, value=id）。
     */
    private void loadVocab(JsonNode root) {
        JsonNode modelNode = root.get("model");
        if (modelNode == null || !modelNode.has("vocab")) return;
        JsonNode vocabNode = modelNode.get("vocab");
        if (vocabNode.isArray()) {
            int idx = 0;
            for (JsonNode token : vocabNode) {
                vocab.put(token.asText(), idx++);
            }
        } else {
            vocabNode.fields().forEachRemaining(entry ->
                    vocab.put(entry.getKey(), entry.getValue().asInt()));
        }
    }

    /**
     * 将文本分词并转换为 token ID 序列。
     * <p>
     * 输出格式：[CLS, token1, token2, ..., SEP, PAD, PAD, ...]
     * 长度固定为 MAX_SEQ_LENGTH。
     *
     * @param text 输入文本
     * @return token ID 数组，长度 = MAX_SEQ_LENGTH
     */
    public long[] tokenize(String text) {
        if (vocab.isEmpty()) {
            return fallbackTokenize(text);
        }
        long[] result = new long[MAX_SEQ_LENGTH];
        result[0] = CLS_TOKEN_ID;
        String normalized = text.toLowerCase().trim();
        int pos = 1;
        int charIndex = 0;

        while (charIndex < normalized.length() && pos < MAX_SEQ_LENGTH - 1) {
            int consumed = wordpieceMatch(normalized, charIndex, result, pos);
            pos++;
            charIndex += consumed;
        }

        result[pos] = SEP_TOKEN_ID;
        return result;
    }

    /**
     * 从指定位置开始执行 WordPiece 最长匹配。
     * <p>
     * 非首字符前添加 "##" 前缀（BERT WordPiece 约定），
     * 从最长子词开始逐步缩短，直到匹配词表或退化为 UNK。
     *
     * @param text   已归一化的文本
     * @param start  当前匹配起始位置
     * @param result token ID 输出数组
     * @param pos    当前写入位置
     * @return 消费的字符数
     */
    private int wordpieceMatch(String text, int start, long[] result, int pos) {
        int maxLen = Math.min(text.length() - start, MAX_SUBWORD_LEN);
        boolean isContinuation = start > 0;
        String prefix = isContinuation ? "##" : "";

        for (int len = maxLen; len >= 1; len--) {
            String substr = text.substring(start, start + len);
            Integer id = vocab.get(prefix + substr);
            if (id == null) id = vocab.get(substr);
            if (id != null) {
                result[pos] = id;
                return isContinuation ? len : len;
            }
        }

        // 未匹配到任何子词，标记为 UNK 并前进一个字符
        result[pos] = UNK_TOKEN_ID;
        return 1;
    }

    /**
     * 降级分词策略：基于字符编码生成 token ID。
     * <p>
     * 词表未加载时使用，保证模型降级时仍能产出有效输入。
     * token ID = charCode % 20000 + 1，避开特殊 token 范围。
     *
     * @param text 输入文本
     * @return token ID 数组
     */
    private long[] fallbackTokenize(String text) {
        long[] tokens = new long[MAX_SEQ_LENGTH];
        tokens[0] = CLS_TOKEN_ID;
        int len = Math.min(text.length(), MAX_SEQ_LENGTH - 2);
        for (int i = 0; i < len; i++) {
            tokens[i + 1] = text.charAt(i) % 20000 + 1;
        }
        tokens[len + 1] = SEP_TOKEN_ID;
        return tokens;
    }

    private static long[] createPaddedResult() {
        long[] arr = new long[MAX_SEQ_LENGTH];
        return arr;
    }
}
