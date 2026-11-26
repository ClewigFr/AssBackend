# Changelog

## Unreleased

### Changed
- `GET /login` answers `200 "Not logged"` instead of `401` without a valid session: it's the dashboard's startup check, not an error (no 4xx on every visit). The dashboard only reads the body.

### Added
- `GET /apps/{app}/flows?period=&offset=&tz=` (role `read`): screen paths as aggregates only. Transitions from one screen to the next within a session (sessions, count, mean time on the first screen), entry and exit screens. A transition seen in fewer than 5 sessions isn't listed, only counted in `hiddenSessions`. Uses raw views, so the retention period only (`detailFrom`).
- `GET /versions`, public: the latest release of the Android and iOS libraries (`{"android","ios"}`), fetched from GitHub at start and every hour, the last known one kept when GitHub fails. `VERSIONS_REFRESH=false` turns it off.
- Sharing applications with other accounts, by email, with a role: `read` (pages and stats) or `manage` (also pages and view deletion). Deleting the application and sharing it stay with the owner.
- `GET /apps/{app}/members`, `POST /apps/{app}/members` (`{"email","role"}`, 404 when no account has this email) and `DELETE /apps/{app}/members/{userId}` (also `/members/me`, for a member to leave).
- `GET /apps` also lists the applications shared with the account, with its `role` and the owner's email (`owner`).

### Changed
- An application with too low a role answers `403`; without any access it still answers `404`.
- Shares disappear with the application or either account. Quotas still count the owner's applications only.
- New `app_members` table, created automatically on start.
