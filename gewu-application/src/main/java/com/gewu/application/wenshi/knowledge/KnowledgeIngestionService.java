package com.gewu.application.wenshi.knowledge;

import com.gewu.domain.wenshi.knowledge.SemanticFragment;
import com.gewu.infrastructure.wenshi.adapter.EmbeddingAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识摄入服务 — 提供多种来源的知识写入入口，统一封装分块与元数据处理逻辑。
 * <p>
 * 支持三种知识摄入方式：
 * <ul>
 *   <li>{@link #ingestManual} — 手动录入的单条知识</li>
 *   <li>{@link #ingestDocument} — 长文档自动分块后批量摄入</li>
 *   <li>{@link #ingestFromDialogue} — 从对话中提取的关键知识</li>
 * </ul>
 * 长文档分块采用"句子切分 + 滑动窗口重叠"策略，确保语义连贯性。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final SemanticMemoryService semanticMemoryService;
    private final EmbeddingAdapter embeddingAdapter;

    /** 默认分块大小（字符数），平衡检索精度与上下文完整性。 */
    private static final int DEFAULT_CHUNK_SIZE = 300;

    /** 分块重叠大小（字符数），避免跨块信息丢失。 */
    private static final int OVERLAP_SIZE = 50;

    /**
     * 手动录入一条知识。
     * <p>
     * 来源标记为 MANUAL，适用于运营人员手动补充业务知识的场景。
     *
     * @param tenantId 租户 ID
     * @param userId   操作用户 ID
     * @param content  知识内容文本
     * @param metadata 附加元数据，可为 null
     * @return 持久化后的 {@link SemanticFragment} 实体
     * @since 1.0.0
     */
    public SemanticFragment ingestManual(String tenantId, String userId, String content, Map<String, Object> metadata) {
        return semanticMemoryService.ingest(tenantId, userId, content, "MANUAL", metadata);
    }

    /**
     * 将长文档分块后批量摄入语义记忆。
     * <p>
     * 使用句子切分 + 滑动窗口策略将文档拆分为 {@value #DEFAULT_CHUNK_SIZE} 字符的块，
     * 相邻块之间保留 {@value #OVERLAP_SIZE} 字符的重叠区域以维持语义连贯。
     *
     * @param tenantId 租户 ID
     * @param userId   操作用户 ID
     * @param document 完整文档内容
     * @param source   知识来源标识
     * @return 摄入的语义片段列表，每个元素对应一个文档块
     * @since 1.0.0
     */
    public List<SemanticFragment> ingestDocument(String tenantId, String userId, String document, String source) {
        List<String> chunks = splitIntoChunks(document, DEFAULT_CHUNK_SIZE, OVERLAP_SIZE);
        List<SemanticFragment> fragments = new ArrayList<>();

        for (String chunk : chunks) {
            SemanticFragment fragment = semanticMemoryService.ingest(tenantId, userId, chunk, source, null);
            fragments.add(fragment);
        }

        log.info("KnowledgeIngestionService.ingestDocument: tenantId={}, chunks={}", tenantId, fragments.size());
        return fragments;
    }

    /**
     * 从对话中提取关键知识并摄入。
     * <p>
     * 来源标记为 DIALOGUE，适用于从用户交互中自动沉淀知识的场景。
     *
     * @param tenantId      租户 ID
     * @param userId        操作用户 ID
     * @param keyKnowledge  从对话中提炼的知识文本
     * @return 持久化后的 {@link SemanticFragment} 实体
     * @since 1.0.0
     */
    public SemanticFragment ingestFromDialogue(String tenantId, String userId, String keyKnowledge) {
        return semanticMemoryService.ingest(tenantId, userId, keyKnowledge, "DIALOGUE", null);
    }

    /**
     * 将文本按句子切分并使用滑动窗口合成为固定大小的块。
     * <p>
     * 分块策略：
     * <ol>
     *   <li>按中英文标点（。！？.!?；;）切分为句子</li>
     *   <li>逐个句子追加到当前块，超过 chunkSize 时保存当前块</li>
     *   <li>新块以当前块末尾 overlap 长度的文本开头，保持上下文连贯</li>
     * </ol>
     *
     * @param text      待分块的原始文本
     * @param chunkSize 每块的目标字符数
     * @param overlap   相邻块之间的重叠字符数
     * @return 分块后的文本列表，空输入返回空列表
     */
    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // 按中英文标点切分句子，保留语义完整性
        String[] sentences = text.split("[。！？.!?；;]");
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // 当前块已满时保存，并保留 overlap 区域作为下一块的前缀
            if (currentChunk.length() + trimmed.length() > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());

                String overlapText = currentChunk.toString();
                if (overlapText.length() > overlap) {
                    overlapText = overlapText.substring(overlapText.length() - overlap);
                }
                currentChunk = new StringBuilder(overlapText);
            }

            if (currentChunk.length() > 0) {
                currentChunk.append("。");
            }
            currentChunk.append(trimmed);
        }

        // 处理最后剩余的文本
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }
}
