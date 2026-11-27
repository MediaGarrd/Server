# Server API

All endpoints are under `/api/v1`. Every error is returned as `{"error": "<message>"}` with a matching status code: a 4xx carries the reason, and a 5xx carries `{"error": "Internal error"}` (the detail is only in the server log), with one exception: a failed `POST /backups/run` answers 500 with the run's failure reason in `error`, because the Client dashboard shows it and a paired Client is the owner. This includes `404` and `405` from the framework (`{"error": "Not Found"}`, `{"error": "Method Not Allowed"}` with an `Allow` header) and `413` for a request body over 64 KiB. Missing backups answer `404` with `Backup not found` or `No backup archives found`.

Every response carries `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` and a `Content-Security-Policy` of `default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`, and everything except the setup page's `/css` and `/js` files is sent with `Cache-Control: no-store`. Request limits: 64 KiB per body, 32 paths of at most 4096 characters per service, a backup location of at most 4096 characters, and a PIN or pairing code of at most 64 characters (`400` otherwise).

## Authentication
After setup, every endpoint except the ones marked open needs the header `Authorization: Bearer <token>`. A client gets its token by pairing (see below). Before setup is finished the `401` message says setup is not finished. A missing, malformed, unknown or revoked token is answered with `401`, `WWW-Authenticate: Bearer` and `{"error": "..."}`. Tokens are never logged and are only ever sent in the reply to `POST /pair`.

Open endpoints (no token): `GET /health` (reduced, see below), `GET /version`, `GET /setup`, `POST /setup/session`, `POST /pair`. Everything else under `/api/v1`, including paths that do not exist, answers `401` without a token, except that a wrong method on an existing path answers `405` (it reveals only that the route exists).

Before setup is finished, the setup page's endpoints (`/config`, `/schedule`, `/services`, `/notifications/webhook`, `/setup/complete`) also accept the setup session cookie instead of a token. After setup is finished that cookie is ignored.

## Pairing
| Endpoint | Auth | Returns |
|---|---|---|
| `POST /pair` | open | `{"token": "...", "id": "...", "name": "..."}` when the body's `pin` is the current pairing PIN or a valid pairing code. The pairing is pending until the token is used (see below). |
| `POST /pairing-codes` | token | `{"code": "ABCDE-FGHJK", "expiresAt": "<ISO-8601>"}`, a single-use code for pairing another client. |
| `GET /clients` | token | Every paired client as `{"id", "name", "createdAt", "lastUsedAt", "current"}`; `current` is true for the caller. `lastUsedAt` is `null` until first use and is updated at most once a minute. |
| `DELETE /clients/{id}` | token | An empty `204` once that client is unpaired, or `404` for an unknown id. A client may unpair itself with its own id. |

`POST /pair` takes `{"pin": "<6-digit PIN or pairing code>", "name": "<optional label>"}`. The label is 1 to 64 characters (letters, digits, spaces and `. _ - ( ) ' @ +`); one is generated when it is left out. The reply carries the token once and the server keeps only its SHA-256 hash.

The PIN is the one printed in the logs as `Pairing PIN: NNNNNN`. The setup PIN only unlocks the setup page. When setup is finished a new PIN is printed as `Pairing PIN` and is also returned by `POST /setup/complete`, and it works until one client has paired. A server that finished setup earlier prints one on start while no client is paired. A pairing code is created by an already paired client, expires after 10 minutes and works once.

A successful `POST /pair` creates a pending pairing, kept in memory only. The first authenticated request with the new token (any endpoint, for example `GET /health` with the `Authorization` header) confirms it: only then is the client stored and listed, and only then does the PIN stop working or the pairing code get spent. Until then the same PIN or code can be used again, so a client whose reply was lost simply pairs again. A pending pairing that is not confirmed within 5 minutes is dropped, at most 10 are kept (the oldest is dropped first), and a restart drops them all. Pending pairings are not listed by `GET /clients`, do not count as a paired client, and `DELETE /clients/{id}` answers `404` for them. If several clients pair with the same PIN before any confirms, the first to confirm wins and the others' tokens are then answered with `401`; the same holds for a pairing code. A pairing made with a code is still confirmed if the code expired in the meantime, but not if the code was dropped because 10 newer codes were created. A PIN pairing confirmed after pairing was closed and reopened (the last client was unpaired) is refused. A code pairing that inserts the first confirmed client also closes PIN pairing.

Statuses of `POST /pair`:
| Status | Meaning |
|---|---|
| `200` | Pairing started. Use the token once to confirm it. |
| `400` | The body is missing, is not JSON, has no `pin`, or has an invalid `name`. |
| `401` | The PIN or code is wrong, expired or already used. |
| `403` | Pairing is closed: a client has already confirmed a pairing with the PIN. Use a pairing code, or restart the server with `MEDIAGARRD_OPEN_PAIRING=true`. |
| `409` | Setup is not finished. Finish it at `/config` first. |
| `429` | Locked after too many wrong attempts. `Retry-After` gives the seconds left. |

Five wrong PINs lock PIN entry (setup page and pairing share this counter) for 10 minutes. Ten wrong pairing codes lock code entry, separately, for 10 minutes. The locks are global, not per address, and a restart clears them. Opening or closing pairing never clears a lock. Anything that is not exactly six digits is treated as a pairing code.

Unpairing the last paired client reopens pairing: a fresh `Pairing PIN` is logged and `POST /pair` accepts it. This does not clear a lockout, which is independent of whether pairing is open.

Revoking a client takes effect on its next request and closes that client's open `/events` streams at once.

