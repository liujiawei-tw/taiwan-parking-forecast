-- Correct source 050064, whose official TW97 coordinate was published as 0/0.
-- The replacement is the New Taipei address-position result for the official address,
-- transformed from TWD97 / EPSG:3826 to WGS84. It is an address point, not a surveyed
-- coordinate for each sub-lot entrance.
UPDATE parking_place
SET display_name = '新莊體育園區停車場（正好停；體育館地下及公園路平面區）',
    latitude = 25.0405039,
    longitude = 121.4519880,
    entrance_latitude = 25.0405039,
    entrance_longitude = 121.4519880,
    catalog_notes = CASE
        WHEN catalog_notes IS NULL OR catalog_notes = '' THEN
            '官方來源 050064 回報園區合計 706 個汽車位；體育局委外資料分列網球場地下 189、體育館地下 399、臨復興路 37、臨公園路 32、臨中華路 49 格。官方來源沒有各區獨立 ID 或分區即時剩餘位，故維持單一場站，避免重複計算。使用者提及的「公園路 D 區」是否即為臨公園路區，官方資料尚未確認。官方 TW97X/TW97Y 為 0/0，座標改採新北市門牌位置服務對中華路一段75號的地址點；此為場址地址點，非分區入口的現地測量。'
        ELSE CONCAT(
            catalog_notes,
            '；官方來源 050064 回報園區合計 706 個汽車位；體育局委外資料分列網球場地下 189、體育館地下 399、臨復興路 37、臨公園路 32、臨中華路 49 格。官方來源沒有各區獨立 ID 或分區即時剩餘位，故維持單一場站，避免重複計算。使用者提及的「公園路 D 區」是否即為臨公園路區，官方資料尚未確認。官方 TW97X/TW97Y 為 0/0，座標改採新北市門牌位置服務對中華路一段75號的地址點；此為場址地址點，非分區入口的現地測量。'
        )
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE service_area = 'NEW_TAIPEI_XINZHUANG'
  AND place_type = 'OFF_STREET_LOT'
  AND latitude = 0.0000000
  AND longitude = 118.7545661
  AND id IN (
      SELECT parking_place_id
      FROM source_mapping
      WHERE source_system = 'NTPC_OPEN_DATA'
        AND dataset_code = 'NTPC_OFF_STREET_AVAILABILITY'
        AND source_place_id = '050064'
  );
