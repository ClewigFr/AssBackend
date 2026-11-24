# Updating the server

How to deploy a new version of the backend, the dashboard or both on the Pi, including when the database changes.

## How versions work

- Every push to `master` (once the tests pass) publishes two tags of each image:
  - `latest`, the most recent version;
  - the **commit hash** (for example `ghcr.io/clewigfr/assbackend:9f46549…`), which never changes.
- The Pi uses `latest` unless `.env` sets `BACKEND_IMAGE` or `DASHBOARD_IMAGE`. Rolling back means pinning a previous commit hash there.
- **Database changes are applied automatically when the backend starts.** It creates the missing tables, columns and indexes and runs its data migrations. Automatic migrations never `DROP` anything, so old data and columns are kept.

## Before any update

1. Check that the CI is **green** for the commit you want, under the repository's **Actions** tab, `image` job included.
2. Read the commits since the last update. Look for anything that changes the database (`src/dao/`, `src/db/DataMigrations.kt`) or the API between the backend and the dashboard.
3. On the Pi, note the running versions, in case you need to roll back:
   ```bash
   cd ~/AssBackend/deploy
   docker compose images backend dashboard
   docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' deploy-backend-1
   ```
   Same for `deploy-dashboard-1`. You can also find the hashes on GitHub, under **Packages > assbackend**.

## Dashboard only

No database change and nothing to back up: the dashboard is static files.

```bash
cd ~/AssBackend/deploy
docker compose pull dashboard
docker compose up -d dashboard
```

Reload https://ass.clewig.fr. Browsers pick up the new version at once (`index.html` is never cached).

## Backend only

1. **Back up first**, always, even when you don't expect a database change:
   ```bash
   docker compose run --rm backup backup.sh
   ```
   Check that it ends with `Backup OK`.
2. Update:
   ```bash
   docker compose pull backend
   docker compose up -d backend
   ```
   The API is unavailable for about 20 to 40 seconds while it restarts. The Android library keeps its views and resends them later, so nothing is lost. Views already received are written to the database before the old version stops.
3. Watch the start:
   ```bash
   docker compose logs -f backend
   ```
   Wait for `Responding at http://0.0.0.0:8080`. Then check:
   ```bash
   docker compose ps backend     # "healthy"
   curl http://127.0.0.1:8099/health
   ```
4. Log in to the dashboard and open an application's stats.

## Both

When a change touches the API in both repositories, update them together, **backend first**:

```bash
docker compose run --rm backup backup.sh
docker compose pull backend dashboard
docker compose up -d backend
docker compose logs -f backend      # wait for "Responding at", then Ctrl+C
docker compose up -d dashboard
```

Between the two steps, the old dashboard talks to the new backend for a minute. That's harmless. Reload the dashboard page once it's done.

## With a database change

Nothing extra to run: the migration happens in step 2 of "Backend only". But:

- **The backup before the update is essential.** It's your only way back if the migration goes wrong.
- **Large tables take longer.** A change that rewrites the views table (a new column type, a new index) can take minutes on millions of rows, and the API waits for it before answering. Do it at a quiet time, and don't stop the container while it runs.
- **Read the start logs.** Data migrations log what they do, for example:
  ```
  DataMigrations - Moved N pages into a default application
  ```
  An error here stops the backend, and the container keeps restarting. Roll back (below) and send me the log.
- **Rolling back after a database change.** The previous backend version usually still works, because migrations only add tables and columns. If it doesn't, restore the backup taken just before. Views received since that backup are lost.

### Versions with data retention (daily counts)

The first backend version with `RETENTION_DAYS` creates the `pagesdaily` table on start. One minute later, it **rolls up every raw view older than 90 days into daily counts and deletes them**. That can't be undone, so:
- back up first (as always), and keep that backup if you may want the old session detail one day;
- to keep everything for now, set `RETENTION_DAYS=0` in `.env` before updating, and choose later.

Expect `Rolled up N page views older than 90 days into daily counts` in the logs. Rolling back to an older backend afterwards works, but it ignores the daily counts: periods older than 90 days then look empty.

### Versions with quotas

The first backend version with quotas limits each account to 5 applications, 30 pages per application and 100,000 views per month (an alert only). Nothing existing is deleted: an account already above a limit keeps its applications and pages, it just can't add more. To change the limits, set `MAX_APPS`, `MAX_PAGES_PER_APP` or `MAX_MONTHLY_VIEWS` in `.env` (`0` = no limit), then bring the new `docker-compose.yml` to the Pi (see "Updating the deploy folder"): the old one doesn't pass these variables to the backend. Update the dashboard too: it has the **Mon compte** page and shows the limits.

