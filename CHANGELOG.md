# Changelog

## Unreleased

### Added
- Sharing applications with other accounts, by email, with a role: `read` (pages and stats) or `manage` (also pages and view deletion). Deleting the application and sharing it stay with the owner.
- `GET /apps/{app}/members`, `POST /apps/{app}/members` (`{"email","role"}`, 404 when no account has this email) and `DELETE /apps/{app}/members/{userId}` (also `/members/me`, for a member to leave).
- `GET /apps` also lists the applications shared with the account, with its `role` and the owner's email (`owner`).

### Changed
- An application with too low a role answers `403`; without any access it still answers `404`.
- Shares disappear with the application or either account. Quotas still count the owner's applications only.
- New `app_members` table, created automatically on start.
