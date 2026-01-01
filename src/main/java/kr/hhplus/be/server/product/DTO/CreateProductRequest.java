package kr.hhplus.be.server.product.DTO;

import java.math.BigDecimal;

public record CreateProductRequest (
        String name,
        BigDecimal price,
        Integer stock
) { }
