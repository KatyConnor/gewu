package com.gewu.common.dto.sandbox;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class RenewExpireRequest {

    private Long expireAt;

    @Min(1)
    private Integer ttlDays;
}