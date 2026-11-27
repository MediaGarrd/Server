# Settings

Set these in `environment` in `docker-compose.yml`. Durations use ISO-8601, for example `PT12H`.

| Variable | Default | What it does |
|---|---|---|
| `SERVER_PORT` | `8080` (`38471` in the example compose file) | The port of the API and setup page. Change the `ports` mapping to match. |
| `MEDIAGARRD_BACKUP_INTERVAL` | `PT12H` | The time between scheduled backups, from `PT15M` (15 minutes) to `P365D` (365 days). A value outside that range is ignored with a warning and `PT12H` is used, and a stored value outside it is ignored the same way. Only the initial value: once it is saved through setup, the API, or the client, the stored value is used. The next backup is due one interval after the last completed or partial backup (a failed run does not count), so restarting the server does not postpone it: an overdue backup runs within about 30 seconds of start. A server with no completed backup yet runs its first one soon after setup is finished, or one interval after start while setup is still open. Deleting the newest archive also removes its record, so after a restart or an interval change the schedule counts from the previous backup. |
| `MEDIAGARRD_RETENTION_COUNT` | `10` | How many backups to keep. Only the initial value, like the interval. |
| `MEDIAGARRD_BACKUP_ROOT` | `/var/lib/mediagarrd/backups` | Where archives are stored, inside the container. Only the initial value. It must be on a mounted volume. |
| `MEDIAGARRD_DATABASE_PATH` | `/var/lib/mediagarrd/db/mediagarrd.db` | The database file, inside the container. It must be on a mounted volume. |
| `MEDIAGARRD_COMPRESSION_LEVEL` | `1` | How hard files are compressed in archives, from `0` (store, no compression) to `9` (smallest, slowest). Files that are already compressed (zip, gz, tgz, bz2, xz, zst, 7z, rar, jpg, jpeg, png, gif, webp, mp4, mkv, mp3, flac, ogg, mov, avi, webm, m4v, m4a, aac, opus, heic, avif, lz4, zstd, jar, docx, xlsx, pptx) are always stored. The server refuses to start with a value outside 0 to 9. |
| `MEDIAGARRD_OPEN_PAIRING` | `false` | Set to `true` to open pairing on start and print a new `Pairing PIN` in the logs even when clients are already paired. A warning is logged on every start while it is set, so remove it once the new client is paired. |
| `MEDIAGARRD_HELPER_IMAGE` | `busybox:stable` | The image used for the helper containers that read each service's files. |
| `MEDIAGARRD_UPDATE_REPOSITORY` | `MediaGarrd/Server` | The GitHub repository checked for new releases. |
| `MEDIAGARRD_GITHUB_API_URL` | `https://api.github.com` | The GitHub API used for the release check. |
| `MEDIAGARRD_UPDATE_CHECK_INTERVAL` | `PT24H` | How often the release check runs. Minimum `PT1H`. |

