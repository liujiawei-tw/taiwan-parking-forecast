package parking.forecast.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "parking.refresh")
@Validated
public class RefreshControlProperties {

    @NotNull
    private Duration sourceCooldown = Duration.ofMinutes(5);

    @NotNull
    private Duration lockTtl = Duration.ofSeconds(90);

    @Min(1)
    private int pageSize = 500;

    @Min(1)
    private int maxPages = 20;

    public Duration getSourceCooldown() {
        return sourceCooldown;
    }

    public void setSourceCooldown(Duration sourceCooldown) {
        this.sourceCooldown = sourceCooldown;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }
}
