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
        RefreshSection refresh,
        GooglePlacesSection googlePlaces) {

    public ParkingQueryResponse(
            QuerySection query,
            DestinationSection destination,
            AreaSection area,
            EtaSection eta,
            RoadsideSection roadside,
            OffStreetSection offStreet,
            PredictionsSection predictions,
            RefreshSection refresh) {
        this(query, destination, area, eta, roadside, offStreet, predictions, refresh,
                new GooglePlacesSection("NOT_REQUESTED", "GOOGLE_PLACES", 500, null, List.of(),
                        "Google Places supplementary parking search was not requested."));
    }

    public ParkingQueryResponse withGooglePlaces(GooglePlacesSection value) {
        return new ParkingQueryResponse(query, destination, area, eta, roadside, offStreet, predictions, refresh, value);
    }

    public record GooglePlacesSection(
            String status,
            String provider,
            int radiusMeters,
            OffsetDateTime generatedAt,
            List<GoogleParkingPlace> items,
            String message) {
    }

    public record GoogleParkingPlace(
            String placeId,
            String name,
            double latitude,
            double longitude,
            Double navigationLatitude,
            Double navigationLongitude,
            boolean navigationAvailable,
            int distanceMeters,
            String address,
            String googleMapsUri,
            String availabilityStatus,
            String source,
            OffsetDateTime sourceObservedAt,
            OffsetDateTime systemCollectedAt,
            String message) {
        public GoogleParkingPlace {
            availabilityStatus = "UNKNOWN";
            source = "GOOGLE_PLACES";
            sourceObservedAt = null;
        }
    }

    public record QuerySection(
            String queryId,
            String status,
            boolean hasPendingWork,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime expiresAt,
            String message) {
    }

    public record MapDataResponse(
            String queryId,
            int radiusMeters,
            String coverageStatus,
            OffsetDateTime generatedAt,
            int candidateCount,
            int verifiedCandidateCount,
            int excludedUnverifiedCount,
            int omittedItemCount,
            int returnedItemCount,
            String message,
            MapSource roadside,
            MapSource offStreet,
            List<MapMarker> items) {
    }

    public record MapViewportResponse(
            String queryId,
            String serviceArea,
            MapBounds viewport,
            MapBounds supportedArea,
            String coverageStatus,
            OffsetDateTime generatedAt,
            int candidateCount,
            int verifiedCandidateCount,
            int excludedUnverifiedCount,
            int offset,
            int pageSize,
            int returnedItemCount,
            Integer nextOffset,
            boolean hasMore,
            String message,
            MapSource roadside,
            MapSource offStreet,
            List<MapMarker> items) {
    }

    public record MapBounds(double south, double west, double north, double east) {
    }

    public record MapSource(
            String status,
            String source,
            Long snapshotId,
            OffsetDateTime snapshotSourceObservedAt,
            OffsetDateTime systemCollectedAt,
            String message) {
    }

    public record MapMarker(
            String id,
            String type,
            String label,
            double latitude,
            double longitude,
            Double navigationLatitude,
            Double navigationLongitude,
            int distanceMeters,
            String availabilityStatus,
            Integer availableCarSpaces,
            Integer totalCarSpaces,
            Boolean availableSpot,
            String carEligibleStatus,
            String qualityStatus,
            String qualityReason,
            boolean navigationAvailable,
            String source,
            OffsetDateTime sourceObservedAt,
            OffsetDateTime systemCollectedAt,
            String address,
            String parkingSpaceType,
            String feeStatus,
            String feeDescription,
            String chargingDays,
            String chargingHours,
            String notes,
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
            Long snapshotId,
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
            OffsetDateTime systemCollectedAt,
            Integer availableCarSpaces,
            Integer totalCarSpaces,
            String parkingSpaceType,
            String feeStatus,
            String feeDescription,
            String chargingDays,
            String chargingHours,
            String notes) {
    }

    public record OffStreetSection(
            String status,
            String source,
            Long snapshotId,
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
            Integer totalCarSpaces,
            boolean navigationAvailable,
            String availabilityStatus,
            OffsetDateTime systemCollectedAt,
            String address,
            String parkingSpaceType,
            String feeDescription,
            String chargingHours,
            String notes,
            String message) {
    }

    public record PredictionsSection(
            String status,
            OffsetDateTime targetArrivalAt,
            String modelVersion,
            OffsetDateTime generatedAt,
            List<String> reasons,
            RoadsidePrediction roadside,
            List<OffStreetPrediction> offStreet,
            String message) {
    }

    public record RoadsidePrediction(
            String status,
            Double probability,
            String modelVersion,
            String inputSnapshotVersion,
            Integer dataAgeSeconds,
            List<String> reasons,
            String message) {
    }

    public record OffStreetPrediction(
            String parkingLotId,
            String status,
            Double probability,
            String modelVersion,
            String inputSnapshotVersion,
            Integer dataAgeSeconds,
            List<String> reasons,
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
