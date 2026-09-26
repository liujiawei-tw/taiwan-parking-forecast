-- S2-011 small seed data for New Taipei Xinzhuang MVP flow verification.
-- This file intentionally contains a tiny synthetic catalog. It is not an official or complete parking dataset.

DELETE FROM source_mapping
WHERE source_system = 'SYSTEM'
  AND dataset_code IN ('NTPC_ROADSIDE_SPOT', 'NTPC_OFF_STREET_AVAILABILITY')
  AND source_place_id IN (
      'SEED-XINZHUANG-ROAD-001',
      'SEED-XINZHUANG-ROAD-002',
      'SEED-XINZHUANG-ROAD-003',
      'SEED-XINZHUANG-LOT-001',
      'SEED-XINZHUANG-LOT-002'
  );

DELETE FROM parking_place
WHERE id IN (910001, 910002, 910003, 920001, 920002);

INSERT INTO parking_place (
    id,
    place_type,
    display_name,
    latitude,
    longitude,
    entrance_latitude,
    entrance_longitude,
    car_eligible_status,
    active_status,
    service_area,
    created_at,
    updated_at
) VALUES
    (
        910001,
        'ON_STREET_SPOT',
        '新莊中正路示範路邊汽車格 A',
        25.0357810,
        121.4509820,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    ),
    (
        910002,
        'ON_STREET_SPOT',
        '新莊中華路示範路邊汽車格 B',
        25.0391050,
        121.4523350,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    ),
    (
        910003,
        'ON_STREET_SPOT',
        '新莊公園路示範路邊汽車格 C',
        25.0376200,
        121.4489100,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    ),
    (
        920001,
        'OFF_STREET_LOT',
        '新莊區公所周邊示範停車場',
        25.0372100,
        121.4530200,
        25.0371880,
        121.4527800,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    ),
    (
        920002,
        'OFF_STREET_LOT',
        '新莊運動中心周邊示範停車場',
        25.0418400,
        121.4526400,
        25.0417000,
        121.4525000,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    );

INSERT INTO source_mapping (
    source_system,
    dataset_code,
    source_place_id,
    parking_place_id,
    mapping_status,
    valid_from,
    valid_to,
    raw_name_hint,
    raw_location_hint,
    created_at,
    updated_at
) VALUES
    (
        'SYSTEM',
        'NTPC_ROADSIDE_SPOT',
        'SEED-XINZHUANG-ROAD-001',
        910001,
        'VERIFIED',
        '2026-09-26 00:00:00',
        NULL,
        '新莊中正路示範路邊汽車格 A',
        'Synthetic seed for Xinzhuang MVP catalog flow',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    ),
    (
        'SYSTEM',
        'NTPC_ROADSIDE_SPOT',
        'SEED-XINZHUANG-ROAD-002',
        910002,
        'VERIFIED',
        '2026-09-26 00:00:00',
        NULL,
        '新莊中華路示範路邊汽車格 B',
        'Synthetic seed for Xinzhuang MVP catalog flow',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    ),
    (
        'SYSTEM',
        'NTPC_ROADSIDE_SPOT',
        'SEED-XINZHUANG-ROAD-003',
        910003,
        'VERIFIED',
        '2026-09-26 00:00:00',
        NULL,
        '新莊公園路示範路邊汽車格 C',
        'Synthetic seed for Xinzhuang MVP catalog flow',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    ),
    (
        'SYSTEM',
        'NTPC_OFF_STREET_AVAILABILITY',
        'SEED-XINZHUANG-LOT-001',
        920001,
        'VERIFIED',
        '2026-09-26 00:00:00',
        NULL,
        '新莊區公所周邊示範停車場',
        'Synthetic seed for Xinzhuang MVP catalog flow',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    ),
    (
        'SYSTEM',
        'NTPC_OFF_STREET_AVAILABILITY',
        'SEED-XINZHUANG-LOT-002',
        920002,
        'VERIFIED',
        '2026-09-26 00:00:00',
        NULL,
        '新莊運動中心周邊示範停車場',
        'Synthetic seed for Xinzhuang MVP catalog flow',
        '2026-09-26 00:00:00',
        '2026-09-26 00:00:00'
    );

