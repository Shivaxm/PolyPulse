package com.polypulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricePointDTO {
    private Instant timestamp;
    private BigDecimal price;
    private BigDecimal volume;
}
