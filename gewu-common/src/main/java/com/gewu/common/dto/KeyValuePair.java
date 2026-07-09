package com.gewu.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 键值对，用于灵活参数传递.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyValuePair {
    private String key;
    private String value;
}