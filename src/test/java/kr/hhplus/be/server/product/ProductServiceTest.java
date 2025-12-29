package kr.hhplus.be.server.product;

import kr.hhplus.be.server.product.DTO.CreateProductRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
@Transactional
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
        Product savedProduct = new Product("Test", BigDecimal.valueOf(1000), 10);
        savedProduct = productRepository.save(savedProduct);

        // when
        var product = productService.getProduct(savedProduct.getProductId());

        // then
        assertEquals(savedProduct.getProductId(), product.productId());
        assertEquals(savedProduct.getName(), product.name());
    }

    @Test
    public void 상품_목록_조회_성공() {
        // given
        var savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product("Test", BigDecimal.valueOf(1000), 10));
        savedProducts.add(new Product("Test2", BigDecimal.valueOf(1000), 10));
        savedProducts.add(new Product("Test3", BigDecimal.valueOf(1000), 10));
        productRepository.saveAll(savedProducts);

        // when
        var products = productService.getProducts();

        // then
        assertEquals(3, products.size());
        assertNotNull(products.get(0).productId());
        assertNotNull(products.get(1).productId());
        assertNotNull(products.get(2).productId());
    }

    @Test
    public void 빈_상품_목록_조회() {
        // when
        var products = productService.getProducts();

        // then
        assertEquals(List.of(), products);
    }

    @Test
    public void 존재하지_않는_상품_조회_예외발생() {
        // given
        Long nonExistentId = 999L;

        // when && then
        assertThatThrownBy(() -> productService.getProduct(nonExistentId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRODUCT_NOT_FOUND");
    }

    @Test
    public void 상품_가격_조회_성공() {
        // given
        Product savedProduct = new Product("Test", BigDecimal.valueOf(1000), 10);
        savedProduct = productRepository.save(savedProduct);

        // when
        var product = productService.getProduct(savedProduct.getProductId());

        // then
        assertEquals(savedProduct.getPrice(), product.price());
        assertEquals(savedProduct.getStock(), product.stock());
    }

    @Test
    public void 상품_등록_성공() {
        // given
        Product newProduct = new Product("Test", BigDecimal.valueOf(1000), 10);

        // when
        var created = productService.createProductTx(new CreateProductRequest("Test", BigDecimal.valueOf(1000), 10));

        // then
        assertNotNull(created.getProductId());
        assertEquals(newProduct.getName(), created.getName());
        assertEquals(newProduct.getPrice(), created.getPrice());
        assertEquals(newProduct.getStock(), created.getStock());
    }

    @Test
    public void 상품_등록_가격_음수_저장_방지() {
        // given
        BigDecimal negativePrice = BigDecimal.valueOf(-100);

        // when & then
        assertThatThrownBy(() -> new Product("Negative Price Item", negativePrice, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price>=0");
    }

    @Test
    public void 상품_등록_가격_NULL_저장_방지() {
        // when & then
        assertThatThrownBy(() -> new Product("Null Price Item", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price>=0");
    }

    @Test
    public void 상품_등록_재고_음수_저장_방지() {
        // given
        Integer negativeStock = -1;

        // when & then
        assertThatThrownBy(() -> new Product("Negative Stock Item", BigDecimal.valueOf(100), negativeStock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stock>=0");
    }

    @Test
    public void 상품_등록_재고_NULL_저장_방지() {
        // when & then
        assertThatThrownBy(() -> new Product("Null Stock Item", BigDecimal.valueOf(100), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stock>=0");
    }

    @Test
    public void 상품_등록_이름_NULL_방지() {
        assertThatThrownBy(() -> new Product(null, BigDecimal.valueOf(1000), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name_required");
    }

    @Test
    public void 상품_등록_이름_빈문자열_방지() {
        assertThatThrownBy(() -> new Product("   ", BigDecimal.valueOf(1000), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name_required");
    }

    @Test
    public void 상품_재고_증가_성공() {
        // given
        Product savedProduct = new Product("Test", BigDecimal.valueOf(1000), 10);
        savedProduct = productRepository.save(savedProduct);

        // when
        Integer amount = 5;
        var updated = productService.chargeTx(savedProduct.getProductId(), amount);

        // then
        assertEquals(savedProduct.getProductId(), updated.getProductId());
        assertEquals(10 + amount, updated.getStock());
    }

    @Test
    public void 상품_재고_음수_증가_방지_성공() {
        // given
        Product savedProduct = new Product("Test", BigDecimal.valueOf(1000), 10);
        savedProduct = productRepository.save(savedProduct);

        // when && then
        Integer amount = -5;
        Long id = savedProduct.getProductId();

        assertThatThrownBy(() -> productService.chargeTx(id, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount>0");
    }

    @Test
    public void 상품_재고_NULL_증가_방지_성공() {
        // given
        Product savedProduct = new Product("Test", BigDecimal.valueOf(1000), 10);
        savedProduct = productRepository.save(savedProduct);

        // when && then
        Long id = savedProduct.getProductId();

        assertThatThrownBy(() -> productService.chargeTx(id, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount>0");
    }

    @Test
    public void 상품_재고_차감_성공() {
        // given
        Product savedProduct = new Product("Test", BigDecimal.valueOf(1000), 10);
        savedProduct = productRepository.save(savedProduct);

        // when
        Integer amount = 5;
        var updated = productService.debitTx(savedProduct.getProductId(), amount);

        // then
        assertEquals(savedProduct.getProductId(), updated.getProductId());
        assertEquals(10 - amount, updated.getStock());
    }

    @Test
    public void 상품_재고_음수_차감_방지_성공() {
        // given
        Product savedProduct = new Product("Test", BigDecimal.valueOf(1000), 10);
        savedProduct = productRepository.save(savedProduct);

        // when && then
        Integer amount = -5;
        Long id = savedProduct.getProductId();

        assertThatThrownBy(() -> productService.debitTx(id, amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount>0");
    }

    @Test
    public void 상품_재고_NULL_차감_방지_성공() {
        // given
        Product savedProduct = new Product("Test", BigDecimal.valueOf(1000), 10);
        savedProduct = productRepository.save(savedProduct);

        // when && then
        Long id = savedProduct.getProductId();

        assertThatThrownBy(() -> productService.debitTx(id, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount>0");
    }

    @Test
    public void 상품_재고_차감_재고부족_예외발생() {
        // given
        Product savedProduct = new Product("Test", BigDecimal.valueOf(1000), 10);
        savedProduct = productRepository.save(savedProduct);

        // when && then
        Integer amount = 15;
        Long id = savedProduct.getProductId();

        assertThatThrownBy(() -> productService.debitTx(id, amount))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("INSUFFICIENT_STOCK");
    }
}