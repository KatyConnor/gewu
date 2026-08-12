package com.gewu.infrastructure.wenshi.adapter;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.gewu.infrastructure.wenshi.BgeTokenizer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 基于 BGE-small-zh-v1.5 ONNX 模型的本地嵌入适配器。
 * <p>
 * 通过 ONNX Runtime 加载中文 BGE-small 模型进行本地推理，无需调用外部 API。
 * 模型加载采用懒加载 + 双重检查锁，首次调用时初始化。
 * <p>
 * 容错策略：模型文件缺失或加载失败时，自动降级为基于 hashCode 的伪随机向量，
 * 保证服务可用性（但语义检索质量会下降）。
 *
 * @since 1.0.0
 */
@Slf4j
public class BgeSmallEmbeddingAdapter implements EmbeddingAdapter {

    /** ONNX 模型文件路径，可通过配置覆盖 */
    @Value("${wenshi.embedding.model-path:models/bge-small-zh-v1.5.onnx}")
    private String modelPath;

    /** 嵌入向量维度，BGE-small 默认为 384 */
    @Value("${wenshi.embedding.dimension:384}")
    private int dimension;

    private final BgeTokenizer tokenizer;

    @Autowired
    public BgeSmallEmbeddingAdapter(BgeTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    private OrtEnvironment environment;
    private OrtSession session;
    // 标记模型加载是否已尝试过（无论成功与否），避免重复加载
    private boolean modelLoaded = false;

    /**
     * {@inheritDoc}
     * <p>
     * 若模型不可用，降级返回基于文本 hashCode 的伪随机向量。
     */
    @Override
    public float[] embed(String text) {
        if (!modelLoaded) {
            loadModel();
        }
        if (session == null) {
            return fallbackEmbed(text);
        }
        try {
            return inference(text);
        } catch (OrtException e) {
            // 推理异常时降级，不影响上层调用
            log.warn("BgeSmallEmbeddingAdapter inference failed, using fallback: {}", e.getMessage());
            return fallbackEmbed(text);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 当前实现为逐条推理，后续可优化为批量推理以提升吞吐。
     */
    @Override
    public float[][] embedBatch(List<String> texts) {
        if (!modelLoaded) {
            loadModel();
        }
        float[][] results = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            results[i] = embed(texts.get(i));
        }
        return results;
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public String getProvider() {
        return "bge-small";
    }

    /**
     * 懒加载 ONNX 模型，线程安全。
     * <p>
     * 仅首次调用时执行，后续调用直接跳过。
     */
    private synchronized void loadModel() {
        if (modelLoaded) {
            return;
        }
        modelLoaded = true;

        Path path = Paths.get(modelPath);
        if (!path.toFile().exists()) {
            log.warn("BgeSmallEmbeddingAdapter: model file not found at '{}', using fallback embedding", modelPath);
            return;
        }

        try {
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setInterOpNumThreads(1);
            // 使用可用处理器数量作为 intra-op 线程数，平衡吞吐与资源占用
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
            session = environment.createSession(modelPath, options);
            log.info("BgeSmallEmbeddingAdapter: model loaded successfully from {}", modelPath);
        } catch (OrtException e) {
            log.warn("BgeSmallEmbeddingAdapter: failed to load model: {}", e.getMessage());
            session = null;
        }
    }

    /**
     * 执行 ONNX 模型推理，返回 mean pooling + L2 归一化后的向量。
     *
     * @param text 待编码文本
     * @return 嵌入向量
     * @throws OrtException 推理过程中发生 ONNX 运行时异常
     */
    private float[] inference(String text) throws OrtException {
        if (text == null || text.isBlank()) {
            return new float[dimension];
        }

        // BGE 模型最大输入长度为 512 token，超长截断
        String inputText = text.length() > 512 ? text.substring(0, 512) : text;

        long[] inputIds = tokenize(inputText);
        long[] attentionMask = createAttentionMask(inputIds.length);
        long[] tokenTypeIds = new long[inputIds.length];

        // 构造 batch_size=1 的二维张量
        long[][] inputIdsTensor = new long[1][inputIds.length];
        long[][] attentionMaskTensor = new long[1][attentionMask.length];
        long[][] tokenTypeIdsTensor = new long[1][tokenTypeIds.length];
        System.arraycopy(inputIds, 0, inputIdsTensor[0], 0, inputIds.length);
        System.arraycopy(attentionMask, 0, attentionMaskTensor[0], 0, attentionMask.length);
        System.arraycopy(tokenTypeIds, 0, tokenTypeIdsTensor[0], 0, tokenTypeIds.length);

        try (ai.onnxruntime.OnnxTensor inputIdsOnnx = ai.onnxruntime.OnnxTensor.createTensor(environment, inputIdsTensor);
             ai.onnxruntime.OnnxTensor attentionMaskOnnx = ai.onnxruntime.OnnxTensor.createTensor(environment, attentionMaskTensor);
             ai.onnxruntime.OnnxTensor tokenTypeIdsOnnx = ai.onnxruntime.OnnxTensor.createTensor(environment, tokenTypeIdsTensor)) {

            java.util.Map<String, ai.onnxruntime.OnnxTensor> inputs = new java.util.HashMap<>();
            inputs.put("input_ids", inputIdsOnnx);
            inputs.put("attention_mask", attentionMaskOnnx);
            inputs.put("token_type_ids", tokenTypeIdsOnnx);

            try (OrtSession.Result results = session.run(inputs)) {
                float[][][] output = (float[][][]) results.get(0).getValue();
                // mean pooling 聚合 token 级输出为句子级向量
                return meanPooling(output[0], attentionMaskTensor[0]);
            }
        }
    }

    private long[] tokenize(String text) {
        return tokenizer.tokenize(text);
    }

    private long[] createAttentionMask(int length) {
        long[] mask = new long[length];
        for (int i = 0; i < length; i++) {
            mask[i] = 1;
        }
        return mask;
    }

    /**
     * 对 token 级嵌入执行 mean pooling，仅对有效 token（attention_mask=1）取平均。
     * <p>
     * 输出向量经过 L2 归一化，便于后续余弦相似度计算。
     *
     * @param tokenEmbeddings 每个 token 的嵌入向量
     * @param attentionMask   注意力掩码，1 表示有效 token
     * @return L2 归一化后的句子级向量
     */
    private float[] meanPooling(float[][] tokenEmbeddings, long[] attentionMask) {
        float[] result = new float[dimension];
        int validTokens = 0;
        for (int i = 0; i < attentionMask.length; i++) {
            if (attentionMask[i] == 1) {
                validTokens++;
                for (int j = 0; j < dimension; j++) {
                    if (j < tokenEmbeddings[i].length) {
                        result[j] += tokenEmbeddings[i][j];
                    }
                }
            }
        }
        if (validTokens > 0) {
            for (int j = 0; j < dimension; j++) {
                result[j] /= validTokens;
            }
        }
        return normalize(result);
    }

    /**
     * 对向量执行 L2 归一化（原地修改）。
     *
     * @param vector 待归一化向量
     * @return 归一化后的向量（同一引用）
     */
    private float[] normalize(float[] vector) {
        float sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    /**
     * 降级嵌入策略：基于文本 hashCode 生成确定性伪随机向量。
     * <p>
     * 保证相同文本每次返回相同向量，不同文本返回不同向量。
     * 仅用于模型不可用时的兜底，不携带语义信息。
     *
     * @param text 输入文本
     * @return 伪随机向量
     */
    private float[] fallbackEmbed(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            return vector;
        }
        int seed = text.hashCode();
        java.util.Random random = new java.util.Random(seed);
        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return normalize(vector);
    }

    /**
     * Spring 容器销毁时释放 ONNX 资源。
     */
    @PreDestroy
    public void cleanup() {
        if (session != null) {
            try {
                session.close();
                log.info("BgeSmallEmbeddingAdapter: ONNX session closed");
            } catch (OrtException e) {
                log.warn("BgeSmallEmbeddingAdapter: error closing session: {}", e.getMessage());
            }
        }
        if (environment != null) {
            environment.close();
            log.info("BgeSmallEmbeddingAdapter: ONNX environment closed");
        }
    }
}
