# AssBackend — Anonymous Simple Stats API

The Ktor 3 + Exposed 1.x server that receives anonymous page views from the [AssAndroid](https://github.com/ClewigFr/AssAndroid) library and serves them to the [AssFrontEnd](https://github.com/ClewigFr/AssFrontEnd) dashboard.

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
| `AUTH_RATE_LIMIT`   | no              | `10`               | Max `POST /login`, `POST /register`, `POST /account/password`, `DELETE /account` and `POST /password/*` per client IP per minute (`0` disables it) |
| `VIEWS_PER_IP_APP_HOUR` | no          | `2000`             | Flood limit: views per client IP and application per hour; more are dropped silently. See [Flood protection](#flood-protection) |
| `VIEWS_PER_SESSION` | no              | `500`              | Flood limit: views per session id and day; more are dropped silently |
| `STATS_CACHE_MS`    | no              | `5000`             | How long stats results are cached (`0` disables the cache) |
| `RETENTION_DAYS`    | no              | `90`               | How long raw page views (with their session ids) are kept. Older ones become views per page and per day, see [Data retention](#data-retention). `0` keeps everything |
| `MAX_APPS`          | no              | `5`                | Applications per account; creating more answers `403`. `0`: no limit, like the two below |
| `MAX_PAGES_PER_APP` | no              | `30`               | Pages per application; adding more (one page or a CSV import) answers `403` |
| `MAX_MONTHLY_VIEWS` | no              | `100000`           | Views per account and calendar month (UTC). A **soft** limit: views keep being recorded, the dashboard shows an alert. See [Quotas](#quotas) |
| `SMTP_HOST`         | no              |                    | SMTP server for the password reset emails, with `SMTP_PORT` (`465`), `SMTP_TLS` (`ssl`, `starttls` or `off`), `SMTP_USER`, `SMTP_PASSWORD` and `MAIL_FROM` (default `SMTP_USER`). Without it, emails are logged in dev and not sent in production |
| `ACCOUNT_EMAILS`    | no              | `true`             | Emails to account owners: when the month's views reach 80 % and 100 % of the quota, and the monthly summary for those who opted in. Checked every hour, each sent once (`accountnotices` table) |
| `DASHBOARD_URL`     | no              | first `CORS_ORIGINS` | Base of the links in the emails (`<DASHBOARD_URL>/#/reset?token=…`, `/#/verify?token=…`) |
| `EMAIL_VERIFICATION` | no             | `true`             | New accounts can't sign in until they open the link emailed to them (valid 24 hours); unverified accounts are deleted after 24 hours. Needs `SMTP_HOST` |
| `SIGNUP_MIN_SECONDS` | no             | `3`                | `POST /register` needs a `formToken` from `GET /register/form`; a sign-up sent sooner after it is treated as a bot (`0` disables the form token) |
| `MAX_SIGNUPS_PER_HOUR` | no           | `30`               | Accounts created per hour, all clients together (`429` over it, `0`: no cap) |
| `TURNSTILE_SITE_KEY`, `TURNSTILE_SECRET` | no |              | Cloudflare Turnstile on sign-up, on when both are set |
| `LEGACY_VIEWS`      | no              | `false`            | `true` accepts `POST /views` bodies without `"app"`: library before 0.3.0, or the deprecated `getInstance(context, verbose)` (see [Applications](#applications)) |
| `KTOR_ENV`          | no              | `dev`              | `dev` uses H2; any other value uses PostgreSQL |
| `PORT`              | no              | `8080`             | HTTP port                                |

### Session signing key

Session cookies are signed with HMAC so that a client can't forge a session for another user. Outside `dev`, **the server refuses to start if `SESSION_SIGN_KEY` is missing**.

1. Generate a random key of at least 32 bytes, written in hex:
   ```bash
   openssl rand -hex 32
   ```
2. Set it on the server (on the Pi, in `deploy/.env`, see [deploy/README.md](deploy/README.md)).
   Or set it locally for a production-like run:
   ```bash
   export SESSION_SIGN_KEY=<the generated key>
   ```

Things to know:
- **Keep the key secret** and never commit it. Store it the same way as your database password.
- **Changing the key logs every user out**, because existing cookies no longer verify. Changing it is also how you invalidate all sessions at once if the key leaks.
- In `dev`, if no key is set, a random one is generated on every start, so restarting the server logs everyone out.
- Use the same key on every instance when you run more than one container.

### Account security

- **Sessions expire after 7 days**, checked on the server. A stolen cookie stops working after that, even though it's signed.
- **Brute-force protection:** after 5 wrong passwords, an account refuses logins for 15 minutes (`429`). The counter is kept in memory, per server instance.
- **Registration is closed in production by default.** To create accounts, set `REGISTRATION_OPEN=true`. Each account only sees its own applications and stats.
- **Sign-up bot protection:** a hidden honeypot field and a signed form token (too fast = bot; both get a fake `201` and nothing is created), an optional Turnstile CAPTCHA, an hourly cap on accounts, and email verification (with it on, `/register` doesn't reveal whether an address is registered).
- Per-IP limits key IPv6 clients by their `/64` prefix, since one subscriber usually gets a whole `/64`.
- Login takes the same time whether or not the email exists, so it doesn't reveal which accounts exist. So does `POST /password/forgot`: the email is sent in the background.
- Password reset links hold a random 256-bit token; only its SHA-256 is stored, so the database alone can't be used to reset a password.
- `POST /views` accepts at most 1000 views per request (the Android client's retry file cap) and 1 MB of JSON once decompressed. Bigger batches get `400`.
- `POST /views` bodies may be gzipped (`Content-Encoding: gzip`); every response advertises it with `Accept-Encoding: gzip`, and the Android library only compresses once it has seen that header. `429` and `503` carry `Retry-After`.

### Performance

The server is built to absorb large volumes of page views with little memory:

- **Asynchronous writes:** `POST /views` checks the views, puts them in an in-memory buffer and answers immediately. A background task writes the buffer in batches (one `INSERT` for up to 5000 views) every `FLUSH_INTERVAL_MS`.
  - **Trade-off:** if the process is killed abruptly (crash, `kill -9`), views not written yet are lost, at most `FLUSH_INTERVAL_MS` worth (200 ms by default). On a normal shutdown (`SIGTERM`, which is what `docker compose stop` sends), the buffer is written before the server stops.
  - If the buffer is full (`INGEST_CAPACITY`), `/views` answers `503`, and the Android client retries later.
- **Page ids are kept in memory**, so validating a view never queries the database.
- **Connection pool** (HikariCP), with queries run on a dedicated thread pool of the same size, so a slow database never blocks request handling.
- **Stats are computed in SQL** (counts per page and per day) and **cached** for `STATS_CACHE_MS`. The response size depends on the number of pages and days, not on the number of views.
- **gzip** for responses over 1 KB, **per-IP rate limits** on `/views` (requests and views per minute, at most 1000 views per request), `/login` and `/register`, request bodies capped at 1 MB (`413`), state-changing browser requests only from `CORS_ORIGINS` (CSRF), and logs written asynchronously at `INFO` level.
- **JVM** (`Dockerfile`): `-XX:+UseSerialGC -XX:MaxRAMPercentage=75 -Xss512k`, tuned for small containers.

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

### Quotas

Every account has the same limits (`MAX_APPS`, `MAX_PAGES_PER_APP`, `MAX_MONTHLY_VIEWS`):
- **5 applications, 30 pages per application:** hard limits. Creating one more answers `403` with a body starting with `quota:`. A CSV import that would go over is refused as a whole (names already in the application don't count). Existing applications and pages above a lowered limit are kept.
- **100,000 views per month:** a soft limit. `POST /views` never refuses views because of it (the Android library would retry them forever); `GET /account` reports the month's views and the dashboard shows an alert once they reach the limit. The month is the calendar month in UTC, all the account's applications together.

### Flood protection

The application and page UUIDs are in the APK, so anyone can send fake views: an anonymous service can't tell a script from a phone. The backend limits what one source can add:
- **per client IP and application:** `VIEWS_PER_IP_APP_HOUR` (2000) per hour. Mobile carriers put many users behind one IP, so it stays well above one person's use;
- **per session:** `VIEWS_PER_SESSION` (500) per session id and day. A session is one app launch.

Views over a limit are **dropped silently**, like invalid ones (`206`, or `400` when none is left), never `429`: the Android library would retry them forever, and a legitimate retry batch (up to 1000 views after an outage) must not get stuck. IPs and session ids are only counted in memory, never stored or logged. Dropped views are counted per application and hour in the `viewsdropped` table, which the monitoring reports and alerts on. After a flood that got through (many IPs), `DELETE /apps/{app}/views` removes the views of a period.

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

1. **Back up the database.** For example with `pg_dump`, or on the Pi `docker compose run --rm backup backup.sh`.
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

See [Configuration](#configuration) for the variables that are required in production.

## API

| Method   | Path          | Auth  | Description |
|----------|---------------|-------|-------------|
| `POST`   | `/views`      | none  | Ingest page views: `{"app","pageViews":[{"page","sessionId","timestamp"}]}`, where `timestamp` is ms elapsed since the view. Returns `200` if all were accepted, `206` if some were rejected, `400` if none were, `429` if rate limited, `503` if the write buffer is full. |
| `GET`    | `/apps`       | login | Your applications and the ones shared with you: `{"apps":[{"id","name","pages","role"}]}`, `role` being `owner`, `manage` or `read`; shared ones also have `owner` (the owner's email) |
| `POST`   | `/apps`       | login | Create an application `{"name"}` → `201 {"id","name"}`. The id is the UUID to configure the Android library with |
| `GET`    | `/apps/{app}` | read | The application and its pages: `{"app":{...},"pages":[{"id","name"}]}` |
| `DELETE` | `/apps/{app}` | owner | Delete the application, its pages and their views → `204` |
| `POST`   | `/apps/{app}/pages` | manage | Add a page `{"name"}` → `201 {"id","name"}`. The id is what the app passes to `logScreen` |
| `POST`   | `/apps/{app}/pages/batch` | manage | Add several pages `{"names":[...]}` (up to 1000) → `200 {"created":[{"id","name"}],"existing":[...]}`. Names already in the application aren't created again, so the same list can be imported twice |
| `DELETE` | `/apps/{app}/pages/{page}` | manage | Delete a page and its views → `204` |
| `GET`    | `/apps/{app}/stats` | read | Stats for a period, see below. `&page=<id>`: one page only (its views, and the sessions that saw it) |
| `DELETE` | `/apps/{app}/views?from=<ms>&to=<ms>` | manage | Delete the views received in `[from, to)`, and the daily counts of the days entirely inside it → `200 {"deleted"}`. To clean up after a flood |
| `GET`    | `/apps/{app}/members` | owner | The accounts the application is shared with: `{"members":[{"userId","email","role"}]}` |
| `POST`   | `/apps/{app}/members` | owner | Share with an existing account `{"email","role":"read"\|"manage"}`, or change its role → `200 {"members":[...]}`. `404` if no account has this email (no invitation is sent), `409` for the owner's own email |
| `DELETE` | `/apps/{app}/members/{userId}` | owner | Stop sharing → `204`. A member can also remove themself (leave the application), also as `/apps/{app}/members/me` |
| `PATCH`  | `/account`    | login | `{"monthlyReport": true\|false}` → `204`: opt in or out of the monthly summary email. `GET /account` returns it as `monthlyReport` |
| `GET`    | `/limits`     | none  | The quotas of every account and the retention: `{"maxApps","maxPagesPerApp","maxMonthlyViews","retentionDays"}` (`0`: no limit, or everything kept). Shown on the dashboard's presentation page |
| `GET`    | `/versions`   | none  | The latest release of each library, from GitHub every hour: `{"android","ios"}` (a field is missing until GitHub answered once). Shown in the presentation page's snippets. `VERSIONS_REFRESH=false` turns the calls to GitHub off |
| `GET`    | `/account`    | login | `{"email","displayName","quotas":{"maxApps","maxPagesPerApp","maxMonthlyViews"},"usage":{"apps","monthViews","monthStart"}}`. `monthViews` counts every application of the account since `monthStart` (the 1st of the month, UTC); a quota of `0` means no limit |
| `POST`   | `/account/password` | login | `{"current","new"}` → `204`, with a new session cookie: every other session (other devices) is signed out. `403` if `current` is wrong, `400` if `new` isn't 8 characters to 72 bytes |
| `POST`   | `/password/forgot` | none | `{"email"}` → `204` whether or not the account exists. If it does, emails a reset link valid 1 hour, usable once (at most 3 pending per account) |
| `POST`   | `/password/reset` | none | `{"token","new"}` → `204`: sets the password, signs out every session and cancels the other links. `400` if the link is unknown, used or expired, or the password isn't 8 characters to 72 bytes |
| `DELETE` | `/account`    | login | `{"password"}` → `204`: deletes the account, its applications, pages and views, its shares, and ends the session. `403` if the password is wrong |

An application can be shared with other accounts. `read` sees the pages and stats; `manage` also adds and deletes pages and deletes views; deleting the application and sharing it stay with the owner. Shares disappear with the application or either account. Quotas keep counting the owner's applications only: a shared application never counts for its members, and pages a manager adds count for the owner.

An application you have no access to answers `404`, exactly like one that doesn't exist; one where your role is too low answers `403`. Going over a [quota](#quotas) answers `403`.

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
- `previousBuckets`: the slots of the previous period, one per slot of `buckets`, to draw both on one chart;
- `pages`: every page of the application with its `views` and the `sessions` that saw it, busiest first.

There is no "users" figure: the library only knows a random session id per app launch, and nothing identifies a person or a device.

### Applications

A user creates **applications** (name + generated UUID), and adds **pages** to each (name + generated UUID). The Android library is initialized with the application's UUID and calls `logScreen` with a page's UUID. `POST /views` then only accepts pages that belong to that application. Bodies without `"app"` are refused with `400` (and the library drops them), unless `LEGACY_VIEWS=true`. Two kinds of apps don't send it: those on a library version before 0.3.0 (no `AssConfig`), and those still calling the deprecated `getInstance(context, verbose)`. Set it while such an app is in use; their views are then attributed to the page's application.

When upgrading an existing database, pages created before applications are moved automatically, on startup, into an "Application par défaut" per owner, and existing views are linked to it.
| `GET`    | `/register/form` | none | `{"formToken","turnstileSiteKey"?,"emailVerification"}`, to load with the sign-up form |
| `POST`   | `/register`   | none  | Form fields: `email` (case-insensitive, max 128), `password` (8 characters to 72 bytes), `displayName` (max 256), `formToken`, `cf-turnstile-response` (with Turnstile) and the empty honeypot `website`. `201` with a session, or `201 "Check your email"` without one when email verification is on (also for an address already registered). `400 "Form expired…"`: reload the form token |
| `POST`   | `/register/verify` | none | `{"token"}` from the verification email → `200 "Logged"` with a session; `400` if unknown or expired. `POST /login` on an unverified account answers `403 "Email not verified"` and sends a new link |
| `POST`   | `/login`      | none  | Form fields: `userId` (email), `password` |
| `POST`   | `/logout`     | none  | Clear the session and revoke every session of the user (all devices) |

Passwords are hashed with bcrypt. Accounts created before bcrypt still have the old hashes, which are checked once more and upgraded to bcrypt the next time the user logs in.

## Self-hosting

[`deploy/`](deploy/README.md) runs everything (PostgreSQL, API, dashboard, Cloudflare Tunnel, nightly encrypted backups) on a Raspberry Pi 5 or any small Linux server with Docker Compose. `GET /health` answers `200` when the database is reachable, for container health checks.

## Hosting options considered

- Scaleway: https://www.scaleway.com/fr/instances-virtuelles/development/
- Ikoula: https://www.ikoula.com/fr/cloud-public

## Contributing and license

Contributions are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md). Report security issues privately, see [SECURITY.md](SECURITY.md).

Released under the [MIT license](LICENSE). The project is part of Anonymous Simple Stats, with the [Android library](https://github.com/ClewigFr/AssAndroid) and the [dashboard](https://github.com/ClewigFr/AssFrontEnd).
