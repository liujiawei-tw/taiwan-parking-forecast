package parking.forecast.controller.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ParkingQueryResponse(
        QuerySection query,
        DestinationSection destination,
        AreaSection area,
        EtaSection eta,
        RoadsideSection roadside,
        OffStreetSection offStreet,
        PredictionsSection predictions,
        RefreshSection refresh) {

    public record QuerySection(
            String queryId,
            String status,
            boolean hasPendingWork,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime expiresAt,
            String message) {
    }

    public record DestinationSection(
            String label,
            double latitude,
            double longitude,
            String supportStatus,
            String message) {
    }

    public record AreaSection(
            int radiusMeters,
            String distanceMode,
            String serviceArea,
            String coverageStatus,
            String message) {
    }

    public record EtaSection(
            String status,
            String provider,
            OffsetDateTime departureAt,
            OffsetDateTime estimatedArrivalAt,
            Integer durationSeconds,
            String message) {
    }

    public record RoadsideSection(
            String status,
            String source,
            OffsetDateTime systemCollectedAt,
            Integer currentlyAvailableSpaces,
            List<RoadsideSpot> availableSpots,
            String message) {
    }

    public record RoadsideSpot(
            String id,
            String label,
            double latitude,
            double longitude,
            int distanceMeters,
            boolean navigationAvailable,
            String availabilityStatus,
            OffsetDateTime systemCollectedAt) {
    }

    public record OffStreetSection(
            String status,
            String source,
            OffsetDateTime systemCollectedAt,
            List<ParkingLot> parkingLots,
            String message) {
    }

    public record ParkingLot(
            String id,
            String name,
            double latitude,
            double longitude,
            int distanceMeters,
            Integer availableCarSpaces,
            boolean navigationAvailable,
            String availabilityStatus,
            OffsetDateTime systemCollectedAt,
            String message) {
    }

    public record PredictionsSection(
            String status,
            OffsetDateTime targetArrivalAt,
            RoadsidePrediction roadside,
            List<OffStreetPrediction> offStreet,
            String message) {
    }

    public record RoadsidePrediction(
            String status,
            Double probability,
            String message) {
    }

    public record OffStreetPrediction(
            String parkingLotId,
            String status,
            Double probability,
            String message) {
    }

    public record RefreshSection(
            SourceRefresh roadside,
            SourceRefresh offStreet) {
    }

    public record SourceRefresh(
            String sourceType,
            String action,
            boolean refreshInProgress,
            OffsetDateTime lastSucceededAt,
            OffsetDateTime nextAllowedAt,
            String message) {
    }
}
