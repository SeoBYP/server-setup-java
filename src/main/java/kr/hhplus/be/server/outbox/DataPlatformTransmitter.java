package kr.hhplus.be.server.outbox;

import org.springframework.stereotype.Component;

// 외부 데이터 플랫폼으로 데이터를 전송하는 모듈 Mock
@Component
public class DataPlatformTransmitter {

    public boolean send(Long outboxId, String eventType,  String payload) {
        System.out.printf("👉 [DATA PLATFORM MOCK] Transmitting event. Outbox ID: %d, Type: %s, Payload: %s\n",
                outboxId, eventType, payload);

        // Mocking: 10번 중 1번은 전송 실패를 가정하여 재시도 로직을 테스트할 수 있게 합니다.
        if (Math.random() < 0.1) {
            System.err.printf("❌ [DATA PLATFORM MOCK] Transmission FAILED for Outbox ID: %d\n", outboxId);
            return false;
        }

        System.out.printf("✅ [DATA PLATFORM MOCK] Transmission SUCCESS for Outbox ID: %d\n", outboxId);
        return true;
    }
}