ALTER TABLE client_access_session
    ADD COLUMN challenge_id UUID;

ALTER TABLE client_access_session
    ADD CONSTRAINT uq_client_access_session_challenge
        UNIQUE (tenant_id, challenge_id),
    ADD CONSTRAINT fk_client_access_session_challenge
        FOREIGN KEY (tenant_id, challenge_id)
        REFERENCES client_access_challenge (tenant_id, challenge_id)
        ON DELETE RESTRICT;

COMMENT ON COLUMN client_access_session.challenge_id IS
    'Challenge consumed to issue this session; NULL only for legacy sessions predating V20.';
