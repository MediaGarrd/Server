# Server API

All endpoints are under `/api/v1`. Errors are returned as `{"error": "..."}` with a matching status code.

## Status
| Endpoint | Returns |
|---|---|
| `GET /health` | The server's health, whether a backup is running, the number of backups to keep, and the time between backups. |
| `GET /version` | The running version and the result of the daily check for a newer GitHub release. |
| `GET /events` | A Server-Sent Events stream that reports when backup runs start and finish and when archives or configuration change. |

## Setup
| Endpoint | Returns |
|---|---|
| `GET /setup` | Whether first-time setup is finished and whether the caller holds a setup session. |
| `POST /setup/session` | An empty `204` and a setup session cookie when the posted PIN is correct. |
| `POST /setup/complete` | An empty `204` once setup is finished, after which the setup page is disabled. |

The setup page itself is served at `GET /config` until setup is finished.

## Configuration
| Endpoint | Returns |
|---|---|
| `GET /config` | The backup location, the number of backups to keep, and the time between backups. |
| `PUT /config` | The updated configuration after changing the backup location and/or the number of backups to keep. |
| `PUT /schedule` | The server status after changing the time between backups. |
| `GET /services` | Every configured service with its host paths. |
| `POST /services` | The added service, with `201`. |
| `PUT /services/{name}` | The updated service. |
| `DELETE /services/{name}` | An empty `204` once the service is removed. |

## Backups
| Endpoint | Returns |
|---|---|
| `GET /backups` | Every archive with its id, file name, size, and creation time. |
| `GET /backups/latest` | The newest archive's details. |
| `GET /backups/{id}` | One archive's details. |
| `GET /backups/latest/download` | The newest archive as a zip file. |
| `GET /backups/{id}/download` | One archive as a zip file. |
| `DELETE /backups/{id}` | An empty `204` once the archive is deleted. |
| `POST /backups/run` | The run id, start and end time, services that ran, and the new archive, once the backup finishes. |
