package com.gewu.common.ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class UlidTest {

    private static final Pattern CROCKFORD_BASE32 = Pattern.compile("^[0-9A-HJ-KM-NP-TV-Z]{26}$");

    @Test
    @DisplayName("Ulid.next() 生成 26 字符的字符串")
    void nextGenerates26CharString() {
        String ulid = Ulid.next();
        assertThat(ulid).hasSize(26);
    }

    @Test
    @DisplayName("连续生成 10000 次 ULID 全部唯一")
    void ulidsAreUniqueAcross10000Generations() {
        Set<String> ulids = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            ulids.add(Ulid.next());
        }
        assertThat(ulids).hasSize(10000);
    }

    @Test
    @DisplayName("生成的 ULID 按时间戳升序排列")
    void ulidsAreSortableInAscendingOrder() {
        List<String> ulids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ulids.add(Ulid.next());
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (int i = 1; i < ulids.size(); i++) {
            assertThat(ulids.get(i - 1))
                    .as("ULID[%d] 应小于 ULID[%d]", i - 1, i)
                    .isLessThanOrEqualTo(ulids.get(i));
        }
    }

    @Test
    @DisplayName("ULID 仅包含合法的 Crockford Base32 字符（0-9, A-Z 排除 I/L/O/U）")
    void ulidsOnlyContainValidCrockfordBase32Characters() {
        for (int i = 0; i < 1000; i++) {
            String ulid = Ulid.next();
            assertThat(CROCKFORD_BASE32.matcher(ulid).matches())
                    .as("ULID %s 包含非法字符", ulid)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("nextMonotonic 在同一毫秒内返回递增的 ULID")
    void nextMonotonicIncrementsWithinSameTimestamp() {
        String first = Ulid.next();
        String second = Ulid.nextMonotonic(first);
        assertThat(second).hasSize(26);
    }

    @Test
    @DisplayName("nextMonotonic 对 null 前驱返回合法 ULID")
    void nextMonotonicWithNullReturnsValidUlid() {
        String ulid = Ulid.nextMonotonic(null);
        assertThat(ulid).hasSize(26);
        assertThat(CROCKFORD_BASE32.matcher(ulid).matches()).isTrue();
    }

    @Test
    @DisplayName("nextMonotonic 对非法长度前驱返回合法 ULID")
    void nextMonotonicWithInvalidLengthReturnsValidUlid() {
        String ulid = Ulid.nextMonotonic("short");
        assertThat(ulid).hasSize(26);
        assertThat(CROCKFORD_BASE32.matcher(ulid).matches()).isTrue();
    }

    @Test
    @DisplayName("快速连续调用 nextMonotonic 保证结果合法且唯一")
    void rapidMonotonicCallsProduceValidUniqueUids() {
        String prev = Ulid.next();
        Set<String> ulids = new HashSet<>();
        ulids.add(prev);
        for (int i = 0; i < 5000; i++) {
            String next = Ulid.nextMonotonic(prev);
            assertThat(next).hasSize(26);
            assertThat(CROCKFORD_BASE32.matcher(next).matches()).isTrue();
            ulids.add(next);
            prev = next;
        }
        assertThat(ulids).hasSize(5001);
    }
}
