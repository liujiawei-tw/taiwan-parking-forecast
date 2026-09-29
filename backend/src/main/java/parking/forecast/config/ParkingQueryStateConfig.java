package parking.forecast.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import parking.forecast.service.InMemoryParkingQueryStateStore;
import parking.forecast.service.ParkingQueryStateStore;
import parking.forecast.service.RedisParkingQueryStateStore;

@Configuration
@EnableConfigurationProperties(ParkingQueryStateProperties.class)
public class ParkingQueryStateConfig {

    @Bean
    @ConditionalOnProperty(name = "parking.query-state.store", havingValue = "memory")
    ParkingQueryStateStore inMemoryParkingQueryStateStore(ParkingQueryStateProperties properties) {
        return new InMemoryParkingQueryStateStore(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "parking.query-state.store", havingValue = "redis", matchIfMissing = true)
    ParkingQueryStateStore redisParkingQueryStateStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ParkingQueryStateProperties properties
    ) {
        return new RedisParkingQueryStateStore(redisTemplate, objectMapper, properties);
    }
}
