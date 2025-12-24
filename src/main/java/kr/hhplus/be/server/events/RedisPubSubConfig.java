package kr.hhplus.be.server.events;

import kr.hhplus.be.server.events.consumer.OrderCreatedEventSubscriber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisPubSubConfig {

    @Value("${app.redis.channels.order-created:local.order.order-created.v1}")
    private String orderCreatedChannel;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            OrderCreatedEventSubscriber subscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 채널 구독
        container.addMessageListener(subscriber, new ChannelTopic(orderCreatedChannel));
        return container;
    }
}
