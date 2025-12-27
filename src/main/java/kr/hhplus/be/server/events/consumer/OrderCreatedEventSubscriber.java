package kr.hhplus.be.server.events.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.outbox.DataPlatformTransmitter;
import kr.hhplus.be.server.outbox.messages.OrderCreatedEventMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OrderCreatedEventSubscriber implements MessageListener {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataPlatformTransmitter transmitter;

    @Value("${app.redis.dlq.order-created:dlq:order-created:v1}")
    private String dlqKey;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);

        final OrderCreatedEventMessage evt;
        try {
            evt = objectMapper.readValue(json, OrderCreatedEventMessage.class);
        } catch (JsonProcessingException e) {
            // 역직렬화 실패는 재시도해도 성공 확률이 낮으니 DLQ 적재
            pushDlq("DESERIALIZE_ERROR", null, null, json, e);
            return;
        }

        String dedupKey = "dedup:data-platform:" + evt.eventId();
        String processingKey = "processing:data-platform:" + evt.eventId();

        if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
            return;
        }

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(processingKey, "1", Duration.ofMinutes(3));

        if (Boolean.FALSE.equals(acquired)) {
            return;
        }

        try {
            String payloadJson = normalizePayloadToJson(evt.payload());

            // mock API 전송 (실패 시 예외/false 여부는 transmitter 구현에 따라)
            transmitter.send(evt.eventId(), evt.eventType(), payloadJson);

            // 성공 후 dedup 확정
            redisTemplate.opsForValue().set(dedupKey, "1", Duration.ofDays(7));

        } catch (Exception e) {
            pushDlq("TRANSMIT_ERROR", evt.eventId(), evt.eventType(), json, e);
        } finally {
            redisTemplate.delete(processingKey);
        }
    }

    private String normalizePayloadToJson(Object payload) throws JsonProcessingException {
        if (payload == null) return "null";
        if (payload instanceof String s) return s;
        return objectMapper.writeValueAsString(payload);
    }

    private void pushDlq(String reason, Long eventId, String eventType, String rawMessage, Exception e) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("reason", reason);
        fields.put("error", safe(e == null ? null : e.getMessage()));
        fields.put("occurredAt", Instant.now().toString());

        // ✅ 분석/리플레이를 위해 반드시 남겨두는 필드
        fields.put("eventId", eventId == null ? "" : String.valueOf(eventId));
        fields.put("eventType", eventType == null ? "" : eventType);

        // 가시성 목적의 초기값(실제 누적은 Worker에서 별도 키로 관리)
        fields.put("retryCount", "0");

        // 원본 pub/sub 메시지(JSON)
        fields.put("message", rawMessage);

        MapRecord<String, String, String> record =
                StreamRecords.newRecord()
                        .in(dlqKey)
                        .ofMap(fields);

        redisTemplate.opsForStream().add(record);
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
