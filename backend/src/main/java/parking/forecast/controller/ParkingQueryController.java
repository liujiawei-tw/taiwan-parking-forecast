package parking.forecast.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.ParkingQueryResponse;
import parking.forecast.service.ParkingQueryMockService;
import parking.forecast.service.ParkingQueryMockService.ParkingQueryNotFoundException;

@RestController
@RequestMapping("/api/v1/parking-queries")
@CrossOrigin(origins = { "http://localhost:5173", "http://127.0.0.1:5173" })
public class ParkingQueryController {

    private final ParkingQueryMockService parkingQueryMockService;

    public ParkingQueryController(ParkingQueryMockService parkingQueryMockService) {
        this.parkingQueryMockService = parkingQueryMockService;
    }

    @PostMapping
    public ResponseEntity<ParkingQueryResponse> create(@Valid @RequestBody CreateParkingQueryRequest request) {
        return ResponseEntity.accepted().body(parkingQueryMockService.create(request));
    }

    @GetMapping("/{queryId}")
    public ParkingQueryResponse get(@PathVariable String queryId) {
        return find(queryId);
    }

    @PostMapping("/{queryId}/refresh")
    public ResponseEntity<ParkingQueryResponse> refresh(@PathVariable String queryId) {
        return ResponseEntity.accepted().body(findForRefresh(queryId));
    }

    private ParkingQueryResponse find(String queryId) {
        try {
            return parkingQueryMockService.findById(queryId);
        } catch (ParkingQueryNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Query not found");
        }
    }

    private ParkingQueryResponse findForRefresh(String queryId) {
        try {
            return parkingQueryMockService.refresh(queryId);
        } catch (ParkingQueryNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Query not found");
        }
    }
}
