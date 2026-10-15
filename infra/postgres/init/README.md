# PostgreSQL local

`10-app-role.sh` é executado somente na inicialização de um volume novo. Para volume já existente, rode `bash scripts/dev/ensure-app-db-role.sh` antes de subir o backend. O papel de migrations (`POSTGRES_USER`) continua separado do login runtime `agendamento_app`; V5 concede a este último somente as permissões runtime documentadas.
