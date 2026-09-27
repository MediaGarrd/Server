# Server

This is the official server for [MediaGarrd](https://github.com/MediaGarrd/MediaGarrd).

## Supported Services
A list of all supported services can be found [here](./ref/services/SUPPORTED_SERVICES). If you would like a service added, open an issue and/or read through the contributor-guide.

## Installation
```bash
git clone --depth 1 https://github.com/MediaGarrd/Server.git
cd Server
./install.sh # Drops .env, dockerenv/server.env, and docker-compose.yml
docker compose up --build -d
```

## License
[GPLv3](./LICENSE)
