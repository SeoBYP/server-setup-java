package kr.hhplus.be.server.product;

import kr.hhplus.be.server.product.DTO.CreateProductRequest;
import kr.hhplus.be.server.product.DTO.ProductResponse;
import kr.hhplus.be.server.product.popularProduct.PopularProductCache;
import kr.hhplus.be.server.product.popularProduct.PopularProductRankingRedis;
import kr.hhplus.be.server.product.popularProduct.PopularProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ProductService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PopularProductRankingRedis rankingRedis;

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
    public Product createProductTx(CreateProductRequest request) {
        Product p = new Product(request.name(), request.price(), request.stock());
        return productRepository.save(p);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getTopSellingProducts() {
        // 1) Redis ZSET에서 Top5 productId 조회
        List<Long> ids = rankingRedis.getTopIds(5);
        if (ids.isEmpty()) return Collections.emptyList();

        // 2) DB에서 상품 상세 조회 (IN 쿼리)
        List<Product> products = productRepository.findByProductIdIn(ids);

        Map<Long, Product> map = products.stream()
                .collect(Collectors.toMap(Product::getProductId, p -> p));

        // 3) Redis에서 받은 id 순서대로 정렬 유지
        return ids.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional
    public Product chargeTx(Long productId, Integer amount)
    {
        var product = productRepository.findForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
        product.charge(amount);
        return productRepository.save(product);
    }

    @Transactional
    public Product debitTx(Long productId, Integer amount)
    {
        var product = productRepository.findForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
        product.debit(amount);
    
        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Product getByNameTx(String name) {
        return productRepository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
    }

}
