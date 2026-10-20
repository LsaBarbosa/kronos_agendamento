ALTER TABLE appointment_history
    ADD COLUMN corrects_history_id UUID;

ALTER TABLE appointment_history
    ADD CONSTRAINT uq_appointment_history_scope UNIQUE (tenant_id, appointment_id, history_id),
    ADD CONSTRAINT fk_appointment_history_correction
        FOREIGN KEY (tenant_id, appointment_id, corrects_history_id)
        REFERENCES appointment_history (tenant_id, appointment_id, history_id)
        ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_appointment_history_corrected_fact
    ON appointment_history (corrects_history_id)
    WHERE corrects_history_id IS NOT NULL;

CREATE INDEX ix_appointment_history_no_show_facts
    ON appointment_history (tenant_id, occurred_at DESC, history_id DESC)
    WHERE to_status = 'NO_SHOW' AND reason_code = 'APPOINTMENT_NO_SHOW';

ALTER TABLE appointment_history DROP CONSTRAINT ck_appointment_history_operation;
ALTER TABLE appointment_history ADD CONSTRAINT ck_appointment_history_operation CHECK ((
    (from_status IS NULL AND to_status = 'CONFIRMED' AND
      ((actor_type = 'PUBLIC' AND actor_ref = 'PUBLIC_BOOKING' AND
        reason_code = 'APPOINTMENT_CREATED' AND reason IS NULL) OR
       (actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN' AND
        ((reason_code = 'ADMIN_BOOKING' AND reason IS NULL) OR
         (reason_code = 'ADMIN_BOOKING_OVERRIDE' AND reason IS NOT NULL AND length(btrim(reason)) BETWEEN 1 AND 500)))
       ) AND previous_starts_at IS NULL AND previous_occupied_until IS NULL AND
       new_starts_at IS NULL AND new_occupied_until IS NULL AND version_before IS NULL AND version_after IS NULL AND corrects_history_id IS NULL) OR
    (from_status = 'CONFIRMED' AND to_status = 'CONFIRMED' AND actor_type = 'ADMIN' AND
      actor_ref = 'TENANT_ADMIN' AND reason_code = 'APPOINTMENT_RESCHEDULED' AND
      previous_starts_at IS NOT NULL AND previous_occupied_until IS NOT NULL AND
      previous_occupied_until > previous_starts_at AND
      new_starts_at IS NOT NULL AND new_occupied_until IS NOT NULL AND
      new_occupied_until > new_starts_at AND
      version_before IS NOT NULL AND version_after IS NOT NULL AND
      version_before >= 1 AND version_after = version_before + 1 AND corrects_history_id IS NULL AND
      (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500)) OR
    (from_status = 'CONFIRMED' AND
      to_status IN ('CANCELLED_BY_PROVIDER', 'COMPLETED', 'NO_SHOW') AND
      actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN' AND
      reason_code = CASE to_status
        WHEN 'CANCELLED_BY_PROVIDER' THEN 'APPOINTMENT_CANCELLED_BY_PROVIDER'
        WHEN 'COMPLETED' THEN 'APPOINTMENT_COMPLETED'
        WHEN 'NO_SHOW' THEN 'APPOINTMENT_NO_SHOW'
      END AND
      previous_starts_at IS NULL AND previous_occupied_until IS NULL AND
      new_starts_at IS NULL AND new_occupied_until IS NULL AND
      version_before IS NOT NULL AND version_after IS NOT NULL AND
      version_before >= 1 AND version_after = version_before + 1 AND corrects_history_id IS NULL AND
      (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500)) OR
    (from_status = 'CONFIRMED' AND to_status = 'CANCELLED_BY_CLIENT' AND
      actor_type = 'CLIENT' AND actor_ref = 'VERIFIED_CLIENT' AND
      reason_code = 'APPOINTMENT_CANCELLED_BY_CLIENT' AND reason IS NULL AND
      previous_starts_at IS NULL AND previous_occupied_until IS NULL AND
      new_starts_at IS NULL AND new_occupied_until IS NULL AND
      version_before IS NOT NULL AND version_after IS NOT NULL AND
      version_before >= 1 AND version_after = version_before + 1 AND corrects_history_id IS NULL) OR
    (from_status = 'NO_SHOW' AND to_status = 'COMPLETED' AND
      actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN' AND
      reason_code = 'APPOINTMENT_NO_SHOW_CORRECTED' AND
      reason IS NOT NULL AND length(btrim(reason)) BETWEEN 1 AND 500 AND
      previous_starts_at IS NULL AND previous_occupied_until IS NULL AND
      new_starts_at IS NULL AND new_occupied_until IS NULL AND
      version_before IS NOT NULL AND version_after = version_before + 1 AND
      version_before >= 1 AND corrects_history_id IS NOT NULL)
) IS TRUE);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action CHECK (action IN (
    'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_ADMIN_LINKED',
    'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
    'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED', 'CLIENT_NO_SHOW_POLICY_UPDATED',
    'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
    'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED',
    'PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED', 'PUBLIC_PROFILE_UPDATED',
    'APPOINTMENT_CONFIRMED', 'APPOINTMENT_RESCHEDULED',
    'APPOINTMENT_CANCELLED_BY_PROVIDER', 'APPOINTMENT_CANCELLED_BY_CLIENT',
    'APPOINTMENT_COMPLETED', 'APPOINTMENT_NO_SHOW_RECORDED', 'APPOINTMENT_NO_SHOW_CORRECTED'
));

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK (
    (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
    (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'SYSTEM' AND actor_ref = 'PUBLIC_BOOKING') OR
    (action = 'TENANT_RENAMED' AND actor_type = 'ADMIN') OR
    (action = 'APPOINTMENT_CANCELLED_BY_CLIENT' AND actor_type = 'CLIENT' AND actor_ref = 'VERIFIED_CLIENT') OR
    (action IN (
        'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
        'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED', 'CLIENT_NO_SHOW_POLICY_UPDATED',
        'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
        'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED',
        'PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED', 'PUBLIC_PROFILE_UPDATED',
        'APPOINTMENT_RESCHEDULED', 'APPOINTMENT_CONFIRMED',
        'APPOINTMENT_CANCELLED_BY_PROVIDER', 'APPOINTMENT_COMPLETED', 'APPOINTMENT_NO_SHOW_RECORDED',
        'APPOINTMENT_NO_SHOW_CORRECTED'
    ) AND actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN')
);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_details_allowlist;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_details_allowlist CHECK ((
    (action = 'CLIENT_NO_SHOW_POLICY_UPDATED'
      AND details ?& ARRAY['changed_fields','version_before','version_after']
      AND details->'changed_fields' = '["no_show_threshold"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::numeric = (details->>'version_before')::numeric + 1
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'APPOINTMENT_CANCELLED_BY_CLIENT' AND actor_type = 'CLIENT'
      AND details ?& ARRAY['changed_fields','version_before','version_after']
      AND details->'changed_fields' = '["status"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::numeric = (details->>'version_before')::numeric + 1
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action IN ('APPOINTMENT_CANCELLED_BY_PROVIDER', 'APPOINTMENT_COMPLETED', 'APPOINTMENT_NO_SHOW_RECORDED', 'APPOINTMENT_NO_SHOW_CORRECTED')
      AND details ?& ARRAY['changed_fields','version_before','version_after','reason_provided']
      AND details->'changed_fields' = '["status"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::numeric = (details->>'version_before')::numeric + 1
      AND jsonb_typeof(details->'reason_provided') = 'boolean'
      AND (action <> 'APPOINTMENT_NO_SHOW_CORRECTED' OR details->'reason_provided' = 'true'::jsonb)
      AND details - ARRAY['changed_fields','version_before','version_after','reason_provided']::text[] = '{}'::jsonb) OR
    (action = 'APPOINTMENT_RESCHEDULED'
      AND details ?& ARRAY['changed_fields','version_before','version_after','override_policies','reason_provided']
      AND details->'changed_fields' = '["appointment"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number'
      AND jsonb_typeof(details->'version_after') = 'number'
      AND (details->>'version_after')::BIGINT = (details->>'version_before')::BIGINT + 1
      AND jsonb_typeof(details->'override_policies') = 'array'
      AND NOT jsonb_path_exists(details, '$.override_policies[*] ? (@ != "OUTSIDE_WEEKLY_SCHEDULE" && @ != "MIN_NOTICE")')
      AND jsonb_typeof(details->'reason_provided') = 'boolean'
      AND ((jsonb_array_length(details->'override_policies') = 0 AND details->'reason_provided' = 'false'::jsonb)
        OR (jsonb_array_length(details->'override_policies') > 0 AND details->'reason_provided' = 'true'::jsonb))
      AND details - ARRAY['changed_fields','version_before','version_after','override_policies','reason_provided']::text[] = '{}'::jsonb) OR
    (action = 'TENANT_CREATED' AND details = '{"changed_fields":["name","slug","timezone"]}'::jsonb) OR
    (action = 'TENANT_RENAMED' AND details->'changed_fields' = '["name"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'TENANT_ADMIN_LINKED' AND details = '{"changed_fields":["identity","membership"]}'::jsonb) OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'SYSTEM'
      AND details = '{"changed_fields":["appointment"],"version_before":0,"version_after":1}'::jsonb) OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'ADMIN'
      AND details->'changed_fields' = '["appointment"]'::jsonb AND details->'version_before' = '0'::jsonb
      AND details->'version_after' = '1'::jsonb AND jsonb_typeof(details->'override_policies') = 'array'
      AND NOT jsonb_path_exists(details, '$.override_policies[*] ? (@ != "OUTSIDE_WEEKLY_SCHEDULE" && @ != "MIN_NOTICE")')
      AND jsonb_typeof(details->'reason_provided') = 'boolean'
      AND details->>'client_restriction_status' IN ('NOT_TRACKED', 'CLEAR', 'RESTRICTED')
      AND details - ARRAY['changed_fields','version_before','version_after','override_policies','reason_provided','client_restriction_status']::text[] = '{}'::jsonb
      AND ((jsonb_array_length(details->'override_policies') = 0 AND details->'reason_provided' = 'false'::jsonb)
        OR (jsonb_array_length(details->'override_policies') > 0 AND details->'reason_provided' = 'true'::jsonb))) OR
    (action = 'SERVICE_CREATED' AND details = '{"changed_fields":["name","description","duration_minutes","buffer_after_minutes","status"]}'::jsonb) OR
    ((action = 'SERVICE_ACTIVATED' OR action = 'SERVICE_DEACTIVATED') AND details = '{"changed_fields":["status"]}'::jsonb) OR
    (action = 'SCHEDULE_UPDATED' AND details->'changed_fields' = '["weekly_periods"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'AVAILABILITY_POLICY_UPDATED' AND details->'changed_fields' = '["availability_policy"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action IN ('SCHEDULE_EXCEPTION_CREATED','SCHEDULE_EXCEPTION_UPDATED','SCHEDULE_EXCEPTION_REMOVED')
      AND details->'changed_fields' = '["schedule_exception"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action IN ('BLOCKED_PERIOD_CREATED','BLOCKED_PERIOD_CANCELLED')
      AND details->'changed_fields' = '["blocked_period"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action IN ('PUBLICATION_PUBLISHED','PUBLICATION_UNPUBLISHED')
      AND details->'changed_fields' = '["publication"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'PUBLIC_PROFILE_UPDATED' AND details->'changed_fields' = '["contact_phone"]'::jsonb
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb) OR
    (action = 'SERVICE_UPDATED' AND jsonb_typeof(details->'changed_fields') = 'array'
      AND jsonb_array_length(details->'changed_fields') > 0
      AND jsonb_typeof(details->'version_before') = 'number' AND jsonb_typeof(details->'version_after') = 'number'
      AND details - ARRAY['changed_fields','version_before','version_after']::text[] = '{}'::jsonb
      AND NOT jsonb_path_exists(details->'changed_fields', '$[*] ? (@.type() != "string" || (@ != "name" && @ != "description" && @ != "duration_minutes" && @ != "buffer_after_minutes"))'))
) IS TRUE);

ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_payload_allowlist;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_payload_allowlist CHECK (
    (event_type IN ('AppointmentConfirmed', 'AppointmentRescheduled', 'AppointmentCancelled', 'AppointmentCompleted', 'ClientNoShowRecorded', 'ClientNoShowCorrected')
      AND aggregate_type = 'APPOINTMENT'
      AND payload ?& ARRAY['appointmentId','version']
      AND payload - ARRAY['appointmentId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'appointmentId') = 'string'
      AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type = 'TENANT_RENAMED' AND aggregate_type = 'TENANT'
      AND payload ?& ARRAY['tenantId','version'] AND payload - ARRAY['tenantId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'tenantId') = 'string' AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type = 'ServiceCreated' AND aggregate_type = 'SERVICE'
      AND payload ?& ARRAY['serviceId','version'] AND payload - ARRAY['serviceId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'serviceId') = 'string' AND jsonb_typeof(payload->'version') = 'number') OR
    (event_type IN ('ScheduleChanged','AvailabilityPolicyChanged')
      AND payload ?& ARRAY['tenantId','version'] AND payload - ARRAY['tenantId','version']::text[] = '{}'::jsonb
      AND jsonb_typeof(payload->'tenantId') = 'string' AND jsonb_typeof(payload->'version') = 'number')
);
