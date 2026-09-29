package parking.forecast.service;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import parking.forecast.config.ParkingQueryStateProperties;

public class RedisParkingQueryStateStore implements ParkingQueryStateStore {

    private static final String QUERY_KEY_PREFIX = "parking:query:";
    private static final String TOKEN_KEY_PREFIX = "parking:query-token:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ParkingQueryStateProperties properties;

    public RedisParkingQueryStateStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ParkingQueryStateProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<String> findQueryIdByRequestToken(String requestToken) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(tokenKey(requestToken)));
        } catch (DataAccessException exception) {
            throw new ParkingQueryStateStoreException("Redis query requestToken lookup failed.", exception);
        }
    }

    @Override
    public Optional<StoredParkingQuery> findByQueryId(String queryId) {
        try {
            String value = redisTemplate.opsForValue().get(queryKey(queryId));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, StoredParkingQuery.class));
        } catch (JsonProcessingException exception) {
            throw new ParkingQueryStateStoreException("Redis query state payload is invalid.", exception);
        } catch (DataAccessException exception) {
            throw new ParkingQueryStateStoreException("Redis query state lookup failed.", exception);
        }
    }

    @Override
    public void save(String requestToken, StoredParkingQuery query) {
        String queryId = query.response().query().queryId();
        try {
            String value = objectMapper.writeValueAsString(query);
            redisTemplate.opsForValue().set(queryKey(queryId), value, queryKeyTtl());
            redisTemplate.opsForValue().set(tokenKey(requestToken), queryId, properties.getTtl());
        } catch (JsonProcessingException exception) {
            throw new ParkingQueryStateStoreException("Redis query state payload cannot be serialized.", exception);
        } catch (DataAccessException exception) {
            throw new ParkingQueryStateStoreException("Redis query state save failed.", exception);
        }
    }

    private String queryKey(String queryId) {
        return QUERY_KEY_PREFIX + queryId;
    }

    private String tokenKey(String requestToken) {
        return TOKEN_KEY_PREFIX + requestToken;
    }

    private java.time.Duration queryKeyTtl() {
        return properties.getTtl().plus(properties.getExpiredStateRetention());
    }

    public static class ParkingQueryStateStoreException extends RuntimeException {

        public ParkingQueryStateStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
