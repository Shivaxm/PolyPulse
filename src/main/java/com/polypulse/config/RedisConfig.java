package com.polypulse.config;

import com.polypulse.service.RedisEventSubscriber;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@ConditionalOnProperty(name = "polypulse.redis.enabled", havingValue = "true")
public class RedisConfig {

    public static final String PRICE_CHANNEL = "polypulse:price_updates";
    public static final String CORRELATION_CHANNEL = "polypulse:correlations";

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public RedisMessageListenerContainer redisListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisEventSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        MessageListenerAdapter priceAdapter = new MessageListenerAdapter(subscriber, "onPriceMessage");
        priceAdapter.setSerializer(new StringRedisSerializer());
        container.addMessageListener(priceAdapter, new ChannelTopic(PRICE_CHANNEL));

        MessageListenerAdapter correlationAdapter = new MessageListenerAdapter(subscriber, "onCorrelationMessage");
        correlationAdapter.setSerializer(new StringRedisSerializer());
        container.addMessageListener(correlationAdapter, new ChannelTopic(CORRELATION_CHANNEL));

        return container;
    }
}
