package parking.forecast.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "parking.query-state")
public class ParkingQueryStateProperties {

    private Duration ttl = Duration.ofMinutes(30);
    private Duration expiredStateRetention = Duration.ofMinutes(30);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getExpiredStateRetention() {
        return expiredStateRetention;
    }

    public void setExpiredStateRetention(Duration expiredStateRetention) {
        this.expiredStateRetention = expiredStateRetention;
    }
}
