package kr.hhplus.be.server.outbox;

import kr.hhplus.be.server.outbox.Outbox.OutboxStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxWorker {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private DataPlatformTransmitter transmitter; // Mock 전송 모듈

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
            boolean success = transmitter.send(
                    event.getId(),
                    event.getAggregateType(),
                    event.getPayload());

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
}