# Settings

Set these in `environment` in `docker-compose.yml`. Durations use ISO-8601, for example `PT12H`.

| Variable | Default | What it does |
|---|---|---|
| `SERVER_PORT` | `8080` (`38471` in the example compose file) | The port of the API and setup page. Change the `ports` mapping to match. |
| `MEDIAGARRD_BACKUP_INTERVAL` | `PT12H` | The time between scheduled backups. Only the initial value: once it is saved through setup, the API, or the client, the stored value is used. |
| `MEDIAGARRD_RETENTION_COUNT` | `10` | How many backups to keep. Only the initial value, like the interval. |
| `MEDIAGARRD_BACKUP_ROOT` | `/var/lib/mediagarrd/backups` | Where archives are stored, inside the container. Only the initial value. It must be on a mounted volume. |
| `MEDIAGARRD_DATABASE_PATH` | `/var/lib/mediagarrd/db/mediagarrd.db` | The database file, inside the container. It must be on a mounted volume. |
| `MEDIAGARRD_COMPRESSION_LEVEL` | `1` | How hard files are compressed in archives, from `0` (store, no compression) to `9` (smallest, slowest). Files that are already compressed (zip, gz, tgz, bz2, xz, zst, 7z, rar, jpg, jpeg, png, gif, webp, mp4, mkv, mp3, flac, ogg, mov, avi, webm, m4v, m4a, aac, opus, heic, avif, lz4, zstd, jar, docx, xlsx, pptx) are always stored. The server refuses to start with a value outside 0 to 9. |
| `MEDIAGARRD_HELPER_IMAGE` | `busybox:stable` | The image used for the helper containers that read each service's files. |
| `MEDIAGARRD_UPDATE_REPOSITORY` | `MediaGarrd/Server` | The GitHub repository checked for new releases. |
| `MEDIAGARRD_GITHUB_API_URL` | `https://api.github.com` | The GitHub API used for the release check. |
| `MEDIAGARRD_UPDATE_CHECK_INTERVAL` | `PT24H` | How often the release check runs. Minimum `PT1H`. |

To change where data is kept on the host, change the left side of the volumes in `docker-compose.yml`:
- `./data/server/backups`: the archives.
- `./data/server/db`: the database, which holds every setting and service. Keep it on persistent storage.

The server also needs `/var/run/docker.sock` mounted to run its helper containers.

## Memory

The server idles at roughly 100 to 150 MB and uses a fixed amount of memory while backing up, however many files or however much data a backup holds. Archives are written to disk as a stream, so the only requirement is free disk space: about twice the size of the archive while a run is in progress (the per-path parts plus the merged archive).

The JVM is tuned in the `Dockerfile`: G1 with a small starting heap that shrinks back after a backup, native memory trimming, and `-XX:+ExitOnOutOfMemoryError` so the container restarts instead of hanging.

The heap cap defaults to half of the container's memory limit (`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50`). Without a memory limit on the container this is half of the host's RAM. To change it, set `JAVA_TOOL_OPTIONS` in `environment`, for example `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70` or `JAVA_TOOL_OPTIONS=-Xmx256m`. Setting it replaces the whole default value, and the JVM prints `Picked up JAVA_TOOL_OPTIONS` at startup when it is in effect.

The image is pinned to `eclipse-temurin:21.0.13_9-jre` because `-XX:TrimNativeHeapInterval` needs Java 21.0.3 or newer. To check the flags against another image, run `docker run --rm <image> java -XX:TrimNativeHeapInterval=10000 -XX:+UseG1GC -version`.
