package kr.hhplus.be.server.order;

import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.product.InsufficientStockException;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductRepository;
import kr.hhplus.be.server.wallet.Wallet;
import kr.hhplus.be.server.wallet.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class OrderConcurrencyTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderFacade orderFacade;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        walletRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("동시에 주문 생성 요청 시 한 번만 성공한다.")
    void 주문_생성_경쟁_테스트() throws Exception {
        // given
        Long userId = 1L;

        List<Product> savedProducts = new ArrayList<>();
        savedProducts.add(new Product("Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product("Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        walletRepository.save(new Wallet(userId, BigDecimal.valueOf(10000)));

        String idempotencyKey = UUID.randomUUID().toString();

        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    orderFacade.createOrder(userId, orderItemRequests, null, idempotencyKey);
                } catch (Exception e) {
                    // 동시성 테스트이므로 일부 실패는 허용(로깅만)
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then: 주문은 1건만
        var orders = orderRepository.findAll().stream()
                .filter(o -> o.getUserId().equals(userId))
                .toList();
        assertEquals(1, orders.size());

        // 지갑 잔액이 한 번만 차감되었는지
        BigDecimal expectedPaid = BigDecimal.ZERO;
        for (OrderItemRequest item : orderItemRequests) {
            Product p = productRepository.findById(item.productId()).orElseThrow();
            expectedPaid = expectedPaid.add(p.getPrice().multiply(BigDecimal.valueOf(item.quantity())));
        }

        BigDecimal expectedBalance = BigDecimal.valueOf(10000).subtract(expectedPaid);
        var wallet = walletRepository.findById(userId).orElseThrow();
        assertTrue(wallet.getBalance().compareTo(expectedBalance) == 0);

        // 재고도 한 번만 차감되었는지
        var p1 = productRepository.findById(1L).orElseThrow();
        var p2 = productRepository.findById(2L).orElseThrow();
        assertEquals(5, p1.getStock());
        assertEquals(5, p2.getStock());
    }

    @Test
    @DisplayName("여러 유저가 동시에 같은 주문을 발급 요청해도 유효한 주문만 성공한다")
    void 다수_유저_주문_생성_경쟁_테스트() throws Exception {
        // given
        BigDecimal price = BigDecimal.valueOf(100);
        int initialStock = 10;
        int perUserQuantity = 2;

        Product product = new Product("CONC_PRODUCT", price, initialStock);
        product = productRepository.save(product);
        Long productId = product.getProductId(); // ✅ 컴파일 에러 원인(productId 미정의) 해결

        int userCount = 10;
        BigDecimal initialBalance = BigDecimal.valueOf(10_000);

        for (long userId = 1L; userId <= userCount; userId++) {
            walletRepository.save(new Wallet(userId, initialBalance));
        }

        ExecutorService executor = Executors.newFixedThreadPool(userCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        List<Long> successUserIds = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (long userId = 1L; userId <= userCount; userId++) {
            final Long uid = userId;
            String idempotencyKey = UUID.randomUUID().toString();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<OrderItemRequest> items = List.of(new OrderItemRequest(productId, perUserQuantity));

                    orderFacade.createOrder(uid, items, null, idempotencyKey);
                    successCount.incrementAndGet();
                    successUserIds.add(uid);

                } catch (InsufficientStockException e) {
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then
        int expectedSuccess = initialStock / perUserQuantity; // 10/2=5
        assertEquals(expectedSuccess, successCount.get());
        assertEquals(userCount - expectedSuccess, failCount.get());

        assertEquals(expectedSuccess, orderRepository.findAll().size());

        var updatedProduct = productRepository.findById(productId).orElseThrow();
        assertEquals(0, updatedProduct.getStock());

        BigDecimal expectedPaid = price.multiply(BigDecimal.valueOf(perUserQuantity));

        for (long userId = 1L; userId <= userCount; userId++) {
            var wallet = walletRepository.findById(userId).orElseThrow();
            if (successUserIds.contains(userId)) {
                BigDecimal expected = initialBalance.subtract(expectedPaid);
                assertTrue(wallet.getBalance().compareTo(expected) == 0,
                        "userId=" + userId + " balance mismatch");
            } else {
                assertTrue(wallet.getBalance().compareTo(initialBalance) == 0,
                        "userId=" + userId + " should not be charged");
            }
        }
    }

    @Test
    void 동일_idempotencyKey_동시_주문_요청_테스트() throws Exception {
        // given
        Long userId = 1L;

        List<Product> savedProducts = new ArrayList<>();
        savedProducts.add(new Product("Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product("Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        walletRepository.save(new Wallet(userId, BigDecimal.valueOf(10000)));

        String idempotencyKey = UUID.randomUUID().toString();

        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    orderFacade.createOrder(userId, orderItemRequests, null, idempotencyKey);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(1, orderRepository.findAll().size());
    }
}