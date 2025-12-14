package kr.hhplus.be.server.order;

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

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class OrderServiceTest {

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
    private OutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        walletRepository.deleteAll();
        orderRepository.deleteAll();
        outboxRepository.deleteAll();
    }

    @Test
    public void 주문생성_성공() {
        // given
        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();
        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        var order = orderService.createOrderTx(1L, orderItemRequests, 0L, idempotencyKey);

        // then
        assertEquals(order.getOrderItems().stream().count(), orderItemRequests.stream().count());
        assertTrue(order.getPaidAmount().compareTo(BigDecimal.valueOf(1000)) == 0);
    }

    @Test
    public void 주문생성시_재고와_잔액이_차감된다() {
        // given
        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();
        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        var order = orderService.createOrderTx(1L, orderItemRequests, 0L, idempotencyKey);

        // then
        var p1 = productRepository.findById(1L).get();
        var p2 = productRepository.findById(2L).get();
        assertEquals(5, p1.getStock());  // 10 → 5
        assertEquals(5, p2.getStock());  // 10 → 5

        var wallet = walletRepository.findById(1L).get();
        assertTrue(BigDecimal.valueOf(9000).compareTo(wallet.getBalance()) == 0);
    }

    @Test
    public void 재고부족시_롤백_실패() {
        // given
        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 3));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();

        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        assertThatThrownBy(() -> orderService.createOrderTx(1L, orderItemRequests, 0L, idempotencyKey))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("INSUFFICIENT_STOCK");

        // then
        assertTrue(walletService.getBalance(1L).compareTo(BigDecimal.valueOf(10000)) == 0);
        assertEquals(productService.getProduct(1L).stock(), 3);
        assertEquals(productService.getProduct(2L).stock(), 10);
    }

    @Test
    public void 상품_없음_시_롤백_실패() {
        // given
        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();
        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        assertThatThrownBy(() -> orderService.createOrderTx(1L, orderItemRequests, 0L, idempotencyKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRODUCT_NOT_FOUND");

        // then
        assertTrue(walletService.getBalance(1L).compareTo(BigDecimal.valueOf(10000)) == 0);
        assertEquals(productService.getProduct(1L).stock(), 10);
    }

    @Test
    public void 포인트부족시_롤백_실패() {
        // given
        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(500));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();
        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        assertThatThrownBy(() -> orderService.createOrderTx(1L, orderItemRequests, 0L, idempotencyKey))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("INSUFFICIENT_BALANCE");

        // then
        assertTrue(walletService.getBalance(1L).compareTo(BigDecimal.valueOf(500)) == 0);
        assertEquals(productService.getProduct(1L).stock(), 10);
        assertEquals(productService.getProduct(2L).stock(), 10);
    }

    @Test
    public void 같은_idempotencyKey로_두번_호출하면_주문은_한번만_생성된다() {
        // given
        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();

        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        Order o1 = orderService.createOrderTx(1L, orderItemRequests, 0L, idempotencyKey);
        Order o2 = orderService.createOrderTx(1L, orderItemRequests, null, idempotencyKey);

        // then
        assertEquals(o1.getOrderId(), o2.getOrderId());
        assertEquals(1, orderRepository.findAll().size());
    }

    @Test
    public void 주문생성시_Outbox에_ORDER_이벤트가_기록된다()
    {
        // given
        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();
        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        var order = orderService.createOrderTx(1L, orderItemRequests, 0L, idempotencyKey);

        // then
        var events = outboxRepository.findAll();
        assertEquals(1, events.size());

        Outbox event = events.get(0);
        assertEquals("ORDER", event.getAggregateType());
        assertEquals(order.getOrderId().toString(), event.getAggregateId());
        assertNotNull(event.getPayload());
        // 상태 enum 이름에 맞춰서
        assertEquals(Outbox.OutboxStatus.PENDING, event.getStatus());
    }

    @Test
    public void 단일주문조회_성공() {
        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();
        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        var createdOrder = orderService.createOrderTx(1L, orderItemRequests, 0L, idempotencyKey);

        // then
        var order = orderService.getOrder(createdOrder.getOrderId());
        assertEquals(order.getUserId(), createdOrder.getUserId());
        assertEquals(order.getStatus(), OrderStatus.ORDERED);
    }

    @Test
    public void 단일주문조회_실패_예외발생() {
        // given
        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(1L, BigDecimal.valueOf(500));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();
        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));

        assertThatThrownBy(() -> orderService.createOrderTx(1L, orderItemRequests, 0L, idempotencyKey))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("INSUFFICIENT_BALANCE");

        // then
        assertThatThrownBy(() -> orderService.getOrder(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORDER_NOT_FOUND");
    }

    @Test
    public void ID리스트로_주문조회_성공() {
        // given
        List<Order> savedOrders = new ArrayList<Order>();
        savedOrders.add(new Order(1L));
        savedOrders.add(new Order(1L));
        orderRepository.saveAll(savedOrders);

        // when
        var orders = orderService.getOrders();

        // then
        assertEquals(orders.stream().count(), 2);
        assertEquals(orders.get(0).getUserId(), 1L);
        assertEquals(orders.get(1).getUserId(), 1L);
    }

    @Test
    public void 사용자ID로_주문조회_성공() {
        // given
        List<Order> savedOrders = new ArrayList<Order>();
        savedOrders.add(new Order(1L));
        savedOrders.add(new Order(2L));
        savedOrders.add(new Order(1L));
        orderRepository.saveAll(savedOrders);

        // when
        var orders = orderService.getOrders(1L);

        // then
        assertEquals(orders.stream().count(), 2);
        assertEquals(orders.get(0).getOrderId(), 1L);
        assertEquals(orders.get(1).getOrderId(), 3L);
    }
}
