package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.memory.MemoryFragment;
import com.gewu.agent.engine.memory.MemoryStore;
import com.gewu.application.wenshi.knowledge.EpisodicMemoryService;
import com.gewu.application.wenshi.knowledge.ProceduralMemoryService;
import com.gewu.application.wenshi.knowledge.SemanticMemoryService;
import com.gewu.application.wenshi.knowledge.WorkingMemoryService;
import com.gewu.domain.wenshi.knowledge.SemanticFragment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MemoryStore SPI 适配器 - 桥接 Wenshi 五类记忆服务到 Agent 引擎。
 * <p>按 {@link MemoryFragment#getType()} 路由到对应的 Wenshi 记忆服务：
 * <ul>
 *   <li>semantic -> {@link SemanticMemoryService}</li>
 *   <li>episodic / experience -> {@link EpisodicMemoryService}</li>
 *   <li>procedural -> {@link ProceduralMemoryService}</li>
 * </ul>
 * 检索统一通过 {@link SemanticMemoryService#search} 进行向量语义检索。
 * <p>启用条件：{@code agent.engine.adapter.enabled=true}
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true")
public class WenshiMemoryStoreAdapter implements MemoryStore {

    private final SemanticMemoryService semanticMemoryService;
    private final EpisodicMemoryService episodicMemoryService;
    private final ProceduralMemoryService proceduralMemoryService;
    private final WorkingMemoryService workingMemoryService;

    private static final String DEFAULT_TENANT = "default";
    private static final String SYSTEM_USER = "system";

    /**
     * 存储记忆片段：按类型路由到对应的 Wenshi 记忆服务。
     */
    @Override
    public void store(MemoryFragment fragment) {
        if (fragment == null || fragment.getContent() == null) {
            return;
        }
        try {
            String tenantId = fragment.getDomain() != null ? fragment.getDomain() : DEFAULT_TENANT;
            String type = fragment.getType() != null ? fragment.getType() : "episodic";
            switch (type) {
                case "semantic" -> semanticMemoryService.ingest(
                        tenantId, SYSTEM_USER, fragment.getContent(), "AGENT_ENGINE", fragment.getMetadata());
                case "episodic", "experience" -> episodicMemoryService.record(
                        tenantId, SYSTEM_USER, null, "MEMORY_STORE", fragment.getContent(), null);
                case "procedural" -> proceduralMemoryService.registerSkill(
                        tenantId, "auto-" + (fragment.getId() != null ? fragment.getId() : "skill"),
                        fragment.getContent(), fragment.getContent(), "AGENT_ENGINE");
                case "working" -> workingMemoryService.put(
                        fragment.getDomain() != null ? fragment.getDomain() : DEFAULT_TENANT,
                        fragment.getId() != null ? fragment.getId() : "wm-" + System.currentTimeMillis(),
                        fragment.getContent());
                default -> episodicMemoryService.record(
                        tenantId, SYSTEM_USER, null, "MEMORY_STORE", fragment.getContent(), null);
            }
            log.debug("WenshiMemoryStoreAdapter.store: type={}, domain={}, contentLength={}",
                    type, tenantId, fragment.getContent().length());
        } catch (Exception e) {
            log.warn("WenshiMemoryStoreAdapter.store failed: {}", e.getMessage());
        }
    }

    /**
     * 语义检索：委托 SemanticMemoryService 进行向量相似度检索。
     */
    @Override
    public List<MemoryFragment> retrieve(String domain, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            String tenantId = domain != null ? domain : DEFAULT_TENANT;
            List<SemanticFragment> fragments = semanticMemoryService.search(tenantId, query, topK);
            List<MemoryFragment> result = fragments.stream()
                    .map(f -> MemoryFragment.builder()
                            .id(f.getId())
                            .domain(tenantId)
                            .type("semantic")
                            .content(f.getContent())
                            .build())
                    .collect(Collectors.toList());
            log.debug("WenshiMemoryStoreAdapter.retrieve: domain={}, query={}, hits={}",
                    tenantId, query, result.size());
            return result;
        } catch (Exception e) {
            log.warn("WenshiMemoryStoreAdapter.retrieve failed: {}", e.getMessage());
            return List.of();
        }
    }
}
