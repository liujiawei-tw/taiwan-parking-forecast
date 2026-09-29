-- S2-020 official-source seed data for New Taipei Xinzhuang MVP flow verification.
-- This file contains a small, traceable subset of NTPC Open Data records, not the full Xinzhuang catalog.
-- Roadside records were sampled from dataset 54A507C4-C038-41B5-BF60-BBECB9D052C6 with areacode 65000050.
-- Off-street records were sampled from static dataset B1464EF0-9C7C-4A6F-ABF7-6BDF32847E68 and verified
-- to have matching IDs in availability dataset e09b35a5-a738-48cc-b0f5-570b67ad9c78.

DELETE FROM source_mapping
WHERE (
        source_system = 'SYSTEM'
        AND dataset_code IN ('NTPC_ROADSIDE_SPOT', 'NTPC_OFF_STREET_AVAILABILITY')
        AND source_place_id IN (
            'SEED-XINZHUANG-ROAD-001',
            'SEED-XINZHUANG-ROAD-002',
            'SEED-XINZHUANG-ROAD-003',
            'SEED-XINZHUANG-LOT-001',
            'SEED-XINZHUANG-LOT-002'
        )
    )
    OR (
        source_system = 'NTPC_OPEN_DATA'
        AND dataset_code IN ('NTPC_ROADSIDE_SPOT', 'NTPC_OFF_STREET_AVAILABILITY')
        AND source_place_id IN (
            '153854',
            '153855',
            '154341',
            '154342',
            '154343',
            '96316',
            '155063',
            '050087',
            '050029',
            '050213'
        )
    );

DELETE FROM parking_place
WHERE id IN (
    910001,
    910002,
    910003,
    920001,
    920002,
    910101,
    910102,
    910103,
    910104,
    910105,
    910106,
    910107,
    920101,
    920102,
    920103
);

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
        910101,
        'ON_STREET_SPOT',
        '中華路一段路邊汽車格 20',
        25.0388680,
        121.4521260,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        910102,
        'ON_STREET_SPOT',
        '中華路一段路邊汽車格 21',
        25.0389570,
        121.4521400,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        910103,
        'ON_STREET_SPOT',
        '中華路一段路邊汽車格 22',
        25.0391870,
        121.4521820,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        910104,
        'ON_STREET_SPOT',
        '中華路一段路邊汽車格 23',
        25.0392350,
        121.4521940,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        910105,
        'ON_STREET_SPOT',
        '中華路一段路邊汽車格 24',
        25.0392840,
        121.4522060,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        910106,
        'ON_STREET_SPOT',
        '化成路路邊汽車格 2',
        25.0431880,
        121.4649970,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        910107,
        'ON_STREET_SPOT',
        '化成路路邊汽車格 14',
        25.0436610,
        121.4650590,
        NULL,
        NULL,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        920101,
        'OFF_STREET_LOT',
        '宜家家居股份有限公司新莊分公司停車場',
        25.0414214,
        121.4647633,
        25.0414214,
        121.4647633,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        920102,
        'OFF_STREET_LOT',
        '新莊區農會停車場',
        25.0378576,
        121.4582263,
        25.0378576,
        121.4582263,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        920103,
        'OFF_STREET_LOT',
        '博客停車場藏壽司思源場停車場',
        25.0481265,
        121.4601752,
        25.0481265,
        121.4601752,
        'CONFIRMED',
        'ACTIVE',
        'NEW_TAIPEI_XINZHUANG',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
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
        'NTPC_OPEN_DATA',
        'NTPC_ROADSIDE_SPOT',
        '153854',
        910101,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '中華路一段 cellid=20 roadid=R27',
        'areacode=65000050; name=汽車停車位; lat=25.038868; lng=121.452126',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        'NTPC_OPEN_DATA',
        'NTPC_ROADSIDE_SPOT',
        '153855',
        910102,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '中華路一段 cellid=21 roadid=R27',
        'areacode=65000050; name=汽車停車位; lat=25.038957; lng=121.452140',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        'NTPC_OPEN_DATA',
        'NTPC_ROADSIDE_SPOT',
        '154341',
        910103,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '中華路一段 cellid=22 roadid=R27',
        'areacode=65000050; name=汽車停車位; lat=25.039187; lng=121.452182',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        'NTPC_OPEN_DATA',
        'NTPC_ROADSIDE_SPOT',
        '154342',
        910104,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '中華路一段 cellid=23 roadid=R27',
        'areacode=65000050; name=汽車停車位; lat=25.039235; lng=121.452194',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        'NTPC_OPEN_DATA',
        'NTPC_ROADSIDE_SPOT',
        '154343',
        910105,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '中華路一段 cellid=24 roadid=R27',
        'areacode=65000050; name=汽車停車位; lat=25.039284; lng=121.452206',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        'NTPC_OPEN_DATA',
        'NTPC_ROADSIDE_SPOT',
        '96316',
        910106,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '化成路 cellid=2 roadid=R77',
        'areacode=65000050; name=汽車停車位; lat=25.043188; lng=121.464997',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        'NTPC_OPEN_DATA',
        'NTPC_ROADSIDE_SPOT',
        '155063',
        910107,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '化成路 cellid=14 roadid=R77',
        'areacode=65000050; name=汽車停車位; lat=25.043661; lng=121.465059',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        'NTPC_OPEN_DATA',
        'NTPC_OFF_STREET_AVAILABILITY',
        '050087',
        920101,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '宜家家居股份有限公司新莊分公司停車場',
        '新北市新莊區中正路1號B1~B3; TOTALCAR=641; TWD97=(296897.8,2770446)',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        'NTPC_OPEN_DATA',
        'NTPC_OFF_STREET_AVAILABILITY',
        '050029',
        920102,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '新莊區農會停車場',
        '新北市新莊區中正路80號B2-B4; TOTALCAR=345; TWD97=(296239.5,2770049)',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    ),
    (
        'NTPC_OPEN_DATA',
        'NTPC_OFF_STREET_AVAILABILITY',
        '050213',
        920103,
        'VERIFIED',
        '2026-09-29 00:00:00',
        NULL,
        '博客停車場開發股份有限公司藏壽司思源場停車場',
        '新北市新莊區思源路168號; TOTALCAR=26; TWD97=(296432.3,2771187.1)',
        '2026-09-29 00:00:00',
        '2026-09-29 00:00:00'
    );
