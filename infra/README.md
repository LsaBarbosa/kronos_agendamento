# Infraestrutura local

O ambiente de desenvolvimento está no [compose.yaml](../compose.yaml), com PostgreSQL 16 e backend opcional no perfil `app`. As variáveis de exemplo estão em `.env.example` na raiz; a cópia `.env` não deve ser versionada.

Não há infraestrutura de produção provisionada. Secrets manager, TLS, banco gerenciado, backups, monitoramento, imagens imutáveis e rollout serão tratados nas histórias de operação do documento 06.
