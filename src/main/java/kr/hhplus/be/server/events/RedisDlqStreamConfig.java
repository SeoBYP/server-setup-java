package kr.hhplus.be.server.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.PostConstruct;
import java.util.Objects;

@Configuration
public class RedisDlqStreamConfig {

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.redis.dlq.order-created:dlq:order-created:v1}")
    private String dlqStreamKey;

    @Value("${app.redis.dlq-group.order-created:cg:dlq:order-created:v1}")
    private String dlqGroup;

    public RedisDlqStreamConfig(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void ensureDlqGroup() {
        // 1) Stream이 없으면 0-byte entry 하나 넣었다가 지우는 방식으로 생성할 수도 있지만
        // 여기서는 RedisConnection의 streamCommands로 그룹 생성 시도/예외 무시 패턴을 사용합니다.
        try (RedisConnection conn = connectionFactory.getConnection()) {

            // Stream 존재 여부 확인 (없으면 XGROUP CREATE가 실패할 수 있음)
            boolean streamExists = Boolean.TRUE.equals(redisTemplate.hasKey(dlqStreamKey));
            if (!streamExists) {
                // Stream을 생성하기 위한 더미 entry 1개 추가 후 즉시 삭제(옵션)
                String id = Objects.toString(
                        redisTemplate.opsForStream().add(dlqStreamKey, java.util.Map.of("bootstrap", "1"))
                );
                if (id != null) {
                    redisTemplate.opsForStream().delete(dlqStreamKey, id);
                }
            }

            // 2) Group 생성 (이미 있으면 예외 -> 무시)
            conn.streamCommands().xGroupCreate(
                    dlqStreamKey.getBytes(),
                    dlqGroup,
                    ReadOffset.from("0-0"), // 처음부터 읽기
                    true // MKSTREAM 유사 효과(드라이버/버전에 따라 동작 차이 있을 수 있어 위에서 stream 생성도 같이 함)
            );
        } catch (Exception e) {
            // 이미 그룹이 존재하는 경우가 대부분. 괜히 실패로 처리하지 말고 무시.
            // 필요하면 로그만 남기세요.
        }
    }
}
