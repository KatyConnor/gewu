package com.gewu.desktop.adapter;

import com.gewu.agent.engine.memory.MemoryFragment;
import com.gewu.agent.engine.memory.MemoryStore;
import com.gewu.desktop.store.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 桌面记忆存储 v1（D2-10）：H2 + 关键词 TF 加权检索（无向量依赖；
 * v2 演进为本地 ONNX 嵌入，见 docs/design/42 §7.3）。
 * <p>记忆来源：引擎执行成功后的经验沉淀（storeExperience，episodic）＋ 设置/知识写入。
 * 分词：CJK 字符 bigram + 拉丁/数字词——中英混合查询均可命中。
 *
 * @since 1.0.0
 */
@Component
public class DesktopMemoryStore implements MemoryStore {

    private final JdbcTemplate jdbc;

    public DesktopMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(MemoryFragment fragment) {
        long now = System.currentTimeMillis();
        String tokens = tokenizeJson(fragment.getContent());
        jdbc.update("""
                MERGE INTO local_memory (id, domain, type, content, tokens_json, created_at) KEY(id)
                VALUES (?,?,?,?,?,?)""",
                fragment.getId() == null ? Ids.newId() : fragment.getId(),
                fragment.getDomain() == null ? "default" : fragment.getDomain(),
                fragment.getType() == null ? "episodic" : fragment.getType(),
                fragment.getContent(), tokens, now);
    }

    @Override
    public List<MemoryFragment> retrieve(String domain, String query, int topK) {
        String d = domain == null || domain.isBlank() ? "default" : domain;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, type, content, tokens_json FROM local_memory WHERE domain = ? ORDER BY created_at DESC LIMIT 500",
                d);
        List<String> qTokens = tokenize(query);
        record Scored(MemoryFragment fragment, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String tokensJson = String.valueOf(row.getOrDefault("TOKENS_JSON", row.get("tokens_json")));
            double score = scoreTokens(qTokens, tokensJson);
            if (score <= 0) {
                continue;
            }
            MemoryFragment f = new MemoryFragment();
            f.setId(String.valueOf(row.getOrDefault("ID", row.get("id"))));
            f.setDomain(d);
            f.setType(String.valueOf(row.getOrDefault("TYPE", row.get("type"))));
            f.setContent(String.valueOf(row.getOrDefault("CONTENT", row.get("content"))));
            f.setScore(score);
            scored.add(new Scored(f, score));
        }
        return scored.stream()
                .sorted((x, y) -> Double.compare(y.score(), x.score()))
                .limit(topK)
                .map(Scored::fragment)
                .collect(Collectors.toList());
    }

    @Override
    public void clear(String domain) {
        jdbc.update("DELETE FROM local_memory WHERE domain = ?", domain);
    }

    // ==================== 分词与打分 ====================

    /** CJK bigram + 拉丁/数字小写词 */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        String s = text.toLowerCase();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (c > 0x4E00 && c < 0x9FFF) {
                    flushLatin(tokens, latin);
                    cjkRun.append(c);
                } else {
                    flushCjk(tokens, cjkRun);
                    latin.append(c);
                }
            } else {
                flushLatin(tokens, latin);
                flushCjk(tokens, cjkRun);
            }
        }
        flushLatin(tokens, latin);
        flushCjk(tokens, cjkRun);
        return tokens;
    }

    private static void flushLatin(List<String> tokens, StringBuilder sb) {
        if (sb.length() > 0) {
            tokens.add(sb.toString());
            sb.setLength(0);
        }
    }

    private static void flushCjk(List<String> tokens, StringBuilder sb) {
        if (sb.length() >= 2) {
            for (int i = 0; i + 1 < sb.length(); i++) {
                tokens.add(sb.substring(i, i + 2));
            }
        } else if (sb.length() == 1) {
            tokens.add(sb.toString());
        }
        sb.setLength(0);
    }

    /** 词频 JSON → 与查询词求交加权的简式打分 */
    static double scoreTokens(List<String> queryTokens, String tokensJson) {
        if (queryTokens.isEmpty() || tokensJson == null || tokensJson.isBlank()
                || "null".equals(tokensJson)) {
            return 0;
        }
        Map<String, Long> docFreq = new HashMap<>();
        String cleaned = tokensJson.replace("[", "").replace("]", "").replace("\"", "");
        Arrays.stream(cleaned.split(",")).filter(t -> !t.isBlank())
                .forEach(t -> docFreq.merge(t, 1L, Long::sum));
        double hit = 0;
        for (String q : queryTokens) {
            hit += docFreq.getOrDefault(q, 0L);
        }
        return hit / Math.sqrt(docFreq.size() + 8);
    }

    static String tokenizeJson(String content) {
        List<String> tokens = tokenize(content);
        return tokens.stream().map(t -> "\"" + t.replace("\"", "") + "\"")
                .collect(Collectors.toList()).toString();
    }
}
