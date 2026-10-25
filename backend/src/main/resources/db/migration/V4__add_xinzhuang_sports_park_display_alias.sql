-- Keep the official source as one 706-space park while surfacing common names.
UPDATE parking_place
SET display_name = '新莊體育園區停車場（園區合計；含正好停-新莊體育館地下停車場、公園路平面區）',
    updated_at = CURRENT_TIMESTAMP
WHERE service_area = 'NEW_TAIPEI_XINZHUANG'
  AND place_type = 'OFF_STREET_LOT'
  AND id IN (
      SELECT parking_place_id
      FROM source_mapping
      WHERE source_system = 'NTPC_OPEN_DATA'
        AND dataset_code = 'NTPC_OFF_STREET_AVAILABILITY'
        AND source_place_id = '050064'
  );
