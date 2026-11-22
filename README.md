# AssBackend — Anonymous Simple Stats API

The Ktor + Exposed server that receives anonymous page views from the [AssAndroid](../AssAndroid) library and serves them to the AssFrontEnd dashboard.

## Configuration

Everything is set through environment variables, which are read in `resources/application.conf`:

| Variable            | Required        | Default            | Purpose                                  |
|---------------------|-----------------|--------------------|------------------------------------------|
| `SESSION_SIGN_KEY`  | **yes, in prod**| random (dev only)  | Hex key used to sign session cookies     |
| `CORS_ORIGINS`      | yes, in prod    | `http://localhost:8080` | Comma-separated dashboard origins allowed to call the API with cookies, e.g. `https://ass-dashboard.example.com` |
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

### Cookies across sites

The dashboard and the API run on different sites, so outside `dev` the session cookie is set with `SameSite=None; Secure`. That means the API must be served over HTTPS in production, and the dashboard's origin must be listed in `CORS_ORIGINS`. Requests from any other origin get `403`. The Android library sends no `Origin` header, so it isn't affected.

## Running

Building needs JDK 11 (Gradle 6.3).

```bash
./gradlew run
```

```bash
./gradlew shadowJar && java -jar build/libs/anonymoussimplestats-0.0.1-all.jar
```

On Heroku, the `Procfile` runs the shadow jar and `app.json` provisions PostgreSQL.

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
