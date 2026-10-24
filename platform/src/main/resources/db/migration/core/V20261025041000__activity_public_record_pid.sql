-- Generic activity targets include UUID public PIDs as well as 26-character ULIDs.
ALTER TABLE ab_activity ALTER COLUMN object_record TYPE VARCHAR(36);
