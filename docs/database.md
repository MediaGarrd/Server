# Recovering the database

Every backup archive contains a consistent copy of the server's own database: its settings, services, and backup records. The copy is taken while no other database operation runs, so it is never half written. It does not contain the record of the run that created it.

The database also holds the paired clients (name, creation and last-use time, and a SHA-256 hash of each token). Tokens themselves are never stored, so an archive cannot be used to sign in, but it does keep clients that were paired when it was taken. Restoring an old copy therefore brings back clients that were unpaired since, and their tokens work again. After a restore, check `GET /api/v1/clients` and unpair anything that should be gone, or pair again with `MEDIAGARRD_OPEN_PAIRING=true`.

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
2. Restore the archive into a new directory with [mgrecover](https://github.com/MediaGarrd/Recovery):
   ```bash
   curl -fLO https://github.com/MediaGarrd/Recovery/releases/latest/download/mgrecover.sh
   chmod +x mgrecover.sh
   sudo ./mgrecover.sh -C restore <archive>.zip
   ```
   `-C` restores the whole archive under `restore` (created if missing), so it can be large. It prints a plan and asks for confirmation (`-y` skips it and is required without a terminal). It creates no symbolic links and writes nothing outside that directory. With `-C`, files are restored without their original owners (with `sudo` they belong to root); step 3 copies the database back as root into the server's existing directory, which keeps its owner and mode.

   To extract only the database instead (this needs the directory to exist):
   ```bash
   mkdir restore
   sudo bsdtar -xpf <archive>.zip -C restore '<path from the listing above>'
   ```
3. Copy it over the current database in the mounted database directory (`./data/server/db` with the example compose file):
   ```bash
   sudo cp restore/<path from the listing above> ./data/server/db/mediagarrd.db
   sudo rm -f ./data/server/db/mediagarrd.db-journal ./data/server/db/mediagarrd.db-wal ./data/server/db/mediagarrd.db-shm
   ```
4. Start the server again:
   ```bash
   docker compose start
   ```

mgrecover needs `bsdtar` (the `libarchive-tools` package on Debian and Ubuntu). A full restore with `sudo ./mgrecover.sh <archive>.zip` puts back every file in the archive, not only the database, and writes the database to its host path. That is only safe while the server is stopped; remove the `-journal`, `-wal` and `-shm` files as in step 3 before starting the server.

## If the database is damaged
The server checks its database on startup. A missing or outdated database is rebuilt or upgraded automatically. A corrupt one is moved aside to `mediagarrd.db.corrupt-<timestamp>` and replaced with an empty database, so the server carries on with no settings, services or paired clients. This also happens while the server is running: the server then behaves as it does on a first start, without a restart. Setup opens again, a new `Setup PIN` is written to the log (look for the `The database was damaged` line), pairing and the web setup page at `/config` work as on a fresh install, and every Client that was paired gets `401` until it is paired again. The backup location, retention count and backup schedule are read again from the new database, so they go back to the defaults (or the `MEDIAGARRD_` environment values) until you set them again. Open event streams are closed, pairings that had not been confirmed are dropped, and a backup that is running at that moment is not stopped. Restore a copy from the newest archive as above to get your settings back (stop the server first), or use the new Setup PIN to configure the server again.
