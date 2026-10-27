# Server

This is the official server for [MediaGarrd](https://github.com/MediaGarrd/MediaGarrd).

## Requirements
- A Linux host running Docker with Compose v2 (`docker compose ...`)
- The host paths of the services you want to back up

## Installation
```bash
git clone --depth 1 https://github.com/MediaGarrd/Server.git
cd Server
cp docker-compose.example.yml docker-compose.yml
docker compose up --build -d
```

The defaults in `docker-compose.yml` work as-is. To change them before the first start:
- **Port:** change both `SERVER_PORT` and the `ports` mapping (default `38471`).
- **Where archives are stored on the host:** change the left side of the `./data/server/backups` volume.
- **Where the database is stored on the host:** change the left side of the `./data/server/db` volume. This holds every setting and service, so keep it on persistent storage.

## First-time setup
Until setup is finished, the server prints a single-use setup PIN to its logs:
```bash
docker logs mediagarrd-server 2>&1 | grep "Setup PIN"
```
From any device on the network, open `http://<server-ip>:38471/config` and enter the PIN. A new PIN is logged each time one is used, after 5 wrong attempts, and on every restart.

On the setup page:
1. **Backup Settings:** the backup location (a path *inside* the container that must be on a mounted volume, default `/var/lib/mediagarrd/backups`), the hours between backups, and how many backups to keep.
2. **Services:** add each service with a name and the absolute **host** paths to back up. Any service can be backed up. A path can be a directory (everything inside it is included) or a single file (only that file is included).
3. **Finish Setup:** available once at least one service exists. The setup page is then disabled for good.

## Configuration after setup
Every setting is stored in the database and survives restarts and container rebuilds. After setup, change them through the API or the MediaGarrd Client:

| Setting | Endpoint |
|---------|----------|
| Read all settings | `GET /api/v1/config` |
| Backup location and/or backups to keep | `PUT /api/v1/config` with `{"backupLocation": "/path", "retentionCount": 10}` |
| Time between backups (ISO-8601) | `PUT /api/v1/schedule` with `{"backupInterval": "PT12H"}` |
| List / add services | `GET` / `POST /api/v1/services` with `{"name": "radarr", "paths": ["/srv/radarr/config"]}` |
| Update / remove a service | `PUT` / `DELETE /api/v1/services/{name}` |

Configuration changes are rejected with `409 Conflict` while a backup is running. A lower retention count takes effect on the next backup run.

`MEDIAGARRD_BACKUP_INTERVAL` and `MEDIAGARRD_RETENTION_COUNT` in `docker-compose.yml` only set the initial values. Once a value has been saved through setup or the API, the stored value is used.

The database is checked on startup and whenever its schema changes underneath the server. If it is deleted, has a table dropped, or is replaced by an older copy, it is rebuilt or upgraded automatically. A corrupt database is moved aside to `mediagarrd.db.corrupt-<timestamp>` and replaced with an empty one, after which settings and services must be configured again (restart the server to get a new setup PIN).

## Updating
```bash
git pull
docker compose up --build -d
```
Settings, services, and archives live in the mounted `./data/server` volumes and are kept.

## How backups work
On every backup run the server creates (but never starts) a small helper container per service with its paths mounted read-only, and streams them into the backup archive through the Docker API. The helper container is reused until the service's paths change.

### Archive layout
Every entry in a backup zip sits at its absolute host path without the leading `/`, with no per-service folder. A service backing up `/etc/nginx/sites-available/default` and `/srv/radarr/config` produces:
```
etc/nginx/sites-available/default
srv/radarr/config/
srv/radarr/config/config.xml
...
```
Each entry keeps its permissions, owner (uid/gid), and modification time. Symbolic links are stored as links, empty directories are kept, and hard links are stored as regular copies. Named pipes, sockets, and device files are skipped. If two services back up the same path it appears once. If a path fails to copy, none of it is included and the run is reported as partial.

### Restoring
Because the layout mirrors the filesystem, restoring is a single extraction onto `/`. Use `bsdtar` (libarchive, the `libarchive-tools` package on Debian/Ubuntu), which restores contents, permissions, owners, modification times, and symbolic links:
```bash
sudo bsdtar -xpf <archive>.zip -C /
```
Extract into an empty directory with `-C <dir>` to inspect a backup first.

Info-ZIP `unzip -o <archive>.zip -d /` restores contents, permissions, and symbolic links, but UnZip 6.00 (the version most distributions ship) does not restore owners, so files end up owned by whoever ran it. Tools that ignore Unix metadata, such as Python's `zipfile` or most GUI archive managers, restore only the file contents.

> **Security:** the server needs `/var/run/docker.sock`, which gives it root-equivalent access to the host. During setup the configuration API requires the PIN session, but once setup is finished the API is unauthenticated, so do not expose the server port to untrusted networks.

## License
[GPLv3](./LICENSE)
