# Server

The official server for [MediaGarrd](https://github.com/MediaGarrd/MediaGarrd). It backs up the configuration of the services on its host into a single zip archive on a schedule, and the [MediaGarrd Client](https://github.com/MediaGarrd/Client) manages it and downloads the archives to another machine.

Full documentation, including configuration, archives and restoring, is on the [MediaGarrd website](https://mediagarrd.github.io/MediaGarrd/).

## Docs
- [settings.md](./docs/settings.md): every environment variable and its default.
- [API.md](./docs/API.md): every HTTP endpoint and what it returns.
- [database.md](./docs/database.md): recovering the server's database from a backup.
- [https.md](./docs/https.md): serving the API over HTTPS behind nginx or Caddy.

## Requirements
- A Linux host running Docker with Compose v2

## Installation
```bash
git clone --depth 1 https://github.com/MediaGarrd/Server.git
cd Server
cp docker-compose.example.yml docker-compose.yml
docker compose up --build -d
```

Then get the single-use setup PIN from the logs:
```bash
docker logs mediagarrd-server 2>&1 | grep "Setup PIN"
```
Open `http://<server-ip>:38471/config`, enter the PIN, add the services to back up, and finish setup.

Then pair the [MediaGarrd Client](https://github.com/MediaGarrd/Client). When you press Finish Setup, the page shows a `Pairing PIN` once, with a copy button, and the logs show it too:
```bash
docker logs mediagarrd-server 2>&1 | grep "Pairing PIN"
```
Enter it in the Client when it asks. The Client receives a token, uses it once to confirm the pairing and sends it with every request, and the PIN stops working once that is done. To pair another Client, create a pairing code from a paired one, or restart the server with `MEDIAGARRD_OPEN_PAIRING=true` to print a new PIN (remove it afterwards). If you are upgrading from a version without pairing, the logs show a `Pairing PIN` on the first start.

## Updating
```bash
git pull
docker compose up --build -d
```

> **Security:** the server mounts `/var/run/docker.sock`, which gives it root-equivalent access to the host. After setup its API needs a token from pairing, but the PIN and token travel in plain text over HTTP, so put it behind HTTPS ([docs/https.md](./docs/https.md)) before exposing it beyond a trusted network.
>
> A paired Client is treated as the owner of the host: it can choose which host paths are backed up and where archives are written. Only pair Clients you trust. Backup folders and archives created by the server are readable by their owner only (the server runs as root), so restore with `sudo`. See [docs/settings.md](./docs/settings.md#security-notes).

## License
[MIT](./LICENSE)
