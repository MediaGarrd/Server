# HTTPS

The Server speaks plain HTTP. To serve it over HTTPS, put a reverse proxy in front of it. This matters because the PIN and the token travel in plain text over HTTP. On a network you do not control, use HTTPS. The Client has its own page for the dashboard side: [Client docs/https.md](https://github.com/MediaGarrd/Client/blob/main/docs/https.md).

The Server has no built-in TLS settings.

## 1. Keep the plain port private
`docker-compose.example.yml` publishes the Server with `"38471:38471"`. Remove that mapping so the plain port is not reachable from outside, and put the Server and the proxy on a shared Docker network. The proxy reaches it by container name.

```yaml
services:
  mediagarrd-server:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: mediagarrd-server
    environment:
      - SERVER_PORT=38471
      - MEDIAGARRD_BACKUP_INTERVAL=PT12H
      - MEDIAGARRD_RETENTION_COUNT=10
    volumes:
      - "./data/server/backups:/var/lib/mediagarrd/backups"
      - "./data/server/db:/var/lib/mediagarrd/db"
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - proxy
    restart: unless-stopped

networks:
  proxy:
    name: proxy
```

Run the proxy on the same network (`proxy`) and send traffic to `mediagarrd-server:38471`. The Server's `/config` setup page is also served through the proxy, so do the first setup over HTTPS too.

## 2. Reverse proxy examples
Both examples assume the proxy is a container on the `proxy` network and the Server is reached at `https://mediagarrd.example.com`.

### Caddy
Caddy gets and renews a certificate by itself for a public domain.
```
mediagarrd.example.com {
    reverse_proxy mediagarrd-server:38471 {
        flush_interval -1
    }
}
```
`flush_interval -1` sends the live event stream (`/api/v1/events`) through immediately. Caddy sets no time limit on a response, so large backup downloads are not cut off.

### nginx
```nginx
server {
    listen 443 ssl http2;
    server_name mediagarrd.example.com;

    ssl_certificate     /etc/ssl/mediagarrd/fullchain.pem;
    ssl_certificate_key /etc/ssl/mediagarrd/privkey.pem;

    location / {
        proxy_pass http://mediagarrd-server:38471;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```
- On nginx 1.25.1 or newer, `http2 on;` as its own directive replaces the `http2` parameter on `listen`; the parameter still works there but is deprecated.
- `proxy_buffering off` lets the event stream through and avoids writing multi-gigabyte archives to the proxy's disk.
- The one-hour timeouts keep a slow download from being cut off, and a backup run started through the API (`POST /api/v1/backups/run`) waits for the backup to finish, which can take a while. Raise them if your backups take longer.
- The Authorization header is passed to the Server by default.

## 3. Connect the Client
Give the Client the `https://` address, for example `https://mediagarrd.example.com`. Without a port it uses 443. A token is tied to the exact address it was paired on, so switching from `http://<ip>:38471` to `https://...` means pairing again with a PIN or a pairing code.

The Client does not follow redirects when it talks to the Server, so a proxy rule that redirects HTTP to HTTPS makes an `http://` address fail. For a Server that is already selected the dashboard reports the address the redirect points to; while connecting ("Connect by IP" or `MEDIAGARRD_SERVER_IP`) you only see that no server was found. Use the `https://` address.

### Certificates from your own authority or self-signed
A certificate from a public authority (Let's Encrypt, Caddy's automatic certificates, Tailscale) needs nothing extra. For a private or self-signed certificate, give the Client the certificate authority with `MEDIAGARRD_SERVER_CA`, described in the Client's [docs/https.md](https://github.com/MediaGarrd/Client/blob/main/docs/https.md). If you reach the Server by IP address, the certificate needs that IP in its Subject Alternative Names.

## Tailscale
If both machines are on the same tailnet, `tailscale serve` or a Tailscale certificate for the machine name gives you HTTPS without exposing anything to the internet.
