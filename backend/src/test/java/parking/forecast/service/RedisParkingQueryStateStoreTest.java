package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import parking.forecast.config.ParkingQueryStateProperties;
import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.CreateParkingQueryRequest.DepartureInput;
import parking.forecast.controller.dto.CreateParkingQueryRequest.LocationInput;
import parking.forecast.controller.dto.ParkingQueryResponse;
import parking.forecast.controller.dto.ParkingQueryResponse.AreaSection;
import parking.forecast.controller.dto.ParkingQueryResponse.DestinationSection;
import parking.forecast.controller.dto.ParkingQueryResponse.EtaSection;
import parking.forecast.controller.dto.ParkingQueryResponse.OffStreetSection;
import parking.forecast.controller.dto.ParkingQueryResponse.PredictionsSection;
import parking.forecast.controller.dto.ParkingQueryResponse.QuerySection;
import parking.forecast.controller.dto.ParkingQueryResponse.RefreshSection;
import parking.forecast.controller.dto.ParkingQueryResponse.RoadsidePrediction;
import parking.forecast.controller.dto.ParkingQueryResponse.RoadsideSection;
import parking.forecast.controller.dto.ParkingQueryResponse.SourceRefresh;
import parking.forecast.service.ParkingQueryStateStore.StoredParkingQuery;
import parking.forecast.service.RedisParkingQueryStateStore.ParkingQueryStateStoreException;

class RedisParkingQueryStateStoreTest {

    private final StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
    private final AtomicReference<String> savedQueryPayload = new AtomicReference<>();
    private RedisParkingQueryStateStore store;
    private ParkingQueryStateProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ParkingQueryStateProperties();
        properties.setTtl(Duration.ofMinutes(30));
        properties.setExpiredStateRetention(Duration.ofMinutes(30));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        store = new RedisParkingQueryStateStore(redisTemplate, objectMapper, properties);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            if (key.startsWith("parking:query:")) {
                savedQueryPayload.set(value);
            }
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void savesQueryStateAndRequestTokenWithSameTtl() {
        StoredParkingQuery query = storedQuery("query-001", "token-001");

        store.save("token-001", query);

        verify(valueOperations).set(eq("parking:query:query-001"), anyString(), eq(Duration.ofMinutes(60)));
        verify(valueOperations).set("parking:query-token:token-001", "query-001", Duration.ofMinutes(30));
        assertThat(savedQueryPayload.get()).contains("\"queryId\":\"query-001\"");
    }

    @Test
    void readsStoredQueryFromRedisJson() {
        StoredParkingQuery query = storedQuery("query-002", "token-002");
        store.save("token-002", query);
        when(valueOperations.get("parking:query:query-002")).thenReturn(savedQueryPayload.get());

        var loaded = store.findByQueryId("query-002");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().response().query().queryId()).isEqualTo("query-002");
        assertThat(loaded.get().request().requestToken()).isEqualTo("token-002");
    }

    @Test
    void readsRequestTokenIndex() {
        when(valueOperations.get("parking:query-token:token-003")).thenReturn("query-003");

        assertThat(store.findQueryIdByRequestToken("token-003")).contains("query-003");
    }

    @Test
    void wrapsRedisFailureWithQueryStateException() {
        when(redisTemplate.opsForValue()).thenThrow(new DataAccessResourceFailureException("redis down"));

        assertThatThrownBy(() -> store.findByQueryId("query-004"))
                .isInstanceOf(ParkingQueryStateStoreException.class)
                .hasMessageContaining("Redis query state lookup failed");
    }

    private StoredParkingQuery storedQuery(String queryId, String requestToken) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-29T08:00:00Z");
        ParkingQueryResponse response = new ParkingQueryResponse(
                new QuerySection(queryId, "READY", false, now, now, now.plusMinutes(30), "ready"),
                new DestinationSection("IKEA 新莊", 25.0414214, 121.4647633, "SUPPORTED", "supported"),
                new AreaSection(500, "STRAIGHT_LINE", "NEW_TAIPEI_XINZHUANG_MVP", "PARTIAL", "partial"),
                new EtaSection("FAILED", "GOOGLE_ROUTES", now, null, null, "failed"),
                new RoadsideSection("NO_REALTIME_SNAPSHOT", "LOCAL_NTPC_CATALOG", null, null, null, List.of(), "none"),
                new OffStreetSection("NO_REALTIME_SNAPSHOT", "LOCAL_NTPC_CATALOG", null, null, List.of(), "none"),
                new PredictionsSection(
                        "INSUFFICIENT_DATA",
                        null,
                        null,
                        now,
                        List.of("MODEL_NOT_LOADED", "ETA_NOT_READY"),
                        new RoadsidePrediction(
                                "INSUFFICIENT_DATA",
                                null,
                                null,
                                null,
                                null,
                                List.of("MODEL_NOT_LOADED", "ETA_NOT_READY"),
                                "none"
                        ),
                        List.of(),
                        "none"
                ),
                new RefreshSection(
                        new SourceRefresh("ROADSIDE", "NO_PUBLISHED_SNAPSHOT", false, null, null, "none"),
                        new SourceRefresh("OFF_STREET", "NO_PUBLISHED_SNAPSHOT", false, null, null, "none")
                )
        );
        CreateParkingQueryRequest request = new CreateParkingQueryRequest(
                requestToken,
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("IKEA 新莊", 25.0414214, 121.4647633),
                new DepartureInput("NOW", null)
        );
        return new StoredParkingQuery(response, request);
    }
}
