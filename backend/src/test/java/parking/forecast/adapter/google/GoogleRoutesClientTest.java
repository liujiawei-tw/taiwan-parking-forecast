package parking.forecast.adapter.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import parking.forecast.config.GoogleRoutesProperties;

class GoogleRoutesClientTest {

    @Test
    void computesTrafficAwareDrivingTravelTime() {
        var testClient = testClient(enabledProperties());
        testClient.server().expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(method(POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andExpect(header("X-Goog-FieldMask", "routes.duration,routes.staticDuration,routes.distanceMeters"))
                .andExpect(content().json("""
                        {
                          "origin": {
                            "location": {
                              "latLng": {
                                "latitude": 25.04,
                                "longitude": 121.45
                              }
                            }
                          },
                          "destination": {
                            "location": {
                              "latLng": {
                                "latitude": 25.0372,
                                "longitude": 121.4525
                              }
                            }
                          },
                          "travelMode": "DRIVE",
                          "routingPreference": "TRAFFIC_AWARE_OPTIMAL",
                          "departureTime": "2026-09-29T02:00:00Z"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "routes": [
                            {
                              "duration": "481s",
                              "staticDuration": "420s",
                              "distanceMeters": 1300
                            }
                          ]
                        }
                        """, APPLICATION_JSON));

        var travelTime = testClient.client().computeDrivingTravelTime(
                25.0400,
                121.4500,
                25.0372,
                121.4525,
                OffsetDateTime.parse("2026-09-29T10:00:00+08:00")
        );

        assertThat(travelTime.duration()).isEqualTo(Duration.ofSeconds(481));
        assertThat(travelTime.staticDuration()).isEqualTo(Duration.ofSeconds(420));
        assertThat(travelTime.distanceMeters()).isEqualTo(1300);
        testClient.server().verify();
    }

    @Test
    void omitsDepartureTimeWhenRouteDepartureTimeIsNull() {
        var testClient = testClient(enabledProperties());
        testClient.server().expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "origin": {
                            "location": {
                              "latLng": {
                                "latitude": 25.04,
                                "longitude": 121.45
                              }
                            }
                          },
                          "destination": {
                            "location": {
                              "latLng": {
                                "latitude": 25.0372,
                                "longitude": 121.4525
                              }
                            }
                          },
                          "travelMode": "DRIVE",
                          "routingPreference": "TRAFFIC_AWARE_OPTIMAL"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "routes": [
                            {
                              "duration": "481s",
                              "staticDuration": "420s",
                              "distanceMeters": 1300
                            }
                          ]
                        }
                        """, APPLICATION_JSON));

        var travelTime = testClient.client().computeDrivingTravelTime(
                25.0400,
                121.4500,
                25.0372,
                121.4525,
                null
        );

        assertThat(travelTime.duration()).isEqualTo(Duration.ofSeconds(481));
        testClient.server().verify();
    }

    @Test
    void doesNotCallGoogleWhenDisabled() {
        var properties = enabledProperties();
        properties.setExternalCallsEnabled(false);
        var testClient = testClient(properties);

        assertThatExceptionOfType(GoogleRoutesClientException.class)
                .isThrownBy(() -> testClient.client().computeDrivingTravelTime(
                        25.0400,
                        121.4500,
                        25.0372,
                        121.4525,
                        OffsetDateTime.parse("2026-09-29T10:00:00+08:00")))
                .withMessageContaining("disabled");
        testClient.server().verify();
    }

    @Test
    void requiresApiKeyBeforeCallingGoogle() {
        var properties = enabledProperties();
        properties.setApiKey("");
        var testClient = testClient(properties);

        assertThatExceptionOfType(GoogleRoutesClientException.class)
                .isThrownBy(() -> testClient.client().computeDrivingTravelTime(
                        25.0400,
                        121.4500,
                        25.0372,
                        121.4525,
                        OffsetDateTime.parse("2026-09-29T10:00:00+08:00")))
                .withMessageContaining("API key");
        testClient.server().verify();
    }

    @Test
    void reportsHttpFailure() {
        var testClient = testClient(enabledProperties());
        testClient.server().expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(method(POST))
                .andRespond(withServerError());

        assertThatExceptionOfType(GoogleRoutesClientException.class)
                .isThrownBy(() -> testClient.client().computeDrivingTravelTime(
                        25.0400,
                        121.4500,
                        25.0372,
                        121.4525,
                        OffsetDateTime.parse("2026-09-29T10:00:00+08:00")))
                .withMessageContaining("HTTP 500");
        testClient.server().verify();
    }

    @Test
    void rejectsResponseWithoutRoutes() {
        var testClient = testClient(enabledProperties());
        testClient.server().expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"routes\": []}", APPLICATION_JSON));

        assertThatExceptionOfType(GoogleRoutesClientException.class)
                .isThrownBy(() -> testClient.client().computeDrivingTravelTime(
                        25.0400,
                        121.4500,
                        25.0372,
                        121.4525,
                        OffsetDateTime.parse("2026-09-29T10:00:00+08:00")))
                .withMessageContaining("no routes");
        testClient.server().verify();
    }

    private TestClient testClient(GoogleRoutesProperties properties) {
        var builder = RestClient.builder().baseUrl(properties.getBaseUrl().toString());
        var server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(new GoogleRoutesClient(builder.build(), properties), server);
    }

    private GoogleRoutesProperties enabledProperties() {
        var properties = new GoogleRoutesProperties();
        properties.setBaseUrl(URI.create("https://routes.googleapis.com"));
        properties.setExternalCallsEnabled(true);
        properties.setApiKey("test-key");
        return properties;
    }

    private record TestClient(GoogleRoutesClient client, MockRestServiceServer server) {
    }
}
