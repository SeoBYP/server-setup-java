package kr.hhplus.be.server.coupon.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.coupon.messages.CouponClaimRequestedMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class CouponClaimProducer {

    @Autowired
    private KafkaTemplate<String,String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.kafka.topics.coupon-claim-requested:coupon-claim-requested.v1}")
    private String topic;

    public void send(CouponClaimRequestedMessage msg) {
        try {
            // 파티션 키는 userId를 사용한다.
            //
            // couponId를 키로 쓰면 동일 쿠폰의 모든 요청이 같은 해시 → 단일 파티션으로 몰린다.
            // 파티션 1개는 컨슈머 그룹 내에서 스레드 1개만 소비하므로,
            // 파티션을 6개로 두고 concurrency를 올려도 병렬 처리가 전혀 늘지 않는다.
            //
            // 선착순 판정의 원자성은 Redis Lua(SISMEMBER+DECR+SADD)가,
            // 중복 발급 차단은 user_coupons의 UNIQUE 제약이 보장하므로
            // 쿠폰 단위 처리 순서는 정합성에 필요하지 않다.
            //
            // userId를 키로 쓰면 파티션 전체로 분산되면서,
            // 동일 사용자의 연속 요청은 같은 파티션에 묶여 순서가 유지된다.
            String key = String.valueOf(msg.userId());
            String json = objectMapper.writeValueAsString(msg);
            kafkaTemplate.send(topic, key, json);
        } catch (Exception e) {
            throw new IllegalStateException("KAFKA_PRODUCE_FAILED", e);
        }
    }

}
