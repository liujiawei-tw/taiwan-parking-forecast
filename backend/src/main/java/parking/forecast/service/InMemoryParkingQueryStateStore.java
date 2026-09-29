package parking.forecast.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import parking.forecast.config.ParkingQueryStateProperties;

public class InMemoryParkingQueryStateStore implements ParkingQueryStateStore {

    private final Clock clock;
    private final ParkingQueryStateProperties properties;
    private final Map<String, StoredState> queries = new ConcurrentHashMap<>();
    private final Map<String, TokenState> requestTokens = new ConcurrentHashMap<>();

    public InMemoryParkingQueryStateStore(ParkingQueryStateProperties properties) {
        this(properties, Clock.systemUTC());
    }

    InMemoryParkingQueryStateStore(ParkingQueryStateProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<String> findQueryIdByRequestToken(String requestToken) {
        TokenState state = requestTokens.get(requestToken);
        if (state == null) {
            return Optional.empty();
        }
        if (isExpired(state.expiresAt())) {
            requestTokens.remove(requestToken);
            return Optional.empty();
        }
        return Optional.of(state.queryId());
    }

    @Override
    public Optional<StoredParkingQuery> findByQueryId(String queryId) {
        StoredState state = queries.get(queryId);
        if (state == null) {
            return Optional.empty();
        }
        if (isExpired(state.expiresAt())) {
            queries.remove(queryId);
            return Optional.empty();
        }
        return Optional.of(state.query());
    }

    @Override
    public void save(String requestToken, StoredParkingQuery query) {
        Instant tokenExpiresAt = clock.instant().plus(properties.getTtl());
        Instant queryRemovedAt = tokenExpiresAt.plus(properties.getExpiredStateRetention());
        String queryId = query.response().query().queryId();
        queries.put(queryId, new StoredState(query, queryRemovedAt));
        requestTokens.put(requestToken, new TokenState(queryId, tokenExpiresAt));
    }

    private boolean isExpired(Instant expiresAt) {
        return !expiresAt.isAfter(clock.instant());
    }

    private record StoredState(
            StoredParkingQuery query,
            Instant expiresAt
    ) {
    }

    private record TokenState(
            String queryId,
            Instant expiresAt
    ) {
    }
}