## Status
| Endpoint | Auth | Returns |
|---|---|---|
| `GET /health` | open, token optional | Without a token: `{"healthy", "backupInterval"}`. With a valid token: `{"healthy", "running", "backupRetentionCount", "backupInterval"}`. A token that is present but invalid answers `401`. |
| `GET /version` | open | The running version and the result of the daily check for a newer GitHub release. |
| `GET /events` | token | A Server-Sent Events stream that reports when backup runs start and finish and when archives or configuration change. |

## Setup
| Endpoint | Auth | Returns |
|---|---|---|
| `GET /setup` | open | Whether first-time setup is finished and whether the caller holds a setup session. |
| `POST /setup/session` | open | An empty `204` and a setup session cookie (`HttpOnly`, `SameSite=Strict`, `Secure` over HTTPS or with `X-Forwarded-Proto: https`; the session ends after 30 minutes without use) when the posted PIN is correct, `401` for a wrong PIN, `410` once setup is finished, `429` while locked. |
| `POST /setup/complete` | setup session | `200` and `{"pairingPin": "NNNNNN"}` once setup is finished, after which the setup page is disabled. A second finish answers `410`, and finishing with no service configured answers `409` with `Add at least one service before finishing setup`. `pairingPin` is `null` when no PIN applies, for example when a client is already paired. The same PIN is printed in the logs as `Pairing PIN`. The reply is the only place the PIN is returned and is sent with `Cache-Control: no-store`. |

The setup page itself is served at `GET /config` until setup is finished.

## Configuration
Setup session before setup is finished, token afterwards.

| Endpoint | Returns |
|---|---|
| `GET /config` | The backup location, the number of backups to keep, and the time between backups. |
| `PUT /config` | The updated configuration after changing the backup location and/or the number of backups to keep. |
| `PUT /schedule` | The server status after changing the time between backups. The interval is an ISO-8601 duration or a number of seconds and must be between 15 minutes and 365 days, both inclusive. A missing, `null`, zero, negative, shorter or longer interval answers `400` with `Backup interval must be between 15 minutes and 365 days` and nothing is saved. |
| `GET /services` | Every configured service with its host paths. |
| `POST /services` | The added service, with `201`. |
| `PUT /services/{name}` | The updated service. |
| `DELETE /services/{name}` | An empty `204` once the service is removed. |

Statuses:
| Status | Meaning |
|---|---|
| `409` | `PUT /config`, `POST /services`, `PUT /services/{name}` and `DELETE /services/{name}` answer `Configuration cannot be changed while a backup is running, try again once it finishes` while a backup run is going. `POST /services` also answers `409` with `Service already exists: <name>` when the name is taken, and so does `PUT /services/{name}` when it renames the service to a name that is taken (the comparison ignores case). |

## Failure webhook
Setup session before setup is finished, token afterwards. An optional webhook URL that receives a message when a backup run fails or only partly succeeds (scheduled and manual runs). The full URL is a secret (Discord and Slack webhook URLs carry a token), so no endpoint ever returns it: only whether one is set and its `scheme://host[:port]`.

| Endpoint | Returns |
|---|---|
| `GET /notifications/webhook` | `{"configured": true, "target": "https://discord.com"}`, or `{"configured": false, "target": null}`. |
| `PUT /notifications/webhook` | The same shape as `GET`, after saving. The body is `{"url": "https://..."}`; an empty string, `null` or a missing `url` clears the webhook. |
| `POST /notifications/webhook/test` | `{"delivered": true, "status": 200, "error": null}` after sending a `test` event to the saved URL and waiting up to 5 seconds. When it is not delivered, `delivered` is `false`, `status` is the receiver's HTTP status or `null`, and `error` is a short reason: `Webhook answered 404`, `Timed out`, `Could not connect` or `Delivery failed`. The reply never contains the URL or the receiver's body. |

Statuses:
| Status | Meaning |
|---|---|
| `200` | Saved, or the test was attempted (check `delivered`). |
| `400` | The URL is not valid (`Webhook URL is not a valid URL`, `must use http or https`, `must not contain user info`, `must not contain a fragment`, `must not contain spaces or control characters`, `must have a valid host name`, `is too long`), or a test was requested with no webhook configured (`No webhook is configured`). |
| `429` | A test was sent less than 10 seconds ago. The limit is global, counts attempts, and `Retry-After` gives the seconds left. |

A URL must be absolute `http` or `https`, at most 2048 characters, with a host name, without user info (`user:pass@`), a fragment (`#`), or whitespace. A host name with an underscore (for example `http://home_assistant:8123`) is refused: use the IP address or a DNS name. The three message shapes are described in [settings.md](./settings.md#failure-webhook).

## Backups
Token required.

| Endpoint | Returns |
|---|---|
| `GET /backups` | Every archive with its id, file name, size, and creation time, newest first. Both the order and the creation time come from the timestamp in the file name (`yyyyMMdd-HHmmss-SSS`, UTC); only an archive whose name has no valid timestamp uses the file's last-modified time. |
| `GET /backups/latest` | The newest archive's details. |
| `GET /backups/{id}` | One archive's details. |
| `GET /backups/latest/download` | The newest archive as a zip file. |
| `GET /backups/{id}/download` | One archive as a zip file. |
| `DELETE /backups/{id}` | An empty `204` once the archive is deleted. |
| `POST /backups/run` | The run id, start and end time, services that ran, and the new archive, once the backup finishes. If the run fails, `500` with the failure reason in `error` (the one 5xx that is not `Internal error`). `409` when a run is already in progress (`A backup run is already in progress`) or when no services are configured (`No services are configured for backup`). |
