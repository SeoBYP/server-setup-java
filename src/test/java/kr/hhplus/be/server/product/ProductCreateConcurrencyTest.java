package kr.hhplus.be.server.product;

import kr.hhplus.be.server.product.DTO.CreateProductRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class ProductCreateConcurrencyTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductFacade productFacade;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("동일한 CreateProductRequest로 동시에 상품 생성 시도 시 DB에는 1건만 생성된다(DB UNIQUE 기반)")
    void 상품_생성_동시성_제어_테스트() throws Exception {
        CreateProductRequest req = new CreateProductRequest(
                "CONC_CREATE_PRODUCT",
                BigDecimal.valueOf(1000),
                10
        );

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        ConcurrentLinkedQueue<Long> createdIds = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Product created = productFacade.createProduct(req);
                    assertNotNull(created.getProductId());
                    createdIds.add(created.getProductId());
                    success.incrementAndGet();
                } catch (Exception e) {
                    // DB UNIQUE 충돌 등을 실패로 카운트(정책에 따라 0일 수도 있음)
                    fail.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then: 최종 DB에는 1건만
        List<Product> all = productRepository.findAll();
        assertEquals(1, all.size(), "DB에는 상품이 1건만 생성되어야 합니다.");

        Product p = all.get(0);
        assertEquals("CONC_CREATE_PRODUCT", p.getName());
        assertTrue(p.getPrice().compareTo(BigDecimal.valueOf(1000)) == 0);
        assertEquals(10, p.getStock());

        // 멱등 반환 정책이라면: 성공은 threadCount일 수도 있고, 실패는 0일 수도 있습니다.
        // 중요한 건 반환된 productId가 모두 동일해야 함
        assertFalse(createdIds.isEmpty(), "최소 1회 이상은 생성/반환되어야 합니다.");
        Long firstId = createdIds.peek();
        assertTrue(createdIds.stream().allMatch(id -> id.equals(firstId)),
                "동일 요청은 모두 같은 productId를 반환해야 합니다. (멱등)");
        assertEquals(threadCount, success.get() + fail.get());
    }

    @Test
    @DisplayName("서로 다른 payload로 동시에 상품 생성 시도 시 각각 생성될 수 있다(락 키가 다름)")
    void 서로다른_요청은_각각_생성된다() throws Exception {
        CreateProductRequest req1 = new CreateProductRequest("P1", BigDecimal.valueOf(1000), 10);
        CreateProductRequest req2 = new CreateProductRequest("P2", BigDecimal.valueOf(2000), 20);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        executor.submit(() -> {
            try {
                startLatch.await();
                productFacade.createProduct(req1);
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                productFacade.createProduct(req2);
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        List<Product> all = productRepository.findAll();
        assertEquals(2, all.size(), "서로 다른 요청 payload는 각각 생성될 수 있습니다.");
    }
}