### Adding the monitoring

The `monitor` service is new in `docker-compose.yml`. Bring the deploy folder to the Pi (see "Updating the deploy folder"), add the `SMTP_*` and `MAIL_TO` settings to `.env`, then follow "Monitoring" in [README.md](README.md#6-monitoring). Rebuild the backup image too (`docker compose build backup`): it now records the last successful backup for the report.

### Password reset

The first backend version with "Mot de passe oublié ?" sends the reset emails with the `SMTP_*` settings of the monitoring, and creates the `passwordresets` table on start. Bring the new `docker-compose.yml` to the Pi: the old one doesn't pass the `SMTP_*` settings to the backend. Without them, the link is never sent (the backend logs a warning). Update the backend first, then the dashboard.

### Flood protection

The first backend version with flood limits creates the `viewsdropped` table on start and drops views beyond 2000 per IP and application per hour, or 500 per session and day (`VIEWS_PER_IP_APP_HOUR`, `VIEWS_PER_SESSION`). Rebuild the monitor too (`docker compose up -d --build monitor`): it reports dropped views and alerts on spikes. Update the dashboard for "Supprimer des vues".

## Rolling back

1. Pin the previous version in `.env`:
   ```
   BACKEND_IMAGE=ghcr.io/clewigfr/assbackend:<previous commit hash>
   DASHBOARD_IMAGE=ghcr.io/clewigfr/assfrontend:<previous commit hash>
   ```
2. Restart:
   ```bash
   docker compose up -d backend dashboard
   ```
3. If the old backend refuses the new database, restore the backup taken before the update. See "Restore" in [README.md](README.md#restore).
4. Once fixed, remove these lines from `.env` to go back to `latest`.

## Other services

- **PostgreSQL**, `cloudflared` and the backup service follow their own images:
  ```bash
  docker compose pull postgres cloudflared && docker compose up -d
  docker compose build --pull backup && docker compose up -d backup
  ```
  The `postgres:18-alpine` tag only receives 18.x fixes.
  `cloudflared` is pinned to a version in `docker-compose.yml`, so `pull` doesn't change it: to update it, pick a release from [github.com/cloudflare/cloudflared/releases](https://github.com/cloudflare/cloudflared/releases), change the tag, then pull.
- **Changing the PostgreSQL major version** (18 → 19) is a different operation, because the data files aren't compatible:
  1. back up;
  2. `docker compose down`;
  3. move `data/postgres` aside;
  4. change the version in `docker-compose.yml` and in `backup/Dockerfile`;
  5. `docker compose up -d`, then restore the backup.
- **Changes to the `deploy/` folder itself** (`docker-compose.yml`, backup scripts): see the next section.

## Updating the deploy folder

The images come from GitHub, but `docker-compose.yml`, `.env.example` and the backup service (`backup/`, built on the Pi) come from this folder. When a commit changes them, bring them to the Pi.

**If the Pi has a clone of the repository** (deploy key):
```bash
cd ~/AssBackend && git pull
```

**Otherwise, copy the folder from the Mac**:
```bash
rsync -av --exclude .env --exclude data --exclude backups --exclude age-key.txt \
  ~/AndroidStudioProjects/AssBackend/deploy/ pi@raspberrypi.local:~/ass/
```
- Replace `pi@raspberrypi.local` with your user and the Pi's address, and `~/ass/` with the folder on the Pi.
- Keep the `/` at the end of both paths: it copies the folder's content into `~/ass/` rather than creating `~/ass/deploy/`.
- The excludes keep the Pi's own files: its `.env` with the secrets, the database (`data/`) and local backups. Don't use `--delete`, which would remove them.
- Without rsync, use `scp`. The Mac's folder has no `.env`, `data/` or backups, so nothing of the Pi's is overwritten:
  ```bash
  cd ~/AndroidStudioProjects/AssBackend/deploy
  scp -r docker-compose.yml .env.example backup README.md UPGRADING.md pi@raspberrypi.local:~/ass/
  ```

Then, on the Pi, apply the changes:
```bash
cd ~/ass        # or ~/AssBackend/deploy
docker compose build backup
docker compose up -d
```
Compose only recreates the services whose settings or image changed. Compare `.env.example` with your `.env` in case a new setting appeared:
```bash
diff <(grep -o '^[A-Z_]*=' .env.example | sort) <(grep -o '^[A-Z_]*=' .env | sort)
```

## Cleaning up

Old images pile up on the SD card. Once the new version works:
```bash
docker image prune -f
```
