package parking.forecast.adapter.ntpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import parking.forecast.config.NtpcOpenDataProperties;

class NtpcOpenDataClientTest {

    @Test
    void fetchesRoadsideAvailabilityWithConfiguredDataset() {
        var testClient = testClient(defaultProperties());
        String payload = """
                [{"id":"1","cellid":"RA-001","roadname":"新莊路","cellstatus":"0"}]
                """;
        testClient.server().expect(requestTo("https://data.ntpc.gov.tw/api/datasets/54A507C4-C038-41B5-BF60-BBECB9D052C6/json?page=0&size=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(payload, APPLICATION_JSON));

        var page = testClient.client().fetchRoadsideAvailabilityPage(0, 1);
        var rows = page.rows();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().cellId()).isEqualTo("RA-001");
        assertThat(rows.getFirst().roadName()).isEqualTo("新莊路");
        assertThat(page.attempts()).isEqualTo(1);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.responseBytes()).isEqualTo(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(page.elapsedMillis()).isGreaterThanOrEqualTo(0);
        testClient.server().verify();
    }

    @Test
    void fetchesOffStreetAvailabilityWithAvailableCarAlias() {
        var testClient = testClient(defaultProperties());
        testClient.server().expect(requestTo("https://data.ntpc.gov.tw/api/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78/json?page=0&size=2"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"ID":"P001","AVAILABLECAR":"12"},{"ID":"P002","AVAILABLE":"-9"}]
                        """, APPLICATION_JSON));

        var rows = testClient.client().fetchOffStreetAvailability(0, 2);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).id()).isEqualTo("P001");
        assertThat(rows.get(0).availableCar()).isEqualTo("12");
        assertThat(rows.get(1).availableCar()).isEqualTo("-9");
        testClient.server().verify();
    }

    @Test
    void fetchesOffStreetCatalogWithConfiguredDataset() {
        var testClient = testClient(defaultProperties());
        testClient.server().expect(requestTo("https://data.ntpc.gov.tw/api/datasets/B1464EF0-9C7C-4A6F-ABF7-6BDF32847E68/json?page=0&size=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"ID":"050029","AREA":"新莊區","NAME":"新莊區農會停車場","TW97X":"296239.5","TW97Y":"2770049","TOTALCAR":"345"}]
                        """, APPLICATION_JSON));

        var rows = testClient.client().fetchOffStreetCatalog(0, 1);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().id()).isEqualTo("050029");
        assertThat(rows.getFirst().area()).isEqualTo("新莊區");
        assertThat(rows.getFirst().totalCar()).isEqualTo("345");
        testClient.server().verify();
    }

    @Test
    void retriesConfiguredNumberOfAttemptsBeforeFailing() {
        var properties = defaultProperties();
        properties.setMaxAttempts(2);
        var testClient = testClient(properties);
        testClient.server().expect(times(2), requestTo("https://data.ntpc.gov.tw/api/datasets/e09b35a5-a738-48cc-b0f5-570b67ad9c78/json?page=0&size=1"))
                .andExpect(method(GET))
                .andRespond(withServerError());

        assertThatExceptionOfType(NtpcOpenDataClientException.class)
                .isThrownBy(() -> testClient.client().fetchOffStreetAvailability(0, 1))
                .withMessageContaining("HTTP 500");
        testClient.server().verify();
    }

    @Test
    void doesNotCallExternalSourceWhenDisabled() {
        var properties = defaultProperties();
        properties.setExternalCallsEnabled(false);
        var testClient = testClient(properties);

        assertThatExceptionOfType(NtpcOpenDataClientException.class)
                .isThrownBy(() -> testClient.client().fetchRoadsideAvailability(0, 1))
                .withMessageContaining("external calls are disabled");
        testClient.server().verify();
    }

    @Test
    void rejectsInvalidPageRequest() {
        var testClient = testClient(defaultProperties());

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> testClient.client().fetchRoadsideAvailability(-1, 1))
                .withMessageContaining("page");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> testClient.client().fetchRoadsideAvailability(0, 0))
                .withMessageContaining("size");
        testClient.server().verify();
    }

    private TestClient testClient(NtpcOpenDataProperties properties) {
        var builder = RestClient.builder().baseUrl(properties.getBaseUrl().toString());
        var server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(new NtpcOpenDataClient(builder.build(), properties), server);
    }

    private NtpcOpenDataProperties defaultProperties() {
        var properties = new NtpcOpenDataProperties();
        properties.setBaseUrl(URI.create("https://data.ntpc.gov.tw"));
        return properties;
    }

    private record TestClient(NtpcOpenDataClient client, MockRestServiceServer server) {
    }
}
