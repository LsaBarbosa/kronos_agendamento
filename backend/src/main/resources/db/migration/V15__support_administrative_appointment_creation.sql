ALTER TABLE appointment_history DROP CONSTRAINT ck_appointment_history_initial;
ALTER TABLE appointment_history ADD COLUMN reason TEXT;
ALTER TABLE appointment_history ADD CONSTRAINT ck_appointment_history_initial CHECK (
    from_status IS NULL AND to_status = 'CONFIRMED' AND (
        (actor_type = 'PUBLIC' AND actor_ref = 'PUBLIC_BOOKING'
            AND reason_code = 'APPOINTMENT_CREATED' AND reason IS NULL) OR
        (actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN'
            AND ((reason_code = 'ADMIN_BOOKING' AND reason IS NULL) OR
                 (reason_code = 'ADMIN_BOOKING_OVERRIDE'
                    AND reason IS NOT NULL AND length(btrim(reason)) BETWEEN 1 AND 500)))
    )
);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_action_actor;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_action_actor CHECK (
    (action = 'TENANT_CREATED' AND actor_type = 'SYSTEM') OR
    (action = 'TENANT_ADMIN_LINKED' AND actor_type = 'SYSTEM' AND actor_ref = 'CONTROLLED_DEMO_PROVISIONER') OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'SYSTEM' AND actor_ref = 'PUBLIC_BOOKING') OR
    (action = 'TENANT_RENAMED' AND actor_type = 'ADMIN') OR
    (action IN (
        'SERVICE_CREATED', 'SERVICE_UPDATED', 'SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED',
        'SCHEDULE_UPDATED', 'AVAILABILITY_POLICY_UPDATED',
        'SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED',
        'BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED',
        'PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED', 'PUBLIC_PROFILE_UPDATED',
        'APPOINTMENT_CONFIRMED'
    ) AND actor_type = 'ADMIN' AND actor_ref = 'TENANT_ADMIN')
);

ALTER TABLE audit_event DROP CONSTRAINT ck_audit_details_allowlist;
ALTER TABLE audit_event ADD CONSTRAINT ck_audit_details_allowlist CHECK (
    (action = 'TENANT_CREATED' AND details = '{"changed_fields":["name","slug","timezone"]}'::jsonb) OR
    (action = 'TENANT_RENAMED' AND details->'changed_fields' = '["name"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action = 'TENANT_ADMIN_LINKED' AND details = '{"changed_fields":["identity","membership"]}'::jsonb) OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'SYSTEM'
        AND details = '{"changed_fields":["appointment"],"version_before":0,"version_after":1}'::jsonb) OR
    (action = 'APPOINTMENT_CONFIRMED' AND actor_type = 'ADMIN'
        AND details->'changed_fields' = '["appointment"]'::jsonb
        AND details->'version_before' = '0'::jsonb AND details->'version_after' = '1'::jsonb
        AND jsonb_typeof(details->'override_policies') = 'array'
        AND NOT jsonb_path_exists(details, '$.override_policies[*] ? (@ != "OUTSIDE_WEEKLY_SCHEDULE" && @ != "MIN_NOTICE")')
        AND jsonb_typeof(details->'reason_provided') = 'boolean'
        AND details->>'client_restriction_status' IN ('NOT_TRACKED', 'CLEAR', 'RESTRICTED')
        AND details - ARRAY['changed_fields', 'version_before', 'version_after', 'override_policies', 'reason_provided', 'client_restriction_status']::text[] = '{}'::jsonb
        AND ((jsonb_array_length(details->'override_policies') = 0 AND details->'reason_provided' = 'false'::jsonb)
            OR (jsonb_array_length(details->'override_policies') > 0 AND details->'reason_provided' = 'true'::jsonb))) OR
    (action = 'SERVICE_CREATED' AND details = '{"changed_fields":["name","description","duration_minutes","buffer_after_minutes","status"]}'::jsonb) OR
    (action = 'SERVICE_UPDATED' AND jsonb_typeof(details->'changed_fields') = 'array'
        AND jsonb_array_length(details->'changed_fields') > 0
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb
        AND NOT jsonb_path_exists(details->'changed_fields', '$[*] ? (@.type() != "string" || (@ != "name" && @ != "description" && @ != "duration_minutes" && @ != "buffer_after_minutes"))')) OR
    (action IN ('SERVICE_ACTIVATED', 'SERVICE_DEACTIVATED') AND details = '{"changed_fields":["status"]}'::jsonb) OR
    (action = 'SCHEDULE_UPDATED' AND details->'changed_fields' = '["weekly_periods"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action = 'AVAILABILITY_POLICY_UPDATED' AND details->'changed_fields' = '["availability_policy"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action IN ('SCHEDULE_EXCEPTION_CREATED', 'SCHEDULE_EXCEPTION_UPDATED', 'SCHEDULE_EXCEPTION_REMOVED')
        AND details->'changed_fields' = '["schedule_exception"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action IN ('BLOCKED_PERIOD_CREATED', 'BLOCKED_PERIOD_CANCELLED')
        AND details->'changed_fields' = '["blocked_period"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action IN ('PUBLICATION_PUBLISHED', 'PUBLICATION_UNPUBLISHED')
        AND details->'changed_fields' = '["publication"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb) OR
    (action = 'PUBLIC_PROFILE_UPDATED' AND details->'changed_fields' = '["contact_phone"]'::jsonb
        AND jsonb_typeof(details->'version_before') = 'number'
        AND jsonb_typeof(details->'version_after') = 'number'
        AND details - ARRAY['changed_fields', 'version_before', 'version_after']::text[] = '{}'::jsonb)
);
