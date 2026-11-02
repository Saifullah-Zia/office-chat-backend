# Deploying on Railway

The full step-by-step guide (database, backend, frontend, variables, volume, data migration) is in the
**office-chat-frontend** repo: `DEPLOY-RAILWAY.md`.

This repo's part: `Dockerfile` + `railway.json` (Railway builds the image and health-checks `/api/health`).
All settings come from environment variables; see `src/main/resources/application.properties`.
