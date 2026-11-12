# Recovering the database

Every backup archive contains a consistent copy of the server's own database: its settings, services, and backup records. The copy is taken while no other database operation runs, so it is never half written. It does not contain the record of the run that created it.

## Where the copy is in the archive
The copy sits at the database file's absolute host path, without the leading `/`. With the example `docker-compose.yml`, which mounts `./data/server/db` at `/var/lib/mediagarrd/db`, that is something like:
```
home/you/Server/data/server/db/mediagarrd.db
```
To find it in an archive:
```bash
bsdtar -tf <archive>.zip | grep 'mediagarrd\.db$'
```
If the copy could not be taken, the archive has no database entry and the run was reported as partial with the error listed under `_mediagarrd-server`.

On Docker Desktop (macOS and Windows) the path is one inside Docker's VM, so it will not match your real host path. The file name is still `mediagarrd.db`.

## Restoring it
Never restore over the database of a running server.

1. Stop the server:
   ```bash
   docker compose stop
   ```
2. Extract the copy into an empty directory:
   ```bash
   mkdir restore
   bsdtar -xpPf <archive>.zip -C restore '<path from the listing above>'
   ```
3. Copy it over the current database in the mounted database directory (`./data/server/db` with the example compose file):
   ```bash
   cp restore/<path from the listing above> ./data/server/db/mediagarrd.db
   rm -f ./data/server/db/mediagarrd.db-journal ./data/server/db/mediagarrd.db-wal ./data/server/db/mediagarrd.db-shm
   ```
4. Start the server again:
   ```bash
   docker compose start
   ```

`bsdtar` is in the `libarchive-tools` package on Debian and Ubuntu. `-P` lets it extract through symbolic links stored in the archive. Extracting the whole archive with `-C /` also writes the database to its host path, which is only safe while the server is stopped.

## If the database is damaged
The server checks its database on startup. A missing or outdated database is rebuilt or upgraded automatically. A corrupt one is moved aside to `mediagarrd.db.corrupt-<timestamp>` and replaced with an empty database, so the server starts again with no settings or services. Restore a copy from the newest archive as above to get them back, or restart the server and use its new setup PIN to configure it again.
