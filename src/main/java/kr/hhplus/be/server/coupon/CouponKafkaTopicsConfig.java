package kr.hhplus.be.server.coupon;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CouponKafkaTopicsConfig {

    @Value("${app.kafka.topics.coupon-claim-requested:coupon-claim-requested.v1}")
    private String claimRequestedTopic;

    @Value("${app.kafka.topics.coupon-claim-replied:coupon-claim-replied.v1}")
    private String claimRepliedTopic;

    // 파티션 수 = 컨슈머 그룹 내 최대 병렬도.
    // 부하 테스트에서 컨슈머 6스레드가 전부 DB 응답 대기(FullReadInputStream.readFully)로
    // 포화된 것이 확인되어 12로 증설한다.
    private static final int PARTITIONS = 12;
    // 단일 브로커 실습 환경 기준
    private static final short REPLICATION_FACTOR = 1;

    @Bean
    public NewTopic couponClaimRequestedTopic() {
        return new NewTopic(claimRequestedTopic, PARTITIONS, REPLICATION_FACTOR);
    }

    @Bean
    public NewTopic couponClaimRepliedTopic() {
        return new NewTopic(claimRepliedTopic, PARTITIONS, REPLICATION_FACTOR);
    }
}
