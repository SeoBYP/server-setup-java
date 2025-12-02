package kr.hhplus.be.server.product.DTO;

import kr.hhplus.be.server.product.Product;

import java.math.BigDecimal;

/**
 * 상품 정보를 클라이언트에게 응답하기 위한 DTO (Data Transfer Object)
 * record를 사용하여 불변 객체로 정의합니다.
 */
public record ProductResponse(
        Long productId,
        String name,
        BigDecimal price,
        Integer stock
) {
    // 💡 참고: record는 생성자, getter, equals(), hashCode(), toString()을 자동으로 생성합니다.

    /**
     * Product 엔티티를 ProductResponse DTO로 변환하는 정적 팩토리 메서드.
     * Service 계층에서 엔티티를 이 DTO로 변환할 때 사용합니다.
     * * @param product 변환할 Product 엔티티
     * @return ProductResponse DTO
     */
    public static ProductResponse from(Product product) {
        // Product 엔티티는 개발자가 정의해야 합니다.
        // Product 엔티티에는 getId(), getName(), getPrice(), getStock() 등의 메서드가 있다고 가정합니다.

        return new ProductResponse(
                product.getProductId(),
                product.getName(),
                product.getPrice(),
                product.getStock()
        );
    }
}