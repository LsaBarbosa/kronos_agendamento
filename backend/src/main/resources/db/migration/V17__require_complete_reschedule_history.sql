ALTER TABLE appointment_history DROP CONSTRAINT ck_appointment_history_operation;

ALTER TABLE appointment_history ADD CONSTRAINT ck_appointment_history_operation CHECK ((
    (from_status IS NULL AND to_status = 'CONFIRMED' AND
      ((actor_type = 'PUBLIC' AND actor_ref = 'PUBLIC_BOOKING' AND
        reason_code = 'APPOINTMENT_CREATED' AND reason IS NULL) OR
       (actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN' AND
        ((reason_code = 'ADMIN_BOOKING' AND reason IS NULL) OR
         (reason_code = 'ADMIN_BOOKING_OVERRIDE' AND reason IS NOT NULL AND length(btrim(reason)) BETWEEN 1 AND 500)))
       ) AND previous_starts_at IS NULL AND previous_occupied_until IS NULL AND
       new_starts_at IS NULL AND new_occupied_until IS NULL AND version_before IS NULL AND version_after IS NULL) OR
    (from_status = 'CONFIRMED' AND to_status = 'CONFIRMED' AND actor_type = 'ADMIN' AND
      actor_ref = 'TENANT_ADMIN' AND reason_code = 'APPOINTMENT_RESCHEDULED' AND
      previous_starts_at IS NOT NULL AND previous_occupied_until IS NOT NULL AND
      previous_occupied_until > previous_starts_at AND
      new_starts_at IS NOT NULL AND new_occupied_until IS NOT NULL AND
      new_occupied_until > new_starts_at AND
      version_before IS NOT NULL AND version_after IS NOT NULL AND
      version_before >= 1 AND version_after = version_before + 1 AND
      (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500))
) IS TRUE);
