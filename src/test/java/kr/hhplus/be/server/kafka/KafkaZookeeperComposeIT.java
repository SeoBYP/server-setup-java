package kr.hhplus.be.server.kafka;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.DockerComposeContainer;

import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class KafkaZookeeperComposeIT {

    // ✅ 프로젝트 경로에 맞게 조정하세요 (로그에선 "<project>/docker" 아래를 복사했다고 나왔습니다)
    private static final File COMPOSE_FILE = new File("docker/kafka-zk-compose.yml");

    private static final DockerComposeContainer<?> COMPOSE =
            new DockerComposeContainer<>(COMPOSE_FILE)
                    // kafka 컨테이너 외부 노출 포트는 compose에서 9092:9092로 고정되어 있으므로 여기서는 "노출"만 선언
                    .withExposedService("kafka", 9092)
                    .withExposedService("zookeeper", 2181);

    static {
        COMPOSE.start();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void zookeeper_카프카_compose_기동_후_단일_메시지_송수신이_된다() {
        String bootstrap = "localhost:9092"; // ✅ compose의 advertised.listener가 localhost:9092 이므로 host에서 이 주소가 맞습니다.
        String topic = "order-created";
        String key = "k1";
        String payload = "hello-kafka-1";

        // 1) Kafka가 "진짜로" 준비될 때까지 대기 (포트/로그 기반보다 훨씬 안정적)
        awaitKafkaReady(bootstrap);

        // 2) 토픽 보장 (이미 있으면 무시, 그 외 실패는 드러나게)
        ensureTopicExists(bootstrap, topic, 1, (short) 1);

        // 3) Produce
        try (KafkaProducer<String, String> producer = newProducer(bootstrap)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
            producer.send(record).get(); // ✅ 전송 확정
        } catch (Exception e) {
            throw new RuntimeException("Kafka produce failed", e);
        }

        // 4) Consume (auto-commit OFF + Awaitility로 수신 확정)
        String groupId = "test-consumer-" + UUID.randomUUID();
        try (KafkaConsumer<String, String> consumer = newConsumer(bootstrap, groupId)) {
            consumer.subscribe(List.of(topic));

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

                boolean found = false;
                for (ConsumerRecord<String, String> record : records.records(topic)) {
                    if (Objects.equals(record.value(), payload)) {
                        found = true;
                        break;
                    }
                }

                assertThat(found).isTrue();
                consumer.commitSync();
            });

        }
    }

    // ----------------------------
    // Helpers
    // ----------------------------

    private static KafkaProducer<String, String> newProducer(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);

        // 안정성 기본값
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private static KafkaConsumer<String, String> newConsumer(String bootstrap, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);

        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // ✅ 테스트 재현성: 자동 커밋 끄고, 우리가 commitSync로 확정
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private static void awaitKafkaReady(String bootstrap) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (AdminClient admin = AdminClient.create(props)) {
                // ✅ 이 호출이 성공하면 "브로커가 메타데이터 요청을 정상 처리" 가능한 상태
                String clusterId = admin.describeCluster().clusterId().get(3, TimeUnit.SECONDS);
                assertThat(clusterId).isNotBlank();
            }
        });
    }

    private static void ensureTopicExists(String bootstrap, String topic, int partitions, short replicationFactor) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);

        try (AdminClient admin = AdminClient.create(props)) {
            NewTopic newTopic = new NewTopic(topic, partitions, replicationFactor);
            admin.createTopics(Collections.singleton(newTopic)).all().get(10, TimeUnit.SECONDS);

        } catch (ExecutionException e) {
            // ✅ TopicExists만 무시
            if (e.getCause() instanceof TopicExistsException) {
                return;
            }
            throw new RuntimeException("Create topic failed: " + topic, e);

        } catch (Exception e) {
            throw new RuntimeException("Create topic failed: " + topic, e);
        }
    }
}
