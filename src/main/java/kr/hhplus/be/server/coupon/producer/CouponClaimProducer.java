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
            String key = String.valueOf(msg.couponId()); // ✅ couponId로 파티션 고정
            String json = objectMapper.writeValueAsString(msg);
            kafkaTemplate.send(topic, key, json);
        } catch (Exception e) {
            throw new IllegalStateException("KAFKA_PRODUCE_FAILED", e);
        }
    }

}
