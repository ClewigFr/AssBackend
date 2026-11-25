# Contributing

Thanks for your interest in Anonymous Simple Stats. Issues and pull requests are welcome.

## Ground rules

- **Anonymity is the product.** Never store or log anything that identifies a person or a device across sessions: no device or advertising IDs, no IP addresses at rest (they are only used in memory for rate limits), no fingerprinting.
- The wire format of `POST /views` is shared with the [Android library](https://github.com/ClewigFr/AssAndroid): a change there must stay compatible with released library versions.
- Keep the defaults safe for a public instance (registration closed, limits on).

## Development

Needs JDK 17; no database or Docker (tests use H2 and an embedded PostgreSQL).

```bash
./gradlew run --args='-port=8099'   # API with an in-memory H2 database, where the dashboard expects it
scripts/seed-dev.sh                 # a dev account with sample data
./gradlew test                      # all tests
```

See the [README](README.md) for the configuration and the API.

## Pull requests

- One topic per pull request, with tests for the behaviour you change.
- `./gradlew test` must pass; CI runs it on every pull request.
- Update the README when you add a setting or change the API.

By contributing, you agree that your contributions are released under the [MIT license](LICENSE).
