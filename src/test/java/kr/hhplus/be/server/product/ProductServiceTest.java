package kr.hhplus.be.server.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
@Transactional  // 각 테스트 후 롤백
public class ProductServiceTest {
    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
    }

    @Test
    public void 단일_상품_조회_성공() {
        // given
        Product savedProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);
        productRepository.save(savedProduct);

        // when
        var product = productService.getProduct(1L);

        // then
        assertEquals(product.getProductId(), savedProduct.getProductId());
        assertEquals(product.getName(), savedProduct.getName());
    }

    @Test
    public void 상품_목록_조회_성공() {
        // given
        var savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test", BigDecimal.valueOf(1000), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(1000), 10));
        savedProducts.add(new Product(3L, "Test3", BigDecimal.valueOf(1000), 10));
        productRepository.saveAll(savedProducts);

        // when
        var products = productService.getProducts();

        // then
        assertEquals(products.stream().count(), 3);
        assertEquals(products.get(0).getProductId(), 1L);
        assertEquals(products.get(1).getProductId(), 2L);
        assertEquals(products.get(2).getProductId(), 3L);

    }

    @Test
    public void 빈_상품_목록_조회() {
        // given

        // when
        var products = productService.getProducts();

        // then
        assertEquals(products, List.of());
    }

    @Test
    public void 존재하지_않는_상품_조회_예외발생() {
        // given
        Long nonExistentId = 999L;

        // when && then
        assertThatThrownBy(() -> productService.getProduct(nonExistentId))
                .isInstanceOf(IllegalArgumentException.class) // 현재 코드가 던지는 예외 타입
                .hasMessageContaining("PRODUCT_NOT_FOUND"); // 예외 메시지까지 검증
    }

    @Test
    public void 상품_가격_조회_성공() {
        // given
        Product savedProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);
        productRepository.save(savedProduct);

        // when
        var product = productService.getProduct(1L);

        // then
        assertEquals(product.getPrice(), savedProduct.getPrice());
        assertEquals(product.getStock(), savedProduct.getStock());
    }


    @Test
    public void 상품_등록_성공() {
        // given
        Product newProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);

        // when
        var product = productService.createProduct(newProduct);

        // then
        assertEquals(product.getProductId(), newProduct.getProductId());
        assertEquals(product.getPrice(), newProduct.getPrice());
    }

    @Test
    public void 상품_등록_가격_음수_저장_방지() {
        // given
        BigDecimal negativePrice = BigDecimal.valueOf(-100);

        // when & then
        // Product 객체 생성 시점에 예외가 발생하는지 검증
        assertThatThrownBy(() -> new Product(
                1L,
                "Negative Price Item",
                negativePrice,
                10)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상품 가격은 0 미만일 수 없습니다.");
    }

    @Test
    public void 상품_등록_가격_NULL_저장_방지() {
        // given
        // when & then
        // Product 객체 생성 시점에 예외가 발생하는지 검증
        assertThatThrownBy(() -> new Product(
                1L,
                "Negative Price Item",
                null,
                10)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상품 가격은 0 미만일 수 없습니다.");
    }

    @Test
    public void 상품_등록_재고_음수_저장_방지() {
        // given
        Integer negativeStock = -1;

        // when & then
        // Product 객체 생성 시점에 예외가 발생하는지 검증
        assertThatThrownBy(() -> new Product(
                1L,
                "Negative Stock Item",
                BigDecimal.valueOf(100),
                negativeStock)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상품 재고는 0 미만일 수 없습니다.");
    }

    @Test
    public void 상품_등록_재고_NULL_저장_방지() {
        // when & then
        // Product 객체 생성 시점에 예외가 발생하는지 검증
        assertThatThrownBy(() -> new Product(
                1L,
                "Negative Stock Item",
                BigDecimal.valueOf(100),
                null)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상품 재고는 0 미만일 수 없습니다.");
    }

    @Test
    public void 상품_재고_증가_성공(){
        // given
        Product savedProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);
        productRepository.save(savedProduct);

        // when
        Integer amount = 5;
        var product = productService.charge(1L,amount);

        // then
        assertEquals(product.getProductId(), savedProduct.getProductId());
        assertEquals(product.getStock(), savedProduct.getStock() + amount);
    }

    @Test
    public void 상품_재고_음수_증가_방지_성공(){
        // given
        Product savedProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);
        productRepository.save(savedProduct);

        // when
        Integer amount = -5;
        assertThatThrownBy(() -> productService.charge(1L,amount)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount>0");
    }

    @Test
    public void 상품_재고_NULL_증가_방지_성공(){
        // given
        Product savedProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);
        productRepository.save(savedProduct);

        // when
        assertThatThrownBy(() -> productService.charge(1L,null)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount>0");
    }

    @Test
    public void 상품_재고_차감_성공(){
        // given
        Product savedProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);
        productRepository.save(savedProduct);

        // when
        Integer amount = 5;
        var product = productService.debit(1L,amount);

        // then
        assertEquals(product.getProductId(), savedProduct.getProductId());
        assertEquals(product.getStock(), savedProduct.getStock() - amount);
    }

    @Test
    public void 상품_재고_음수_차감_방지_성공(){
        // given
        Product savedProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);
        productRepository.save(savedProduct);

        // when
        Integer amount = -5;
        assertThatThrownBy(() -> productService.debit(1L,amount)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount>0");
    }

    @Test
    public void 상품_재고_NULL_차감_방지_성공(){
        // given
        Product savedProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);
        productRepository.save(savedProduct);

        // when
        assertThatThrownBy(() -> productService.debit(1L,null)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount>0");
    }

    @Test
    public void 상품_재고_차감_재고부족_예외발생(){
        // given
        Product savedProduct = new Product(1L, "Test", BigDecimal.valueOf(1000), 10);
        productRepository.save(savedProduct);

        // when
        Integer amount = 15;

        // then
        assertThatThrownBy(() -> productService.debit(1L,amount)
        )
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("INSUFFICIENT_STOCK");
    }
}
