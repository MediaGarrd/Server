# Settings

Set these in `environment` in `docker-compose.yml`. Durations use ISO-8601, for example `PT12H`.

| Variable | Default | What it does |
|---|---|---|
| `SERVER_PORT` | `8080` (`38471` in the example compose file) | The port of the API and setup page. Change the `ports` mapping to match. |
| `MEDIAGARRD_BACKUP_INTERVAL` | `PT12H` | The time between scheduled backups. Only the initial value: once it is saved through setup, the API, or the client, the stored value is used. |
| `MEDIAGARRD_RETENTION_COUNT` | `10` | How many backups to keep. Only the initial value, like the interval. |
| `MEDIAGARRD_BACKUP_ROOT` | `/var/lib/mediagarrd/backups` | Where archives are stored, inside the container. Only the initial value. It must be on a mounted volume. |
| `MEDIAGARRD_DATABASE_PATH` | `/var/lib/mediagarrd/db/mediagarrd.db` | The database file, inside the container. It must be on a mounted volume. |
| `MEDIAGARRD_HELPER_IMAGE` | `busybox:stable` | The image used for the helper containers that read each service's files. |
| `MEDIAGARRD_UPDATE_REPOSITORY` | `MediaGarrd/Server` | The GitHub repository checked for new releases. |
| `MEDIAGARRD_GITHUB_API_URL` | `https://api.github.com` | The GitHub API used for the release check. |
| `MEDIAGARRD_UPDATE_CHECK_INTERVAL` | `PT24H` | How often the release check runs. Minimum `PT1H`. |

To change where data is kept on the host, change the left side of the volumes in `docker-compose.yml`:
- `./data/server/backups`: the archives.
- `./data/server/db`: the database, which holds every setting and service. Keep it on persistent storage.

The server also needs `/var/run/docker.sock` mounted to run its helper containers.
