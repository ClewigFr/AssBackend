# AssBackend — Anonymous Simple Stats API

The Ktor + Exposed server that receives anonymous page views from the [AssAndroid](../AssAndroid) library and serves them to the AssFrontEnd dashboard.

## Configuration

Everything is set through environment variables, which are read in `resources/application.conf`:

| Variable            | Required        | Default            | Purpose                                  |
|---------------------|-----------------|--------------------|------------------------------------------|
| `SESSION_SIGN_KEY`  | **yes, in prod**| random (dev only)  | Hex key used to sign session cookies     |
| `CORS_ORIGINS`      | yes, in prod    | `http://localhost:8080` | Comma-separated dashboard origins allowed to call the API with cookies, e.g. `https://ass-dashboard.example.com` |
| `REGISTRATION_OPEN` | no              | `true` in dev, `false` otherwise | Allow `POST /register`. Every account can read all stats, so keep it closed in production: set it to `true` only while you create accounts |
| `JDBC_DATABASE_URL` | yes, in prod    | in-memory H2       | Database URL (PostgreSQL in prod)        |
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
- **Registration is closed in production by default.** To create an account: set `REGISTRATION_OPEN=true`, register, then unset it and restart.
- Login takes the same time whether or not the email exists, so it doesn't reveal which accounts exist.
- `POST /views` accepts at most 5000 views per request. Bigger batches get `400`.

### Cookies across sites

The dashboard and the API run on different sites, so outside `dev` the session cookie is set with `SameSite=None; Secure`. That means the API must be served over HTTPS in production, and the dashboard's origin must be listed in `CORS_ORIGINS`. Requests from any other origin get `403`. The Android library sends no `Origin` header, so it isn't affected.

## Running in dev

Requirements: **JDK 11**. Gradle 6.3 doesn't run on JDK 17+. In dev, no environment variable is needed: the server uses an in-memory H2 database and a random session key.

1. Start the server on port 8099. The dashboard's dev server already uses 8080, and the dashboard expects the API on 8099.
   ```bash
   ./gradlew run --args='-port=8099'
   ```
   With `./gradlew run`, the `PORT` variable is ignored (Gradle doesn't pass it to the server), so use `-port` as shown. `PORT` does work with the jar: `PORT=8099 java -jar build/libs/anonymoussimplestats-0.0.1-all.jar`.

   If your default JDK isn't 11, set `JAVA_HOME` first, for example:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 11)
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

Each test starts the real module against a fresh in-memory database. No server needs to be running.

## Production build

```bash
./gradlew shadowJar && java -jar build/libs/anonymoussimplestats-0.0.1-all.jar
```

On Heroku, the `Procfile` runs the shadow jar and `app.json` provisions PostgreSQL. See [Configuration](#configuration) for the variables that are required in production.

## API

| Method   | Path          | Auth  | Description |
|----------|---------------|-------|-------------|
| `POST`   | `/views`      | none  | Ingest page views: `{"pageViews":[{"page","sessionId","timestamp"}]}`, where `timestamp` is ms elapsed since the view. Returns `200` if all were stored, `206` if some had an unknown page, `400` if none were stored. |
| `GET`    | `/pages`      | none  | List pages |
| `POST`   | `/pages`      | login | Create a page `{"name": "..."}`; the caller becomes its owner |
| `DELETE` | `/pages`      | owner | Delete a page and its views `{"id": "...", "name": ""}` |
| `GET`    | `/stats`      | login | Plain-text summary |
| `GET`    | `/stats/json` | login | All views and pages as JSON |
| `POST`   | `/register`   | none  | Form fields: `email`, `password`, `displayName` |
| `POST`   | `/login`      | none  | Form fields: `userId` (email), `password` |
| `GET`    | `/logout`     | none  | Clear the session |

Passwords are hashed with bcrypt. Accounts created before bcrypt still have the old hashes, which are checked once more and upgraded to bcrypt the next time the user logs in.

## Hosting options considered

- Heroku: https://www.heroku.com/
- Scaleway: https://www.scaleway.com/fr/instances-virtuelles/development/
- Ikoula: https://www.ikoula.com/fr/cloud-public
