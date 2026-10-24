package parking.forecast.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.ParkingQueryResponse;
import parking.forecast.controller.dto.ParkingQueryResponse.MapDataResponse;
import parking.forecast.domain.TriggerType;
import parking.forecast.service.ParkingQueryService;
import parking.forecast.service.ParkingQueryService.ParkingQueryContractException;

@RestController
@RequestMapping("/api/v1/parking-queries")
@CrossOrigin(origins = { "http://localhost:5173", "http://127.0.0.1:5173" })
public class ParkingQueryController {

    private final ParkingQueryService parkingQueryService;

    public ParkingQueryController(ParkingQueryService parkingQueryService) {
        this.parkingQueryService = parkingQueryService;
    }

    @PostMapping
    public ResponseEntity<ParkingQueryResponse> create(@Valid @RequestBody CreateParkingQueryRequest request) {
        return ResponseEntity.accepted().body(parkingQueryService.create(request));
    }

    @GetMapping("/{queryId}")
    public ParkingQueryResponse get(@PathVariable String queryId) {
        return parkingQueryService.findById(queryId);
    }

    @GetMapping("/{queryId}/map-data")
    public MapDataResponse getMapData(@PathVariable String queryId) {
        return parkingQueryService.findMapData(queryId);
    }

    @PostMapping("/{queryId}/refresh")
    public ResponseEntity<ParkingQueryResponse> refresh(
            @PathVariable String queryId,
            @RequestParam(defaultValue = "MANUAL_REFRESH") String trigger
    ) {
        return ResponseEntity.accepted().body(parkingQueryService.refresh(queryId, parseRefreshTrigger(trigger)));
    }

    private TriggerType parseRefreshTrigger(String trigger) {
        try {
            TriggerType triggerType = TriggerType.valueOf(trigger.toUpperCase());
            if (triggerType == TriggerType.MANUAL_REFRESH || triggerType == TriggerType.VISIBLE_AUTO_REFRESH) {
                return triggerType;
            }
        } catch (IllegalArgumentException exception) {
            // Fall through to the stable contract error below.
        }
        throw new ParkingQueryContractException(
                "INVALID_REFRESH_TRIGGER",
                "refresh trigger must be MANUAL_REFRESH or VISIBLE_AUTO_REFRESH.",
                false
        );
    }
}
