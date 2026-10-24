ALTER TABLE parking_place ADD COLUMN address VARCHAR(500);
ALTER TABLE parking_place ADD COLUMN parking_space_type VARCHAR(120);
ALTER TABLE parking_place ADD COLUMN fee_status VARCHAR(120);
ALTER TABLE parking_place ADD COLUMN fee_description VARCHAR(1000);
ALTER TABLE parking_place ADD COLUMN charging_days VARCHAR(250);
ALTER TABLE parking_place ADD COLUMN charging_hours VARCHAR(250);
ALTER TABLE parking_place ADD COLUMN total_car_spaces INTEGER;
ALTER TABLE parking_place ADD COLUMN catalog_notes VARCHAR(2000);
