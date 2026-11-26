# Deploying the backend on the Raspberry Pi

The production API `https://chat-api.dainn.online` runs on the Raspberry Pi (arm64, Debian 13) as a Docker
Compose stack in `~/aichat`. This guide is for whoever deploys or updates it.

| Service | What it is | Reachable from |
|---|---|---|
| `db` | PostgreSQL 16 + pgvector, data in the `aichat_pgdata` volume | compose network only |
| `redis` | BullMQ queue for memory extraction | compose network only |
| `migrate` | Runs pending TypeORM migrations, then exits | — |
| `api` | The NestJS backend | `127.0.0.1:3010` on the Pi |
| `tunnel` | `cloudflared` for the dedicated tunnel `aichat` | outbound to Cloudflare only |

Ports 5432, 6379 and 3000 on the Pi already belong to other services. That is why nothing here publishes them.

## Files on the Pi

```
~/aichat/
  compose.yml       copy of compose.yml in this folder
  cloudflared.yml   copy of cloudflared.yml in this folder
  init-env.sh       copy of init-env.sh in this folder
  .env              secrets, chmod 600, created once by init-env.sh, never committed
  tunnel.json       tunnel credentials, chmod 400, never committed
  backend/          backend source, built on the Pi
```

## First setup (already done on 2026-11-26)

1. Copy the backend source and these files to `~/aichat`. Run this from the repo root:
   ```bash
   git archive origin/main:backend | ssh dainn@<pi> 'mkdir -p ~/aichat/backend && tar -x -C ~/aichat/backend'
   ```
2. On the Pi, run `cd ~/aichat && sh init-env.sh`. It generates `DATABASE_PASSWORD` and `JWT_ACCESS_SECRET` on the
   Pi and leaves `LLM_API_KEY` empty.
3. Create the tunnel:
   ```bash
   cloudflared tunnel create aichat
   cloudflared tunnel route dns aichat chat-api.dainn.online
   ```
   Then copy `~/.cloudflared/<id>.json` to `~/aichat/tunnel.json` and run `chmod 400` on it.
4. Run `docker compose up -d --build`.

## Update to a new backend version

```bash
# from the repo root, on your machine
git archive origin/main:backend | ssh dainn@<pi> 'rm -rf ~/aichat/backend && mkdir ~/aichat/backend && tar -x -C ~/aichat/backend'
# on the Pi
cd ~/aichat && docker compose up -d --build   # rebuilds, runs migrations, then restarts api
docker image prune -f                          # the SD/SSD has little free space
```

A release that ships a one-off deploy step must run it after the update. For example, the opt-out erasure release
needs this:

```bash
docker compose run --rm --no-deps api node dist/scripts/purge-legacy-optout.js
```

## Set or change the LLM key

1. Edit `~/aichat/.env` and set `LLM_API_KEY`. Set `LLM_BASE_URL` too if you use a gateway.
2. Run `docker compose up -d api`. An empty key runs the keyless stub provider.

Do not regenerate `.env` after first setup. A new `DATABASE_PASSWORD` would no longer match the existing database.

## Check that it works

```bash
curl https://chat-api.dainn.online/health   # {"status":"ok",...}
curl https://chat-api.dainn.online/ready    # database "up"
docker compose ps                           # api/db/redis healthy, migrate exited (0)
docker compose logs -f api
```
