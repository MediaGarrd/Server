# Server

The official server for [MediaGarrd](https://github.com/MediaGarrd/MediaGarrd). It backs up the configuration of the services on its host into a single zip archive on a schedule, and the [MediaGarrd Client](https://github.com/MediaGarrd/Client) manages it and downloads the archives to another machine.

Full documentation, including configuration, archives and restoring, is on the [MediaGarrd website](https://mediagarrd.github.io/MediaGarrd/).

## Docs
- [settings.md](./docs/settings.md): every environment variable and its default.
- [API.md](./docs/API.md): every HTTP endpoint and what it returns.
- [database.md](./docs/database.md): recovering the server's database from a backup.

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

## Updating
```bash
git pull
docker compose up --build -d
```

> **Security:** the server mounts `/var/run/docker.sock`, which gives it root-equivalent access to the host, and its API is unauthenticated after setup. Do not expose its port to untrusted networks.

## License
[MIT](./LICENSE)
