# Deploying on Railway

The full step-by-step guide (database, backend, frontend, variables, volume, data migration) is in the
**office-chat-frontend** repo: `DEPLOY-RAILWAY.md`.

This repo's part: `Dockerfile` + `railway.json` (Railway builds the image and health-checks `/api/health`).
All settings come from environment variables; see `src/main/resources/application.properties`.

## Database backups (R2)

With `R2_BACKUP_BUCKET` set, the backend runs `pg_dump` every night at 03:00 (Pakistan time) and uploads it to that
R2 bucket: `daily/office-chat-YYYY-MM-DD.dump` every day and `monthly/office-chat-YYYY-MM.dump` once a month. After
each deploy it also makes today's copy if it is missing. The logs say `Database backup saved: ...`, or
`DATABASE BACKUP FAILED: ...`. How long copies are kept is set by the bucket's lifecycle rules (daily/ 30 days,
monthly/ 365 days).

To restore: download a `.dump` from Cloudflare (R2 > bucket > object > Download), then, with PostgreSQL 18 installed,

    pg_restore --clean --if-exists --no-owner --no-acl -h <PGHOST> -p <PGPORT> -U <PGUSER> -d <PGDATABASE> office-chat-YYYY-MM-DD.dump

using the Postgres service's public connection details from Railway (Postgres > Variables / Connect). Practise it
on a local database first: `createdb test_restore` then the same command with `-h localhost -d test_restore`.
