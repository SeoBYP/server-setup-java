package kr.hhplus.be.server.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.outbox.Outbox.OutboxStatus;
import kr.hhplus.be.server.outbox.messages.OrderCreatedEventMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxWorker {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.redis.channels.order-created:local.order.order-created.v1}")
    private String orderCreatedChannel;

    // 예: 10초마다 PENDING 상태의 이벤트를 처리합니다. (실제 운영 환경에서는 전용 스케줄러를 사용)
    @Scheduled(fixedDelay = 10000)
    @Transactional // Outbox 상태 업데이트를 위한 트랜잭션
    public void processPendingEvents() {
        System.out.println("--- [Outbox Worker] Checking for PENDING events...");

        // 1. PENDING 이벤트 배치 조회
        List<Outbox> pendingEvents = outboxRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        if (pendingEvents.isEmpty()) {
            System.out.println("--- [Outbox Worker] No pending events found.");
            return;
        }

        for (Outbox event : pendingEvents) {
            // 2. 외부 전송 시도
            boolean success = publish(event);

            // 3. 결과에 따라 상태 업데이트
            if (success) {
                event.markAsSent();
                System.out.printf("--- [Outbox Worker] Event ID %d marked as SENT.\n", event.getId());
            } else {
                event.markAsFailed(); // 재시도 로직을 위해 FAILED로 마크
                System.err.printf("--- [Outbox Worker] Event ID %d marked as FAILED (will be retried).\n", event.getId());
            }
            // 4. 트랜잭션 커밋 시 상태 변경 DB에 반영
            outboxRepository.save(event);
        }
        System.out.println("--- [Outbox Worker] Finished processing batch.");
    }

    private boolean publish(Outbox event) {
        try {
            // Outbox에 저장된 aggregateId가 orderId라면 그대로 사용
            Long orderId = Long.valueOf(event.getAggregateId());

            // Outbox.payload가 이미 JSON 문자열이라면 그대로 payload로 넣어도 됩니다.
            // (subscriber에서 evt.payload().toString()로 쓰는 패턴과 호환)
            OrderCreatedEventMessage msg = new OrderCreatedEventMessage(
                    event.getId(),           // eventId = outboxId (dedup 핵심)
                    event.getEventType(),    // "ORDER_CREATED"
                    1,                       // schemaVersion
                    orderId,
                    event.getPayload()       // JSON string
            );

            String json = objectMapper.writeValueAsString(msg);

            redisTemplate.convertAndSend(orderCreatedChannel, json);

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

}