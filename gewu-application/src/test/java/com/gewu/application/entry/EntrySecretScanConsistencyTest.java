package com.gewu.application.entry;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 密钥特征集双源一致性守护（RPT-005 F-502）：引擎本地 EntrySecretScan
 * 与 gewu-common SecretScanUtil 的特征正则逐字一致——两处修改需同步，漂移在此拦截。
 * （置于本模块：唯一直接依赖 common 与 engine 两侧的模块。）
 */
class EntrySecretScanConsistencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void patternsIdenticalBetweenCommonAndEngine() throws Exception {
        assertEquals(patternStrings(com.gewu.common.security.SecretScanUtil.class),
                patternStrings(com.gewu.agent.engine.entry.EntrySecretScan.class));
    }

    private static List<String> patternStrings(Class<?> clazz) throws Exception {
        Field f = clazz.getDeclaredField("PATTERNS");
        f.setAccessible(true);
        List<?> patterns = (List<?>) f.get(null);
        Method patternAccessor = patterns.getFirst().getClass().getDeclaredMethod("pattern");
        patternAccessor.setAccessible(true);
        return patterns.stream().map(p -> {
            try {
                return ((Pattern) patternAccessor.invoke(p)).toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }
}
