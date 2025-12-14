package kr.hhplus.be.server.order;

import kr.hhplus.be.server.coupon.UserCoupon;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.outbox.Outbox;
import kr.hhplus.be.server.outbox.OutboxRepository;
import kr.hhplus.be.server.product.InsufficientStockException;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductRepository;
import kr.hhplus.be.server.product.ProductService;
import kr.hhplus.be.server.wallet.InsufficientBalanceException;
import kr.hhplus.be.server.wallet.Wallet;
import kr.hhplus.be.server.wallet.WalletRepository;
import kr.hhplus.be.server.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class OrderConcurrencyTest {
    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletService walletService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

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

        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(userId, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();
        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));


        int threadCount = 10; // 동시에 10번 발급 시도
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);


        // when : 여러 스레드가 동시에 createOrder 호출
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 모두 준비될 때까지 대기
                    orderFacade.createOrder(userId, orderItemRequests, null, idempotencyKey);

                } catch (Exception e) {
                    // 예상치 못한 예외도 실패로 카운트
                    e.printStackTrace();

                } finally {
                    doneLatch.countDown();
                }
            });
        }
        // 모든 스레드를 동시에 출발
        startLatch.countDown();

        // 모든 작업이 끝날 때까지 대기
        doneLatch.await();
        executor.shutdown();

        // then

        // 1) DB에 주문은 1건만 있어야 한다
        var orders = orderRepository.findAll().stream()
                .filter(o -> o.getUserId().equals(userId))
                .toList();
        assertEquals(1, orders.size());


        // 2) 지갑 잔액은 한 번만 차감되었는지
        BigDecimal expectedPaid = BigDecimal.ZERO;
        for (OrderItemRequest item : orderItemRequests) {
            Product p = productRepository.findById(item.productId()).orElseThrow();
            expectedPaid = expectedPaid.add(p.getPrice().multiply(BigDecimal.valueOf(item.quantity())));
        }

        BigDecimal expectedBalance = BigDecimal.valueOf(10000).subtract(expectedPaid);
        var wallet = walletRepository.findById(userId).get();
        assertTrue(wallet.getBalance().compareTo(expectedBalance) == 0);

        // 3) 재고도 한 번만 차감되었는지
        var p1 = productRepository.findById(1L).get();
        var p2 = productRepository.findById(2L).get();
        assertEquals(5, p1.getStock());
        assertEquals(5, p2.getStock());
    }

    @Test
    @DisplayName("여러 유저가 동시에 같은 주문을 발급 요청해도 유효한 주문만 성공한다")
    void 다수_유저_주문_생성_경쟁_테스트() throws Exception {
        // given
        Long productId = 1L;
        BigDecimal price = BigDecimal.valueOf(100);
        int initialStock = 10;
        int perUserQuantity = 2;

        // 상품 1개 (재고 10개)
        Product product = new Product(productId, "CONC_PRODUCT", price, initialStock);
        productRepository.save(product);

        int userCount = 10;
        BigDecimal initialBalance = BigDecimal.valueOf(10_000);

        // 유저 10명 지갑 세팅
        for (long userId = 1L; userId <= userCount; userId++) {
            walletRepository.save(new Wallet(userId, initialBalance));
        }

        ExecutorService executor = Executors.newFixedThreadPool(userCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        // 어떤 유저가 성공했는지 확인용
        List<Long> successUserIds = new java.util.concurrent.CopyOnWriteArrayList<>();

        // when: 각 유저가 동시에 같은 상품 2개 주문 시도
        for (long userId = 1L; userId <= userCount; userId++) {
            final Long uid = userId;
            String idempotencyKey = UUID.randomUUID().toString(); // 각 요청은 서로 다른 키
            executor.submit(() -> {
                try {
                    startLatch.await();

                    List<OrderItemRequest> items =
                            List.of(new OrderItemRequest(productId, perUserQuantity));

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
        int expectedSuccess = initialStock / perUserQuantity; // 10 / 2 = 5
        assertEquals(expectedSuccess, successCount.get());
        assertEquals(userCount - expectedSuccess, failCount.get());

        // 1) 실제 DB에 생성된 주문 수
        var orders = orderRepository.findAll();
        assertEquals(expectedSuccess, orders.size());

        // 2) 상품 재고는 0이어야 함
        var updatedProduct = productRepository.findById(productId).get();
        assertEquals(0, updatedProduct.getStock());

        // 3) 성공한 유저들의 지갑은 2 * price 만큼 차감, 실패한 유저들은 그대로
        BigDecimal expectedPaid = price.multiply(BigDecimal.valueOf(perUserQuantity)); // 100 * 2 = 200

        for (long userId = 1L; userId <= userCount; userId++) {
            var wallet = walletRepository.findById(userId).get();
            if (successUserIds.contains(userId)) {
                // 성공한 유저
                BigDecimal expected = initialBalance.subtract(expectedPaid);
                assertTrue(wallet.getBalance().compareTo(expected) == 0,
                        "userId=" + userId + " balance mismatch");
            } else {
                // 실패한 유저
                assertTrue(wallet.getBalance().compareTo(initialBalance) == 0,
                        "userId=" + userId + " should not be charged");
            }
        }
    }

    @Test
    void 동일_idempotencyKey_동시_주문_요청_테스트() throws Exception {
        // given
        Long userId = 1L;

        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();

        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));


        int threadCount = 10; // 동시에 10번 발급 시도
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // when : 여러 스레드가 동시에 createOrder 호출
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 모두 준비될 때까지 대기
                    orderFacade.createOrder(userId, orderItemRequests, null, idempotencyKey);

                } catch (Exception e) {
                    // 예상치 못한 예외도 실패로 카운트
                    e.printStackTrace();

                } finally {
                    doneLatch.countDown();
                }
            });
        }
        // 모든 스레드를 동시에 출발
        startLatch.countDown();

        // 모든 작업이 끝날 때까지 대기
        doneLatch.await();
        executor.shutdown();


        assertEquals(1, orderRepository.findAll().size());
    }
}
