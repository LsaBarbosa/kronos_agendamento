ALTER TABLE billing_annual_renewal_notice
    DROP CONSTRAINT ck_annual_notice_state,
    ADD CONSTRAINT ck_annual_notice_state CHECK ((
        (state IN ('SCHEDULED', 'READY') AND obsolete_reason IS NULL)
        OR (state = 'OBSOLETE' AND obsolete_reason IN (
            'PERIOD_CHANGED', 'SUBSCRIPTION_INELIGIBLE', 'PAID_PERIOD_EXPIRED'
        ))
    ) IS TRUE);

ALTER TABLE billing_event_inbox
    DROP CONSTRAINT billing_event_inbox_disposition_check,
    ADD CONSTRAINT billing_event_inbox_disposition_check CHECK (
        disposition IN (
            'UNLINKED', 'PENDING', 'APPLIED', 'OLDER', 'DECLINED', 'MISMATCHED',
            'EXPIRED_PERIOD'
        )
    );

COMMENT ON COLUMN billing_annual_renewal_notice.obsolete_reason IS
    'Explains why no annual pre-expiry notice will be sent; PAID_PERIOD_EXPIRED is terminal at now >= paid_through_at.';
COMMENT ON COLUMN billing_event_inbox.disposition IS
    'EXPIRED_PERIOD records authenticated payment evidence whose authoritative paid-through instant is not in the future; it does not reactivate access.';
