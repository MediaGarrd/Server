#!/usr/bin/env bash
set -euo pipefail
source ./scripts/install-util.sh

prompt server_port "MediaGarrd-Server host port [$PORT_DEFAULT]: " "$PORT_DEFAULT"
prompt backup_interval "Server automatic backup interval in ISO-8601 format [$INTERVAL_DEFAULT]: " "$INTERVAL_DEFAULT"
prompt backup_root_host "Server backup host path [$BACKUP_LOC_DEFAULT]: " "$BACKUP_LOC_DEFAULT"
prompt backup_retention "Maximum number of backups to keep [$BACKUP_RETENTION_DEFAULT]: " "$BACKUP_RETENTION_DEFAULT"

yes_no jellyfin_enabled "Enable Jellyfin backups? [Y/n]: " "y"
if [[ "$jellyfin_enabled" == "true" ]]; then
    prompt jellyfin_path "Jellyfin source base directory [$DEFAULT_APPDATA_DIR/jellyfin]: " "$DEFAULT_APPDATA_DIR/jellyfin"
fi

yes_no radarr_enabled "Enable Radarr backups? [Y/n]: " "y"
if [[ "$radarr_enabled" == "true" ]]; then
    prompt radarr_path "Radarr source base directory [$DEFAULT_APPDATA_DIR/radarr]: " "$DEFAULT_APPDATA_DIR/radarr"
fi

yes_no sonarr_enabled "Enable Sonarr backups? [Y/n]: " "y"
if [[ "$sonarr_enabled" == "true" ]]; then
    prompt sonarr_path "Sonarr source base directory [$DEFAULT_APPDATA_DIR/sonarr]: " "$DEFAULT_APPDATA_DIR/sonarr"
fi

yes_no prowlarr_enabled "Enable Prowlarr backups? [Y/n]: " "y"
if [[ "$prowlarr_enabled" == "true" ]]; then
    prompt prowlarr_path "Prowlarr source base directory [$DEFAULT_APPDATA_DIR/prowlarr]: " "$DEFAULT_APPDATA_DIR/prowlarr"
fi

yes_no tdarr_enabled "Enable Tdarr backups? [Y/n]: " "y"
if [[ "$tdarr_enabled" == "true" ]]; then
    prompt tdarr_path "Tdarr source base directory [$DEFAULT_APPDATA_DIR/tdarr]: " "$DEFAULT_APPDATA_DIR/tdarr"
fi

yes_no qbittorrent_enabled "Enable qBittorrent backups? [Y/n]: " "y"
if [[ "$qbittorrent_enabled" == "true" ]]; then
    prompt qbittorrent_path "qBittorrent source base directory [$DEFAULT_APPDATA_DIR/qbittorrent]: " "$DEFAULT_APPDATA_DIR/qbittorrent"

    yes_no has_saved_torrents "Would you like to add an additional path for stored .torrent files? [Y/n]: " "n"
    if [[ "$has_saved_torrents" == "true" ]]; then
        prompt qbittorrent_saved_torrents_path "qBittorrent saved-torrents source directory [$MNT_DIR/torrents]: " "$MNT_DIR/torrents"
    fi
fi

{
    cat <<EOF_ENV
SERVER_PORT=$server_port
MEDIAGARRD_BACKUP_INTERVAL=$backup_interval
BACKUP_ROOT_HOST_PATH=$backup_root_host
MEDIAGARRD_RETENTION_COUNT=$backup_retention
EOF_ENV
    if [[ "$jellyfin_enabled" == "true" ]]; then
        echo "JELLYFIN_PATH=$jellyfin_path"
    fi
    if [[ "$radarr_enabled" == "true" ]]; then
        echo "RADARR_PATH=$radarr_path"
    fi
    if [[ "$sonarr_enabled" == "true" ]]; then
        echo "SONARR_PATH=$sonarr_path"
    fi
    if [[ "$prowlarr_enabled" == "true" ]]; then
        echo "PROWLARR_PATH=$prowlarr_path"
    fi
    if [[ "$tdarr_enabled" == "true" ]]; then
        echo "TDARR_PATH=$tdarr_path"
    fi
    if [[ "$qbittorrent_enabled" == "true" ]]; then
        echo "QBITTORRENT_PATH=$qbittorrent_path"
        if [[ "$has_saved_torrents" == "true" ]]; then
            echo "QBITTORRENT_SAVED_TORRENTS_PATH=$qbittorrent_saved_torrents_path"
        fi
    fi
} > "$ENV_FILE"

{
    cat <<EOF_SERVER
SERVER_PORT=$server_port
MEDIAGARRD_BACKUP_INTERVAL=$backup_interval
MEDIAGARRD_BACKUP_ROOT=/var/lib/mediagarrd/backups
MEDIAGARRD_RETENTION_COUNT=$backup_retention
EOF_SERVER
    if [[ "$jellyfin_enabled" == "true" ]]; then
        echo "JELLYFIN_ENABLED=true"
    fi
    if [[ "$radarr_enabled" == "true" ]]; then
        echo "RADARR_ENABLED=true"
    fi
    if [[ "$sonarr_enabled" == "true" ]]; then
        echo "SONARR_ENABLED=true"
    fi
    if [[ "$prowlarr_enabled" == "true" ]]; then
        echo "PROWLARR_ENABLED=true"
    fi
    if [[ "$tdarr_enabled" == "true" ]]; then
        echo "TDARR_ENABLED=true"
    fi
    if [[ "$qbittorrent_enabled" == "true" ]]; then
        echo "QBITTORRENT_ENABLED=true"
    fi
} > "$SERVER_ENV_FILE"

  rm -f "$CLIENT_ENV_FILE"

{
    cat <<EOF_COMPOSE
services:
  mediagarrd-server:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: mediagarrd-server
    env_file:
      - ./secrets/server.env
    ports:
      - "$server_port:$server_port"
    volumes:
      - "$backup_root_host:/var/lib/mediagarrd/backups"
EOF_COMPOSE

    if [[ "$jellyfin_enabled" == "true" ]]; then
        echo "      - \"$jellyfin_path:/srv/sources/jellyfin:ro\""
    fi
    if [[ "$radarr_enabled" == "true" ]]; then
        echo "      - \"$radarr_path:/srv/sources/radarr:ro\""
    fi
    if [[ "$sonarr_enabled" == "true" ]]; then
        echo "      - \"$sonarr_path:/srv/sources/sonarr:ro\""
    fi
    if [[ "$prowlarr_enabled" == "true" ]]; then
        echo "      - \"$prowlarr_path:/srv/sources/prowlarr:ro\""
    fi
    if [[ "$tdarr_enabled" == "true" ]]; then
        echo "      - \"$tdarr_path:/srv/sources/tdarr:ro\""
    fi
    if [[ "$qbittorrent_enabled" == "true" ]]; then
        echo "      - \"$qbittorrent_path:/srv/sources/qbittorrent:ro\""
        if [[ "$has_saved_torrents" == "true" ]]; then
            echo "      - \"$qbittorrent_saved_torrents_path:/srv/sources/qbittorrent-saved-torrents:ro\""
        fi
    fi
    echo "    restart: unless-stopped"
} > "$COMPOSE_FILE"

echo ""
echo "Created: $COMPOSE_FILE"
echo "Created: $ENV_FILE"
echo "Created: $SERVER_ENV_FILE"
echo ""
echo "Next steps:"
echo "1. Review .env"
echo "2. Review secrets/server.env"
echo "3. Review docker-compose.yml"
echo "4. Start services with: docker compose up --build -d"
