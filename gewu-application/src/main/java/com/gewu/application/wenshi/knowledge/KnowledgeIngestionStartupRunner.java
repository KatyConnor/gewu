package com.gewu.application.wenshi.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.infrastructure.mapper.wenshi.SemanticFragmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 知识库目录灌入启动器（45 号复评前置：知识层灌入领域文档）。
 * <p>应用就绪后扫描 {@code gewu.wenshi.knowledge.ingest-dir} 目录（递归 *.md），
 * 逐文件经 {@link KnowledgeIngestionService#ingestDocument} 分块灌入语义记忆
 * （300 字符块/50 重叠，bge-small 内联向量化）。按 source（相对路径）去重：
 * 已灌过的文件跳过，重启安全；新增/变更文件追加灌入。
 * <p>配置（默认关闭——灌入完成即可移除目录配置）：
 * <pre>
 * gewu.wenshi.knowledge:
 *   ingest-dir: docs/agent-engine   # 语料目录（递归 *.md；空=关闭）
 *   ingest-tenant: default          # 租户
 *   ingest-user: system             # 操作者
 *   ingest-skip-existing: true      # 按 source 去重
 * </pre>
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-16
 */
@Slf4j
@Component
public class KnowledgeIngestionStartupRunner implements ApplicationRunner {

    private final KnowledgeIngestionService ingestionService;
    private final SemanticFragmentMapper semanticFragmentMapper;

    @Value("${gewu.wenshi.knowledge.ingest-dir:}")
    String ingestDir;
    @Value("${gewu.wenshi.knowledge.ingest-tenant:default}")
    String ingestTenant;
    @Value("${gewu.wenshi.knowledge.ingest-user:system}")
    String ingestUser;
    @Value("${gewu.wenshi.knowledge.ingest-skip-existing:true}")
    boolean skipExisting;

    public KnowledgeIngestionStartupRunner(KnowledgeIngestionService ingestionService,
                                           SemanticFragmentMapper semanticFragmentMapper) {
        this.ingestionService = ingestionService;
        this.semanticFragmentMapper = semanticFragmentMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (ingestDir == null || ingestDir.isBlank()) {
            return;
        }
        File dir = new File(ingestDir);
        if (!dir.isDirectory()) {
            log.warn("知识灌入目录不存在，跳过: {}", ingestDir);
            return;
        }
        long start = System.currentTimeMillis();
        int totalFragments = 0;
        int filesIngested = 0;
        int filesSkipped = 0;
        List<File> mdFiles;
        try (Stream<java.nio.file.Path> walk = Files.walk(dir.toPath())) {
            mdFiles = walk
                    .filter(path -> path.toString().toLowerCase().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .map(java.nio.file.Path::toFile)
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("知识灌入目录遍历失败: {}", e.getMessage());
            return;
        }
        log.info("知识库灌入启动: dir={}, files={}", dir.getPath(), mdFiles.size());
        for (File file : mdFiles) {
            String source = dir.toPath().relativize(file.toPath()).toString();
            try {
                if (skipExisting && alreadyIngested(source)) {
                    filesSkipped++;
                    continue;
                }
                String content = Files.readString(file.toPath());
                if (content.isBlank()) {
                    continue;
                }
                List<?> fragments = ingestionService.ingestDocument(ingestTenant, ingestUser, content, source);
                totalFragments += fragments.size();
                filesIngested++;
                log.info("知识灌入: source={}, fragments={}（累计 {}）", source, fragments.size(), totalFragments);
            } catch (Exception e) {
                log.warn("知识灌入单文件失败（继续其余文件）: source={}, cause={}", source, e.getMessage());
            }
        }
        log.info("知识库灌入完成: filesIngested={}, filesSkipped={}, totalFragments={}, 耗时 {}ms",
                filesIngested, filesSkipped, totalFragments, System.currentTimeMillis() - start);
    }

    /** 按 source 去重（该路径曾灌入过即跳过——重启安全；文件变更时人工清理对应片段后可重灌） */
    private boolean alreadyIngested(String source) {
        Long count = semanticFragmentMapper.selectCount(
                new LambdaQueryWrapper<com.gewu.domain.wenshi.knowledge.SemanticFragment>()
                        .eq(com.gewu.domain.wenshi.knowledge.SemanticFragment::getSource, source));
        return count != null && count > 0;
    }
}
