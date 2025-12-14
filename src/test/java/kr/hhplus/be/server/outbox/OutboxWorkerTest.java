package kr.hhplus.be.server.outbox;

import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.order.OrderService;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductRepository;
import kr.hhplus.be.server.wallet.Wallet;
import kr.hhplus.be.server.wallet.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class OutboxWorkerTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OutboxWorker outboxWorker;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WalletRepository walletRepository;

    @MockitoBean
    private DataPlatformTransmitter transmitter;

    @Test
    public void Outbox_전송실패시_STATUS_FAILED로_변경된다()
    {
        // given: 상품 + 지갑 + 주문 생성 → Outbox 1건(PENDING) 생성됨
        List<Product> products = List.of(
                new Product(1L, "Test1", BigDecimal.valueOf(100), 5)
        );
        productRepository.saveAll(products);

        walletRepository.save(new Wallet(1L, BigDecimal.valueOf(10000)));

        String key = UUID.randomUUID().toString();
        List<OrderItemRequest> items = List.of(new OrderItemRequest(1L, 2));

        var order = orderService.createOrderTx(1L, items, null, key);

        // outbox DB 확인
        Outbox event = outboxRepository.findAll().get(0);
        assertEquals(Outbox.OutboxStatus.PENDING, event.getStatus());

        // ★ Mock: transmitter.send() 호출 시 무조건 실패하게 만든다.
        when(transmitter.send(any(), any(), any())).thenReturn(false);

        // when: Worker 실행
        outboxWorker.processPendingEvents();

        // then: 상태가 FAILED 로 바뀌었는지 확인
        Outbox updated = outboxRepository.findById(event.getId()).get();
        assertEquals(Outbox.OutboxStatus.FAILED, updated.getStatus());
    }
}
