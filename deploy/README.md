# Hosting on a Raspberry Pi 5

The whole service on one Raspberry Pi (or any arm64/amd64 Linux box) with Docker Compose:

| Service | What it does | Public address |
|---|---|---|
| `postgres` | PostgreSQL 18, the data | none |
| `backend` | the API (image `ghcr.io/clewigfr/assbackend`) | `https://ass-api.clewig.fr` |
| `dashboard` | the dashboard, served by nginx (image `ghcr.io/clewigfr/assfrontend`) | `https://ass.clewig.fr` |
| `cloudflared` | Cloudflare Tunnel: HTTPS, no port opened on the router, home IP hidden | |
| `backup` | nightly encrypted dump to Backblaze B2 | |

The images are built by the CI of each repository on every push to `master` and published to GitHub Packages.

The Android library is configured with `AssConfig(endpoint = "https://ass-api.clewig.fr", appId = …)`.

## 1. Prepare the Pi

1. Flash **Raspberry Pi OS Lite (64-bit)** with Raspberry Pi Imager. In its settings, set a user, an SSH key and the Wi-Fi (Ethernet is better for a server).
2. Use a good **A2 card of 32 GB or more** (SanDisk Extreme / Samsung Pro). A cheap card is what dies first.
3. Log in over SSH, then update the system and install Docker:
   ```bash
   sudo apt update && sudo apt full-upgrade -y
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker $USER   # log out and back in afterwards
   ```
4. Reduce writes to the SD card:
   - Logs in RAM: in `/etc/systemd/journald.conf`, set `Storage=volatile` and `RuntimeMaxUse=50M`, then `sudo systemctl restart systemd-journald`.
   - Swap: recent Raspberry Pi OS swaps to compressed RAM (zram) already. Check with `swapon --show`: you should see `/dev/zram0` and no `/var/swap` file.

## 2. Put clewig.fr on Cloudflare (once)

1. Create a free account on https://dash.cloudflare.com and **add the domain `clewig.fr`** (Free plan).
2. Cloudflare imports the existing DNS records. **Check that all of them are there** (website, `MX` mail records, `TXT`) before going further.
3. At the registrar of `clewig.fr`, replace the name servers with the two Cloudflare gives you. It takes from a few minutes to a few hours; Cloudflare emails you when the domain is active.

## 3. Create the tunnel

1. In Cloudflare, open **Zero Trust > Networks > Tunnels > Create a tunnel**, type **Cloudflared**, name it `ass-pi`.
2. On the install page, choose Docker and **copy only the token** (the long string after `--token`). It goes in `.env` as `TUNNEL_TOKEN`.
3. Add two **public hostnames**:

   | Subdomain | Domain | Service |
   |---|---|---|
   | `ass` | `clewig.fr` | `HTTP` `dashboard:80` |
   | `ass-api` | `clewig.fr` | `HTTP` `backend:8080` |

   Cloudflare creates the DNS records and the HTTPS certificates. `ass-api` rather than `api.ass` because the free certificate covers `*.clewig.fr` only, not `*.ass.clewig.fr`.

## 4. Start the stack

```bash
git clone https://github.com/ClewigFr/AssBackend.git
cd AssBackend/deploy
cp .env.example .env && chmod 600 .env
```

