package kr.hhplus.be.server.kafka;

import kr.hhplus.be.server.kafka.testconsumer.TestKafkaConsumer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.DockerComposeContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class KafkaSpringComposeIT {

    private static final File COMPOSE_FILE = new File("docker/kafka-zk-compose.yml");

    private static final DockerComposeContainer<?> COMPOSE =
            new DockerComposeContainer<>(COMPOSE_FILE)
                    .withExposedService("kafka", 9092)
                    .withExposedService("zookeeper", 2181);

    static {
        COMPOSE.start();
    }

    private static String bootstrap() {
        String host = COMPOSE.getServiceHost("kafka", 9092);
        Integer port = COMPOSE.getServicePort("kafka", 9092);
        return host + ":" + port;
    }

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaSpringComposeIT::bootstrap);

        // (선택) 테스트가 예측 가능하도록 consumer 기본값을 명시
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("spring.kafka.consumer.enable-auto-commit", () -> "false");
        registry.add("spring.kafka.consumer.group-id", () -> "it-" + UUID.randomUUID());
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired TestKafkaConsumer testConsumer;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void spring_kafka_단일_메시지_송수신이_된다() {
        String bootstrap = bootstrap();
        String topic = "order-created";
        String key = "k1";
        String payload = "hello-kafka-1";

        awaitKafkaReady(bootstrap);
        ensureTopicExists(bootstrap, topic, 1, (short) 1);

        // consumer가 받을 payload 기대값 세팅
        testConsumer.reset(payload);

        // produce
        kafkaTemplate.send(topic, key, payload);

        // consume assert
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(testConsumer.isReceived()).isTrue();
        });
    }

    private static void awaitKafkaReady(String bootstrap) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (AdminClient admin = AdminClient.create(props)) {
                String clusterId = admin.describeCluster()
                        .clusterId()
                        .get(3, TimeUnit.SECONDS);
                assertThat(clusterId).isNotBlank();
            }
        });
    }

    private static void ensureTopicExists(String bootstrap, String topic, int partitions, short replicationFactor) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);

        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(Collections.singleton(new NewTopic(topic, partitions, replicationFactor)))
                    .all()
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof TopicExistsException) return;
            throw new RuntimeException("Create topic failed: " + topic, e);
        }
    }

    @Configuration
    @EnableKafka
    static class TestConfig {

        @Bean
        TestKafkaConsumer testKafkaConsumer() {
            return new TestKafkaConsumer();
        }

        // ---- Producer (KafkaTemplate) ----
        @Bean
        ProducerFactory<String, String> producerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaSpringComposeIT.bootstrap());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

            // 테스트 안정성(선택)
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            props.put(ProducerConfig.RETRIES_CONFIG, 3);

            return new DefaultKafkaProducerFactory<>(props);
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
            return new KafkaTemplate<>(pf);
        }

        // ---- Consumer + @KafkaListener Container ----
        @Bean
        ConsumerFactory<String, String> consumerFactory() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaSpringComposeIT.bootstrap());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

            // 테스트 예측 가능성
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());

            return new DefaultKafkaConsumerFactory<>(props);
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
                ConsumerFactory<String, String> cf
        ) {
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(cf);
            return factory;
        }
    }
}
