# Same PostgreSQL version as the database, so pg_dump and pg_restore always match it
FROM postgres:18-alpine
RUN apk add --no-cache rclone age curl tzdata && touch /etc/rclone.conf
# Remotes come from RCLONE_CONFIG_* environment variables (see docker-compose.yml)
ENV RCLONE_CONFIG=/etc/rclone.conf
COPY backup.sh restore.sh entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/*.sh
ENTRYPOINT ["entrypoint.sh"]
CMD ["cron"]
