package parking.forecast.service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import parking.forecast.adapter.ntpc.NtpcOpenDataClient;
import parking.forecast.adapter.ntpc.NtpcOpenDataClientException;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideFeeRateDto;
import parking.forecast.config.NtpcCatalogImportProperties;

/**
 * Resolves route-level NTPC tariffs only when the district and full road name
 * produce one unambiguous, non-range source row. Cached data is refreshed daily.
 */
@Service
public class NtpcRoadsideFeeRateLookupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NtpcRoadsideFeeRateLookupService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration FAILURE_RETRY_DELAY = Duration.ofHours(1);
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern RANGE_SEGMENT = Pattern.compile(
            ".*(?:路|街|大道)[^，,；;]{0,30}(?:至|[~～—–－-])[^，,；;]{0,30}(?:路|街|大道).*"
    );

    private final NtpcOpenDataClient ntpcOpenDataClient;
    private final NtpcCatalogImportProperties catalogProperties;
    private final Clock clock;
    private volatile RateSnapshot snapshot = RateSnapshot.empty();

    @Autowired
    public NtpcRoadsideFeeRateLookupService(
            NtpcOpenDataClient ntpcOpenDataClient,
            NtpcCatalogImportProperties catalogProperties
    ) {
        this(ntpcOpenDataClient, catalogProperties, Clock.systemUTC());
    }

    NtpcRoadsideFeeRateLookupService(
            NtpcOpenDataClient ntpcOpenDataClient,
            NtpcCatalogImportProperties catalogProperties,
            Clock clock
    ) {
        this.ntpcOpenDataClient = ntpcOpenDataClient;
        this.catalogProperties = catalogProperties;
        this.clock = clock;
    }

    public Optional<RoadsideFeeRate> findUniqueRate(String serviceArea, String fullRoadName) {
        String areaCode = resolveRoadsideAreaCode(serviceArea);
        String normalizedRoadName = normalizeKey(fullRoadName);
        if (areaCode == null || normalizedRoadName == null || isRangeOrIntersection(fullRoadName)) {
            return Optional.empty();
        }

        return findRate(refreshIfStale(), areaCode, normalizedRoadName);
    }

    /** Read-only lookup for map GETs; it never calls the official source. */
    public Optional<RoadsideFeeRate> findCachedUniqueRate(String serviceArea, String fullRoadName) {
        String areaCode = resolveRoadsideAreaCode(serviceArea);
        String normalizedRoadName = normalizeKey(fullRoadName);
        if (areaCode == null || normalizedRoadName == null || isRangeOrIntersection(fullRoadName)) {
            return Optional.empty();
        }
        return findRate(snapshot, areaCode, normalizedRoadName);
    }

    private Optional<RoadsideFeeRate> findRate(RateSnapshot catalog, String areaCode, String normalizedRoadName) {
        List<NtpcRoadsideFeeRateDto> matches = catalog.rows().getOrDefault(
                new RateKey(normalizeKey(areaCode), normalizedRoadName), List.of());
        if (matches.size() != 1) {
            return Optional.empty();
        }

        NtpcRoadsideFeeRateDto row = matches.get(0);
        if (!hasText(row.rates())) {
            return Optional.empty();
        }
        return Optional.of(new RoadsideFeeRate(
                row.rates().strip(),
                clean(row.weekdaysTime()),
                clean(row.saturdayChargingTime()),
                clean(row.sundayChargingTime()),
                clean(row.nationalHolidaysChargingTime())
        ));
    }

    private synchronized RateSnapshot refreshIfStale() {
        Instant now = clock.instant();
        RateSnapshot current = snapshot;
        if (now.isBefore(current.nextRefreshAt())) {
            return current;
        }

        try {
            List<NtpcRoadsideFeeRateDto> rows = fetchAllRows();
            RateSnapshot refreshed = new RateSnapshot(index(rows), now.plus(CACHE_TTL), now);
            snapshot = refreshed;
            return refreshed;
        } catch (NtpcOpenDataClientException exception) {
            LOGGER.warn("NTPC roadside fee-rate refresh failed; retaining cached fee rates when available.");
            RateSnapshot retryLater = new RateSnapshot(current.rows(), now.plus(FAILURE_RETRY_DELAY), current.loadedAt());
            snapshot = retryLater;
            return retryLater;
        }
    }

    private List<NtpcRoadsideFeeRateDto> fetchAllRows() {
        List<NtpcRoadsideFeeRateDto> rows = new ArrayList<>();
        int pageSize = catalogProperties.getPageSize();
        int maxPages = catalogProperties.getMaxPages();
        for (int page = 0; page < maxPages; page++) {
            List<NtpcRoadsideFeeRateDto> pageRows = ntpcOpenDataClient
                    .fetchRoadsideFeeRatesPage(page, pageSize)
                    .rows();
            rows.addAll(pageRows);
            if (pageRows.size() < pageSize) {
                if (rows.isEmpty()) {
                    throw new NtpcOpenDataClientException(
                            "NTPC roadside fee-rate dataset returned no rows; retaining the previous cached snapshot.");
                }
                return List.copyOf(rows);
            }
        }
        throw new NtpcOpenDataClientException(
                "NTPC roadside fee-rate dataset reached the configured page limit before traversal completed.");
    }

    private Map<RateKey, List<NtpcRoadsideFeeRateDto>> index(List<NtpcRoadsideFeeRateDto> rows) {
        Map<RateKey, List<NtpcRoadsideFeeRateDto>> grouped = new HashMap<>();
        for (NtpcRoadsideFeeRateDto row : rows) {
            String areaCode = normalizeKey(row.areaCode());
            String roadName = normalizeKey(row.roadName());
            if (areaCode == null || roadName == null || isRangeOrIntersection(row.roadName())) {
                continue;
            }
            grouped.computeIfAbsent(new RateKey(areaCode, roadName), ignored -> new ArrayList<>()).add(row);
        }
        Map<RateKey, List<NtpcRoadsideFeeRateDto>> immutable = new HashMap<>();
        grouped.forEach((key, values) -> immutable.put(key, List.copyOf(values)));
        return Map.copyOf(immutable);
    }

    private String resolveRoadsideAreaCode(String serviceArea) {
        if (!hasText(serviceArea)) {
            return null;
        }
        return catalogProperties.getScopes().stream()
                .filter(scope -> serviceArea.strip().equals(scope.getServiceArea().strip()))
                .map(NtpcCatalogImportProperties.Scope::getRoadsideAreaCode)
                .filter(NtpcRoadsideFeeRateLookupService::hasText)
                .map(String::strip)
                .findFirst()
                .orElse(null);
    }

    private static boolean isRangeOrIntersection(String roadName) {
        if (!hasText(roadName)) {
            return true;
        }
        String name = roadName.strip();
        return name.matches(".*[()（）\\[\\]【】].*")
                || (name.contains("路口") && (name.contains("與") || name.contains("及")))
                || RANGE_SEGMENT.matcher(name).matches();
    }

    private static String normalizeKey(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        return normalized.isEmpty() ? null : normalized;
    }

    private static String clean(String value) {
        return hasText(value) ? value.strip() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record RoadsideFeeRate(
            String rates,
            String weekdaysTime,
            String saturdayChargingTime,
            String sundayChargingTime,
            String nationalHolidaysChargingTime
    ) {
    }

    private record RateKey(String areaCode, String roadName) {
    }

    private record RateSnapshot(
            Map<RateKey, List<NtpcRoadsideFeeRateDto>> rows,
            Instant nextRefreshAt,
            Instant loadedAt
    ) {
        private static RateSnapshot empty() {
            return new RateSnapshot(Map.of(), Instant.MIN, null);
        }
    }
}