## Failure webhook
An optional URL that gets a message when a backup run ends `failed` or `partial`, so a failed scheduled backup (which only retries after the next interval) does not go unnoticed. Set it on the setup page, through `PUT /api/v1/notifications/webhook` (see [API.md](./API.md#failure-webhook)), or from the Client dashboard. It is stored in the settings table; empty means disabled. Use the **Send Test** button or `POST /api/v1/notifications/webhook/test` to check it.

When it is sent:
- `backup.failed`: every service failed (no archive), a run ended with no service completed, or the run threw after it started (for example the archive could not be written; the message is the exception text).
- `backup.partial`: some services, or only the database snapshot, failed. The archive was created.
- Not sent on a successful run, on a run that is rejected (one already running, no services configured), or on an error before the run starts (for example the service list could not be read).
- `test`: only from the test endpoint.

The message is sent once, in the background, so it never slows or fails the backup: one attempt, 5 seconds to connect, 10 seconds in total, redirects are not followed, and a failed delivery is only logged as a warning with the host name (never the path or query). At most 16 messages wait in the queue; more are dropped with a warning. Clearing the webhook stops messages not yet sent.

The body depends on the host, because Discord and Slack do not document how they treat unknown fields:
- `discord.com`, `discordapp.com` and their subdomains: `{"content": "...", "allowed_mentions": {"parse": []}}`. The reason is placed in an inline code span, and `allowed_mentions` stops `@everyone`, `@here` and role or user pings.
- `hooks.slack.com`, and a Discord URL whose path ends in `/slack` (Discord's Slack-compatible endpoint): `{"text": "..."}` with `&`, `<` and `>` escaped. Discord's `/github` endpoint expects GitHub's payloads and is not supported; use the plain webhook URL.
- Any other host (n8n, Home Assistant, ntfy and so on): `{"event", "time", "error", "failedServices", "archiveId", "content", "text"}`. `event` is `backup.failed`, `backup.partial` or `test`, `time` is ISO-8601 UTC (the run's end time, or the moment of the event for an error or a test), `error` is the run's failure reason (one line per service joined with `; `, at most 1000 characters, `null` for a test), `failedServices` lists the service names (empty when not applicable), `archiveId` is present whenever the run created an archive (a partial run, or a failed one that still produced an archive) and omitted otherwise, and `content` and `text` hold the same short message. There is no server name field.

Every message is at most 1900 characters, has line breaks removed, and has `@` and `<` followed by a zero-width space so no mention or link markup is active.

Security notes for the webhook:
- **The URL is a secret.** It is never returned by the API (only `scheme://host`), never logged and never part of an error message. It is stored in plain text in the settings database, which is included in the database snapshot inside every backup archive. If an archive leaks, rotate the webhook.
- **Private addresses are allowed on purpose.** Only the owner (the setup session or a paired Client) can set the URL, and people run ntfy or Home Assistant on their LAN, so the server does not block loopback, private or link-local addresses. Redirects are not followed and the test reply carries only the status code, so the test cannot be used to read a response from an internal service.
- **Error text goes to the receiver.** Service names and failure reasons (paths, Docker messages) are sent to whatever you configure.
- The test is limited to one every 10 seconds.
- Host names with an underscore are refused because the JDK HTTP client cannot reach them either.

## Pairing
Once setup is finished the API needs a token, which a client gets by pairing with a PIN (see [API.md](./API.md#pairing)).
- A new install prints a `Setup PIN` for the `/config` page. When setup is finished, a fresh `Pairing PIN` is shown on the setup page (with a copy button, once) and printed in the logs. It works until one client has paired: the pairing is confirmed when the Client first uses its token, which the Client does straight away.
- An install that finished setup before pairing existed, or that has no paired client, prints a `Pairing PIN` on every start until a client pairs.
- To pair another client, either create a pairing code from a paired client (valid for 10 minutes, one use), or restart the server with `MEDIAGARRD_OPEN_PAIRING=true` and read the new PIN from the logs: `docker logs mediagarrd-server 2>&1 | grep "Pairing PIN"`. Remove the variable afterwards.
- Five wrong PINs lock PIN entry for 10 minutes. Restarting the server clears the lock.
- A paired client can be unpaired from the API (`DELETE /api/v1/clients/{id}`) or the Client. When the last client is unpaired, pairing reopens and a fresh `Pairing PIN` is logged.

Serve the API over HTTPS when it is reachable beyond a network you trust, because the token and the PIN travel in plain text over HTTP. See [https.md](./https.md).

## Security notes
- **A paired Client is the owner.** Once paired, a Client can add services with any absolute host path (except `/`), which the server bind-mounts read-only into a helper container and archives, and it can set the backup location to any path the server can write. Treat a pairing PIN or code like a root credential for the host, and unpair Clients you no longer use.
- **The server runs as root** and mounts `/var/run/docker.sock`, which is root-equivalent on the host. This is by design and is not changed by the app.
- **Permissions.** Directories the server creates (the backup folder, `.mediagarrd-runs/`, the data folder) are `0700`, and archives and the database file are `0600`, where the file system supports POSIX permissions. Existing directories you chose are never changed, and archives made by earlier versions keep their mode. Because the files belong to root, read or restore them with `sudo` (for example `sudo mgrecover ...` or `sudo bsdtar -xf <archive>`).
- **Retention only deletes archives the server recorded.** An old archive with no database record (left from before an upgrade, or after a database was moved aside as corrupt) is kept and still listed. The same applies to an archive whose record could not be written (the server logs "this archive will not be pruned"); such archives are never deleted automatically. Delete it by hand or with `DELETE /api/v1/backups/{id}`.
- **SQLite side files.** The database file is `0600`, but its `-journal` and `-wal` files follow the process umask, and a data folder that already existed keeps its mode. They hold token hashes and settings only; restrict the data folder itself (`chmod 700`) if others share the host.
- **PINs are logged** in plain text by design, so protect the container logs. The Pairing PIN is also returned once in the reply to Finish Setup.
- **The PIN stays usable until a Client confirms.** After a correct PIN it keeps working; an unconfirmed pairing is dropped after 5 minutes. Several Clients may pair meanwhile and only the first to confirm is kept. The Client stores its token before confirming, so a confirmation whose replies were lost is completed by its next request and never leaves the Client unpaired; if the pending pairing expired first, the Client is asked to pair again and the PIN is still open. A correct PIN does not reset the wrong-attempt counter until pairing closes. A lockout that rotates the PIN does not cancel a pairing already started with the old one.
- **Lockouts are global.** Anyone who can reach the port can lock PIN entry for 10 minutes by sending wrong PINs, and the PIN is replaced when that happens. This is an accepted trade-off; restart the server or wait to recover.
- **Unpairing the last Client reopens pairing** and prints a fresh `Pairing PIN` in the logs, so the server is never stranded.
- **Limits.** Request bodies over 64 KiB are refused with `413`. A service has at most 32 paths of at most 4096 characters, and a PIN or code is at most 64 characters.
- **Setup sessions** expire after 30 minutes without use, and the setup cookie is `Secure` when the request is HTTPS or carries `X-Forwarded-Proto: https`.
- **Pairing codes:** creating one when 10 are open drops the oldest, so a paired Client can invalidate another's code.

To change where data is kept on the host, change the left side of the volumes in `docker-compose.yml`:
- `./data/server/backups`: the archives.
- `./data/server/db`: the database, which holds every setting and service. Keep it on persistent storage.

The server also needs `/var/run/docker.sock` mounted to run its helper containers.

## Memory

The server idles at roughly 100 to 150 MB and uses a fixed amount of memory while backing up, however many files or however much data a backup holds. Archives are written to disk as a stream, so the only requirement is free disk space: about twice the size of the archive while a run is in progress (the per-path parts plus the merged archive).

The JVM is tuned in the `Dockerfile`: G1 with a small starting heap that shrinks back after a backup, native memory trimming, and `-XX:+ExitOnOutOfMemoryError` so the container restarts instead of hanging.

The heap cap defaults to half of the container's memory limit (`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50`). Without a memory limit on the container this is half of the host's RAM. To change it, set `JAVA_TOOL_OPTIONS` in `environment`, for example `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70` or `JAVA_TOOL_OPTIONS=-Xmx256m`. Setting it replaces the whole default value, and the JVM prints `Picked up JAVA_TOOL_OPTIONS` at startup when it is in effect.

The image is pinned to `eclipse-temurin:21.0.13_9-jre` because `-XX:TrimNativeHeapInterval` needs Java 21.0.3 or newer. To check the flags against another image, run `docker run --rm <image> java -XX:TrimNativeHeapInterval=10000 -XX:+UseG1GC -version`.
