-- O schema agendamento e seu histórico são geridos pelo Flyway.
-- Pré-requisito para a futura constraint GiST de ocupação (AGD-015).
-- Em produção, provisionar a extensão com o papel de migração apropriado.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

COMMENT ON SCHEMA agendamento IS 'Schema do monólito modular App Agendamento';
