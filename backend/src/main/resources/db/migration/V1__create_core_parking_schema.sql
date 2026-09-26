CREATE TABLE parking_place (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    place_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    entrance_latitude DECIMAL(10, 7),
    entrance_longitude DECIMAL(10, 7),
    car_eligible_status VARCHAR(40) NOT NULL,
    active_status VARCHAR(40) NOT NULL,
    service_area VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_parking_place_area_type_status
    ON parking_place (service_area, place_type, active_status);

CREATE INDEX idx_parking_place_lat_lng
    ON parking_place (latitude, longitude);

CREATE TABLE source_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(60) NOT NULL,
    dataset_code VARCHAR(80) NOT NULL,
    source_place_id VARCHAR(120) NOT NULL,
    parking_place_id BIGINT NOT NULL,
    mapping_status VARCHAR(40) NOT NULL,
    valid_from TIMESTAMP(6),
    valid_to TIMESTAMP(6),
    raw_name_hint VARCHAR(200),
    raw_location_hint VARCHAR(300),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_source_mapping_parking_place
        FOREIGN KEY (parking_place_id) REFERENCES parking_place (id)
);

CREATE INDEX idx_source_mapping_source_id
    ON source_mapping (source_system, dataset_code, source_place_id);

CREATE INDEX idx_source_mapping_place
    ON source_mapping (parking_place_id);

CREATE TABLE ingestion_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(60) NOT NULL,
    dataset_code VARCHAR(80) NOT NULL,
    trigger_type VARCHAR(60) NOT NULL,
    run_status VARCHAR(40) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6),
    http_request_count INTEGER,
    record_count INTEGER,
    failure_reason VARCHAR(500),
    quality_summary TEXT
);

CREATE INDEX idx_ingestion_run_source_started
    ON ingestion_run (source_system, dataset_code, started_at);

CREATE INDEX idx_ingestion_run_status
    ON ingestion_run (run_status);

CREATE TABLE availability_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(60) NOT NULL,
    dataset_code VARCHAR(80) NOT NULL,
    snapshot_status VARCHAR(40) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6),
    system_fetched_at TIMESTAMP(6),
    source_observed_at TIMESTAMP(6),
    published_at TIMESTAMP(6),
    expected_record_count INTEGER,
    actual_record_count INTEGER,
    quality_status VARCHAR(40) NOT NULL,
    quality_reason VARCHAR(500),
    ingestion_run_id BIGINT,
    CONSTRAINT fk_availability_snapshot_ingestion_run
        FOREIGN KEY (ingestion_run_id) REFERENCES ingestion_run (id)
);

CREATE INDEX idx_availability_snapshot_source_status
    ON availability_snapshot (source_system, dataset_code, snapshot_status);

CREATE INDEX idx_availability_snapshot_published
    ON availability_snapshot (published_at);

CREATE TABLE availability_observation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    parking_place_id BIGINT NOT NULL,
    source_system VARCHAR(60) NOT NULL,
    dataset_code VARCHAR(80) NOT NULL,
    source_place_id VARCHAR(120) NOT NULL,
    availability_status VARCHAR(50) NOT NULL,
    available_car_spaces INTEGER,
    total_car_spaces INTEGER,
    is_available_spot BOOLEAN,
    raw_status_code VARCHAR(80),
    raw_availability_value VARCHAR(80),
    source_observed_at TIMESTAMP(6),
    system_fetched_at TIMESTAMP(6) NOT NULL,
    quality_status VARCHAR(40) NOT NULL,
    quality_reason VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_availability_observation_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES availability_snapshot (id),
    CONSTRAINT fk_availability_observation_parking_place
        FOREIGN KEY (parking_place_id) REFERENCES parking_place (id)
);

CREATE INDEX idx_availability_observation_snapshot_place
    ON availability_observation (snapshot_id, parking_place_id);

CREATE INDEX idx_availability_observation_place_fetched
    ON availability_observation (parking_place_id, system_fetched_at);

CREATE INDEX idx_availability_observation_dataset_fetched
    ON availability_observation (dataset_code, system_fetched_at);

CREATE TABLE source_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(60) NOT NULL,
    dataset_code VARCHAR(80) NOT NULL,
    active_snapshot_id BIGINT,
    last_successful_fetched_at TIMESTAMP(6),
    last_source_observed_at TIMESTAMP(6),
    next_allowed_refresh_at TIMESTAMP(6),
    refresh_status VARCHAR(40) NOT NULL,
    failure_reason VARCHAR(500),
    consecutive_failure_count INTEGER NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_source_state_active_snapshot
        FOREIGN KEY (active_snapshot_id) REFERENCES availability_snapshot (id)
);

CREATE INDEX idx_source_state_source
    ON source_state (source_system, dataset_code);

CREATE INDEX idx_source_state_next_refresh
    ON source_state (next_allowed_refresh_at);
