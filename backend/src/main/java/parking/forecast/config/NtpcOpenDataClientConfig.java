package parking.forecast.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({
        NtpcOpenDataProperties.class,
        NtpcCatalogImportProperties.class,
        NtpcCatalogImportCommandProperties.class
})
public class NtpcOpenDataClientConfig {

    @Bean
    RestClient ntpcOpenDataRestClient(NtpcOpenDataProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
