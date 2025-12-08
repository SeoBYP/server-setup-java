package kr.hhplus.be.server.product;

import kr.hhplus.be.server.product.DTO.ProductResponse;
import kr.hhplus.be.server.product.popularProduct.PopularProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProductService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PopularProductRepository popularProductRepository;

    @Transactional
    public ProductResponse getProduct(Long productId)
    {
        var product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
        return ProductResponse.from(product);
    }

    @Transactional
    public List<ProductResponse> getProducts(Long[] productIds)
    {
        // 1. 모든 Product 엔티티를 조회
        List<Product> products = productRepository.findAllById(List.of(productIds));

        // 2. Stream API를 사용하여 Product 엔티티 목록을 ProductResponse DTO 목록으로 변환
        return products.stream()
                .map(ProductResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<ProductResponse> getProducts(List<Long> productIds)
    {
        // 1. 모든 Product 엔티티를 조회
        List<Product> products = productRepository.findAllById(productIds);

        // 2. Stream API를 사용하여 Product 엔티티 목록을 ProductResponse DTO 목록으로 변환
        return products.stream()
                .map(ProductResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getProducts()
    {
        // 1. 모든 Product 엔티티를 조회
        List<Product> products = productRepository.findAll();

        // 2. Stream API를 사용하여 Product 엔티티 목록을 ProductResponse DTO 목록으로 변환
        return products.stream()
                .map(ProductResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public Product createProduct(Product product)
    {
        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getTopSellingProducts() {
        // 1. PopularProductRepository에서 상위 5개 상품 ID를 조회
        List<Long> topProductIds = popularProductRepository.findTop5ProductIds();

        // 💡 예외 처리: 인기 상품 ID 목록이 비어 있는 경우 (예: 초기 상태, 집계 오류)
        // 불필요한 DB 조회를 막고 빈 리스트를 반환하여 안전하게 처리합니다.
        if (topProductIds.isEmpty()) {
            // Java 9+ 에서는 List.of()를, Java 8에서는 Collections.emptyList()를 사용할 수 있습니다.
            return Collections.emptyList();
        }

        // 2. 캐시된 ID 리스트를 이용하여 상품 상세 정보만 DB에서 조회
        // findByIdIn()을 통해 단 한 번의 쿼리로 N개의 상품 데이터를 가져옵니다. (N+1 문제 방지)
        List<Product> products = productRepository.findByProductIdIn(topProductIds);

        // 3. Stream API를 사용하여 Product 엔티티 목록을 ProductResponse DTO 목록으로 변환
        return products.stream()
                // ProductResponse.from(Product product) 정적 팩토리 메서드 참조
                .map(ProductResponse::from)
                .collect(Collectors.toList());
    }
    @Scheduled(fixedRate = 3600000) // 1시간마다 실행
    public void calculateAndCacheTopSellingProducts() {
        // 1. Order 테이블을 GROUP BY 하여 최근 3일간의 판매량을 집계
        // 2. 상위 5개 ID 추출
        // 3. topSellingCacheRepository.save(top5Ids)
    }

    @Transactional
    public Product charge(Long productId, Integer amount)
    {
        var product = productRepository.findForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
        product.charge(amount);
        return productRepository.save(product);
    }

    @Transactional
    public Product debit(Long productId, Integer amount)
    {
        var product = productRepository.findForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
        product.debit(amount);
    
        return productRepository.save(product);
    }
}
