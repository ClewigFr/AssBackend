# AssBackend — Anonymous Simple Stats API

The Ktor 3 + Exposed 1.x server that receives anonymous page views from the [AssAndroid](../AssAndroid) library and serves them to the AssFrontEnd dashboard.

## Configuration

Everything is set through environment variables, which are read in `resources/application.conf`:

| Variable            | Required        | Default            | Purpose                                  |
|---------------------|-----------------|--------------------|------------------------------------------|
| `SESSION_SIGN_KEY`  | **yes, in prod**| random (dev only)  | Hex key used to sign session cookies     |
| `CORS_ORIGINS`      | yes, in prod    | `http://localhost:8080` | Comma-separated dashboard origins allowed to call the API with cookies, e.g. `https://ass-dashboard.example.com` |
| `REGISTRATION_OPEN` | no              | `true` in dev, `false` otherwise | Allow `POST /register`. Each account only sees its own applications; leave it closed if strangers shouldn't use your server |
| `JDBC_DATABASE_URL` | yes, in prod    | in-memory H2       | Database URL (PostgreSQL in prod)        |
| `DB_POOL_SIZE`      | no              | `10`               | Database connections (and threads running queries) |
| `FLUSH_INTERVAL_MS` | no              | `200`              | How often buffered page views are written to the database |
| `INGEST_CAPACITY`   | no              | `500000`           | Max page views waiting to be written; beyond that `/views` answers `503` |
| `VIEWS_RATE_LIMIT`  | no              | `600`              | Max `POST /views` per client IP per minute (`0` disables it) |
| `VIEWS_PER_MINUTE`  | no              | `20000`            | Max page views accepted per client IP per minute, answered `429` beyond (`0` disables it; keep it above 1000, the largest batch) |
| `AUTH_RATE_LIMIT`   | no              | `10`               | Max `POST /login` and `POST /register` per client IP per minute (`0` disables it) |
| `STATS_CACHE_MS`    | no              | `5000`             | How long stats results are cached (`0` disables the cache) |
| `RETENTION_DAYS`    | no              | `90`               | How long raw page views (with their session ids) are kept. Older ones become views per page and per day, see [Data retention](#data-retention). `0` keeps everything |
| `KTOR_ENV`          | no              | `dev`              | `dev` uses H2; any other value uses PostgreSQL |
| `PORT`              | no              | `8080`             | HTTP port                                |

### Session signing key

Session cookies are signed with HMAC so that a client can't forge a session for another user. Outside `dev`, **the server refuses to start if `SESSION_SIGN_KEY` is missing**.

1. Generate a random key of at least 32 bytes, written in hex:
   ```bash
   openssl rand -hex 32
   ```
2. Set it on the server. For example, on Heroku:
   ```bash
   heroku config:set SESSION_SIGN_KEY=<the generated key>
   ```
   Or set it locally for a production-like run:
   ```bash
   export SESSION_SIGN_KEY=<the generated key>
   ```

Things to know:
- **Keep the key secret** and never commit it. Store it the same way as your database password.
- **Changing the key logs every user out**, because existing cookies no longer verify. Changing it is also how you invalidate all sessions at once if the key leaks.
- In `dev`, if no key is set, a random one is generated on every start, so restarting the server logs everyone out.
- Use the same key on every instance when you run more than one dyno or container.

### Account security

- **Sessions expire after 7 days**, checked on the server. A stolen cookie stops working after that, even though it's signed.
- **Brute-force protection:** after 5 wrong passwords, an account refuses logins for 15 minutes (`429`). The counter is kept in memory, per server instance.
- **Registration is closed in production by default.** To create accounts, set `REGISTRATION_OPEN=true`. Each account only sees its own applications and stats.
- Login takes the same time whether or not the email exists, so it doesn't reveal which accounts exist.
- `POST /views` accepts at most 1000 views per request (the Android client's retry file cap) and 1 MB of JSON once decompressed. Bigger batches get `400`.
- `POST /views` bodies may be gzipped (`Content-Encoding: gzip`); every response advertises it with `Accept-Encoding: gzip`, and the Android library only compresses once it has seen that header. `429` and `503` carry `Retry-After`.

### Performance

The server is built to absorb large volumes of page views with little memory:

- **Asynchronous writes:** `POST /views` checks the views, puts them in an in-memory buffer and answers immediately. A background task writes the buffer in batches (one `INSERT` for up to 5000 views) every `FLUSH_INTERVAL_MS`.
  - **Trade-off:** if the process is killed abruptly (crash, `kill -9`), views not written yet are lost, at most `FLUSH_INTERVAL_MS` worth (200 ms by default). On a normal shutdown (`SIGTERM`, which is what Heroku sends), the buffer is written before the server stops.
  - If the buffer is full (`INGEST_CAPACITY`), `/views` answers `503`, and the Android client retries later.
- **Page ids are kept in memory**, so validating a view never queries the database.
- **Connection pool** (HikariCP), with queries run on a dedicated thread pool of the same size, so a slow database never blocks request handling.
- **Stats are computed in SQL** (counts per page and per day) and **cached** for `STATS_CACHE_MS`. The response size depends on the number of pages and days, not on the number of views.
- **gzip** for responses over 1 KB, **per-IP rate limits** on `/views` (requests and views per minute, at most 1000 views per request), `/login` and `/register`, and logs written asynchronously at `INFO` level.
- **JVM** (`Procfile`): `-XX:+UseSerialGC -XX:MaxRAMPercentage=75 -Xss512k`, tuned for small containers.

Measured locally with ApacheBench (H2 in memory, so absolute numbers will differ on PostgreSQL):

| | Before | After |
|---|---|---|
| `POST /views` (20 views per request, 50 concurrent) | 1,117 req/s, p99 156 ms | **4,722 req/s** (≈94,000 views/s), p99 62 ms |
| Stats with ~140k views stored (one year, with comparison) | 16 req/s, 17.5 MB per response | **321 req/s, ~1 KB** (cached; 18 req/s when every request recomputes) |
| Server memory after the load | 5.3 GB | **335 MB** |

### Data retention

Raw page views, each with its random session id, are kept for `RETENTION_DAYS` (90 by default). After that, they're **rolled up into views per page and per day** (table `pagesdaily`), then deleted:

- A background task does it 1 minute after the server starts, then every 6 hours, one day per transaction. A day is moved atomically, so stats never count a view twice. A late upload of an old view is added to its day on the next run.
- Days are UTC days. In the stats, a daily count is shown on its date in the viewer's time zone.
- Stats keep counting the views of older periods (totals, day and month slots, per page). Sessions, pages per session, durations, bounce rate and hourly detail only exist for the last `RETENTION_DAYS` days. The stats response gives `detailFrom`, the instant before which only daily counts exist, and the dashboard explains it.
- Less personal data kept (no session trail after 90 days), and a database that stays small: one row per page and per day, whatever the traffic.
- Deleting a page or an application deletes its daily counts too.
- **The rollup can't be undone.** Raising `RETENTION_DAYS` later only keeps more detail from then on.

### Cookies across sites

The dashboard and the API run on different sites, so outside `dev` the session cookie is set with `SameSite=None; Secure`. That means the API must be served over HTTPS in production, and the dashboard's origin must be listed in `CORS_ORIGINS`. Requests from any other origin get `403`. The Android library sends no `Origin` header, so it isn't affected.

## Running in dev

Requirements: **JDK 17 or newer** (Kotlin 2.4, Gradle 9.9, Ktor 3.6). In dev, no environment variable is needed: the server uses an in-memory H2 database and a random session key.

1. Start the server on port 8099. The dashboard's dev server already uses 8080, and the dashboard expects the API on 8099.
   ```bash
   ./gradlew run --args='-port=8099'
   ```
   With `./gradlew run`, the `PORT` variable is ignored (Gradle doesn't pass it to the server), so use `-port` as shown. `PORT` does work with the jar: `PORT=8099 java -jar build/libs/anonymoussimplestats-0.0.1-all.jar`.

   If your default JDK is older than 17, set `JAVA_HOME` first, for example:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```
2. In another terminal, add test data: one account, three pages and views spread over three days. The login and password are at the top of the script.
   ```bash
   scripts/seed-dev.sh
   ```
3. Start the dashboard (AssFrontEnd) with `npm run dev`, open http://localhost:8080 and log in with the seeded account.

Things to know:
- The H2 database lives in memory, so **every restart wipes the data and logs everyone out**. Run `scripts/seed-dev.sh` again after each restart.
- `http://localhost:8080` is already in the default `CORS_ORIGINS`. If you serve the dashboard elsewhere, set `CORS_ORIGINS`.
- To point the Android sample app at your machine, use `AssConfig(endpoint = "http://10.0.2.2:8099")` from the emulator. Android blocks plain HTTP by default, so you'll need to allow cleartext traffic for that host.

### Tests

```bash
./gradlew test
```

Each test starts the real module against a fresh in-memory H2 database. `PostgresTest` runs the main flows and the migration from the legacy schema (`testresources/legacy-schema.sql`) on a real PostgreSQL 18, using embedded binaries downloaded by Gradle. No server or Docker is needed.

## Deploying an upgrade

Validated on PostgreSQL 18 with a database in the pre-upgrade state (created by commit `7886c78`): tables and data are kept, and the new columns, indexes and applications are added on the first start.

1. **Back up the database.** For example: `heroku pg:backups:capture`, or `pg_dump`.
2. **Set the configuration:** `SESSION_SIGN_KEY` (required, `openssl rand -hex 32`), `CORS_ORIGINS` (the dashboard's URL), and `JDBC_DATABASE_URL`. `REGISTRATION_OPEN=true` only if sign-ups should be open.
3. **Deploy the backend and the dashboard together.** Their API changed in step.
4. **Check the first start's logs.** On a database from before applications, expect:
   ```
   DataMigrations - Widening pagesviews.id to BIGINT (one-time table rewrite)
   DataMigrations - Moved N pages into a default application
   DataMigrations - Linked N page views to their application
   ```
   The `BIGINT` change rewrites the views table once, so on a large table the first start takes longer (the table is locked meanwhile). Later starts log none of these lines.
5. **Smoke test:** log in to the dashboard. Each existing user finds their old pages in an "Application par défaut", with their stats. Everyone has to log in again once, because the session cookie format changed.

What doesn't migrate:
- Pages created before page ownership existed (`owner` is null) are kept but belong to no application, so they're visible to nobody. Their views are kept too.
- Automatic migrations never `DROP` anything. The legacy `pages.owner` column is kept.

## Production build

```bash
./gradlew shadowJar && java -jar build/libs/anonymoussimplestats-0.0.1-all.jar
```

On Heroku, the `Procfile` runs the shadow jar and `app.json` provisions PostgreSQL. See [Configuration](#configuration) for the variables that are required in production.

## API

| Method   | Path          | Auth  | Description |
|----------|---------------|-------|-------------|
| `POST`   | `/views`      | none  | Ingest page views: `{"app","pageViews":[{"page","sessionId","timestamp"}]}`, where `timestamp` is ms elapsed since the view. Returns `200` if all were accepted, `206` if some were rejected, `400` if none were, `429` if rate limited, `503` if the write buffer is full. |
| `GET`    | `/apps`       | login | Your applications: `{"apps":[{"id","name","pages"}]}` |
| `POST`   | `/apps`       | login | Create an application `{"name"}` → `201 {"id","name"}`. The id is the UUID to configure the Android library with |
| `GET`    | `/apps/{app}` | owner | The application and its pages: `{"app":{...},"pages":[{"id","name"}]}` |
| `DELETE` | `/apps/{app}` | owner | Delete the application, its pages and their views → `204` |
| `POST`   | `/apps/{app}/pages` | owner | Add a page `{"name"}` → `201 {"id","name"}`. The id is what the app passes to `logScreen` |
| `POST`   | `/apps/{app}/pages/batch` | owner | Add several pages `{"names":[...]}` (up to 1000) → `200 {"created":[{"id","name"}],"existing":[...]}`. Names already in the application aren't created again, so the same list can be imported twice |
| `DELETE` | `/apps/{app}/pages/{page}` | owner | Delete a page and its views → `204` |
| `GET`    | `/apps/{app}/stats` | owner | Stats for a period, see below |

Another user's application answers `404`, exactly like one that doesn't exist.

### Stats

`GET /apps/{app}/stats?period=day|week|month|year&offset=0&tz=<minutes east of UTC>`

| `period` | Range (in the viewer's time zone) | Slots |
|---|---|---|
| `day` | today | 24 hours |
| `week` (default) | the last 7 days, today included | 7 days |
| `month` | the last 30 days | 30 days |
| `year` | the last 12 calendar months, the current one included | 12 months |

`offset=-1` is the period before, `-2` the one before that, and so on (up to `-1000`).

The response has:
- `current` and `previous` (the period just before, of the same length): `views`, `sessions`, `pagesPerSession`, `avgSessionMs` (time between the first and last view of a session; a single-view session counts as 0), and `bounceRate` (share of sessions with a single view);
- `buckets`: `start` (epoch ms), `views` and `sessions` per slot. A session is counted in the slot where it started, so slots add up exactly;
- `detailFrom`: before this instant (epoch ms), only views per page and per day are kept, see [Data retention](#data-retention). Session figures only cover the part of the period after it. `null` when `RETENTION_DAYS=0`;
- `pages`: every page of the application with its `views` and the `sessions` that saw it, busiest first.

There is no "users" figure: the library only knows a random session id per app launch, and nothing identifies a person or a device.

### Applications

A user creates **applications** (name + generated UUID), and adds **pages** to each (name + generated UUID). The Android library is initialized with the application's UUID and calls `logScreen` with a page's UUID. `POST /views` then only accepts pages that belong to that application. Older library versions that don't send `"app"` still work: their views are attributed to the page's application.

When upgrading an existing database, pages created before applications are moved automatically, on startup, into an "Application par défaut" per owner, and existing views are linked to it.
| `POST`   | `/register`   | none  | Form fields: `email`, `password`, `displayName` |
| `POST`   | `/login`      | none  | Form fields: `userId` (email), `password` |
| `GET`    | `/logout`     | none  | Clear the session |

Passwords are hashed with bcrypt. Accounts created before bcrypt still have the old hashes, which are checked once more and upgraded to bcrypt the next time the user logs in.

## Self-hosting

[`deploy/`](deploy/README.md) runs everything (PostgreSQL, API, dashboard, Cloudflare Tunnel, nightly encrypted backups) on a Raspberry Pi 5 or any small Linux server with Docker Compose. `GET /health` answers `200` when the database is reachable, for container health checks.

## Hosting options considered

- Heroku: https://www.heroku.com/
- Scaleway: https://www.scaleway.com/fr/instances-virtuelles/development/
- Ikoula: https://www.ikoula.com/fr/cloud-public