Fill in `.env`:
- `POSTGRES_PASSWORD`: `openssl rand -hex 24`
- `SESSION_SIGN_KEY`: `openssl rand -hex 32`
- `TUNNEL_TOKEN`: from step 3
- the backup settings, see [Nightly backups](#5-nightly-backups) (you can do that later: until then the backup service only logs an error each night)

If the GitHub packages are private, log in once with a token that has `read:packages`: `docker login ghcr.io -u ClewigFr`. Or make both packages public in GitHub, under Packages > Package settings.

```bash
docker compose up -d
docker compose ps        # everything "healthy" after about a minute
curl http://127.0.0.1:8099/health   # OK
```

Then open https://ass.clewig.fr.

**Create your account:** set `REGISTRATION_OPEN=true` in `.env`, run `docker compose up -d backend`, and register with:
```bash
curl -X POST https://ass-api.clewig.fr/register -d email=you@example.com -d password='…' -d displayName=You
```
Then set it back to `false` and run `docker compose up -d backend` again.

**Update** after a push to `master`: see [UPGRADING.md](UPGRADING.md) (backend, dashboard or both, database changes, rollback).

## 5. Nightly backups

Every night at 3:00 (`BACKUP_SCHEDULE`, `TZ`), the `backup` service:
1. dumps the database with `pg_dump` (compressed; the API keeps running);
2. **encrypts** the dump with [age](https://age-encryption.org). The database holds emails and password hashes, so nothing leaves the Pi unencrypted;
3. uploads it to **Backblaze B2** with rclone (the first 10 GB are free, no credit card);
4. keeps **7 daily, 4 weekly and 6 monthly** copies and deletes the older ones;
5. pings **healthchecks.io**, which emails you when a backup fails or doesn't run.

Setup, once:

1. **Encryption key.** Generate a key pair:
   ```bash
   docker compose run --rm backup age-keygen
   ```
   - The `public key: age1…` line goes in `.env` as `AGE_RECIPIENT`.
   - The whole output (with `AGE-SECRET-KEY-…`) is the **private key**. Store it in your password manager, and **not only on the Pi**: without it the backups can't be read, and if the Pi dies it dies with them.
2. **Backblaze B2.** Create an account on https://www.backblaze.com/cloud-storage, then:
   - **Buckets > Create a Bucket**, private, for example `ass-backup-clewig` (names are global), into `B2_BUCKET`;
   - **Application Keys > Add a New Application Key**, with access to this bucket only, read and write: `keyID` into `B2_KEY_ID`, `applicationKey` into `B2_APP_KEY`.
3. **Alerts (optional).** On https://healthchecks.io, create a free check with a 1 day period and a few hours of grace, and put its ping URL in `HEALTHCHECK_URL`.
4. Apply and run a first backup now:
   ```bash
   docker compose up -d backup
   docker compose run --rm backup backup.sh
   docker compose run --rm backup restore.sh --list
   ```

Size: about tens of MB per million views, so 10 GB lasts a long time.

### Restore

Put the private key in a file on the Pi (`age-key.txt`, `chmod 600`), then:

```bash
docker compose stop backend
docker compose run --rm -v ./age-key.txt:/key.txt:ro backup restore.sh            # newest daily backup
docker compose run --rm -v ./age-key.txt:/key.txt:ro backup restore.sh weekly/ass-2026-11-22-0300.dump.age
docker compose start backend
rm age-key.txt
```

The restore replaces the current tables. **Try it once** after the first backup, so you know it works before you need it.

## 6. Moving to an SSD later

The Pi 5 takes an NVMe SSD through an M.2 HAT. The data is only in `DATA_DIR`, so:

1. Mount the SSD, for example at `/mnt/ssd`.
2. Stop the stack, copy the data and point `.env` at it:
   ```bash
   docker compose down
   sudo rsync -a ./data/ /mnt/ssd/ass/
   ```
   In `.env`: `DATA_DIR=/mnt/ssd/ass`.
3. `docker compose up -d`. Once everything works, delete `./data`.

With an SSD you can also remove `synchronous_commit=off` from `docker-compose.yml` (no more SD card wear to save).

You can also restore a backup onto a new Pi instead of copying: start the stack on the empty database, then follow [Restore](#restore).

## Memory

Limits set in `docker-compose.yml` (`mem_limit`): PostgreSQL 1 GB, backend 768 MB, dashboard 64 MB, tunnel 128 MB, backup 256 MB. That's about 2.2 GB at most, so it fits on a 2 GB Pi and leaves plenty on the 8 GB one.

## Useful commands

```bash
docker compose logs -f backend      # API logs
docker compose logs backup          # last backups
docker compose exec postgres psql -U ass ass
```
