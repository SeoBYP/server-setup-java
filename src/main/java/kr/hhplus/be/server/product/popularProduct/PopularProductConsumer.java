package kr.hhplus.be.server.product.popularProduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.outbox.DTO.OrderCreatedEventPayload;
import kr.hhplus.be.server.outbox.Outbox;
import kr.hhplus.be.server.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PopularProductConsumer {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private PopularProductRepository popularProductRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelay = 1000) // 필요시 조정
    @Transactional
    public void processOrderEvents() {

        // ORDER + PENDING + processed=false
        List<Outbox> events =
                outboxRepository.findTop100ByAggregateTypeAndStatusAndProcessedFalseOrderByCreatedAtAsc(
                        "ORDER",
                        Outbox.OutboxStatus.PENDING
                );

        if (events.isEmpty()) return;

        for (Outbox event : events) {
            try {
                // 1. Payload 역직렬화
                OrderCreatedEventPayload payload =
                        objectMapper.readValue(event.getPayload(), OrderCreatedEventPayload.class);

                // 2. 인기 상품 집계 업데이트
                payload.getItems().forEach(item -> {
                    updatePopularProductSales(item.getProductId(), item.getQuantity());
                });

                // 3. 처리 완료 표시
                event.markProcessed();

            } catch (Exception e) {
                // 실패하면 Outbox status = FAILED 로 전환
                event.markAsFailed();
            }
        }
    }

    private void updatePopularProductSales(Long productId, Integer quantity) {
        popularProductRepository.findForUpdateByProductId(productId)
                .ifPresentOrElse(popularProduct -> {
                    popularProduct.addSalesQuantity(quantity);
                    popularProductRepository.save(popularProduct);
                }, () -> {
                    try {
                        popularProductRepository.save(new PopularProduct(productId, quantity));
                    } catch (Exception ex) {
                        // Race condition → UPDATE path 다시 시도
                        popularProductRepository.findForUpdateByProductId(productId)
                                .ifPresent(pp -> {
                                    pp.addSalesQuantity(quantity);
                                    popularProductRepository.save(pp);
                                });
                    }
                });
    }
}