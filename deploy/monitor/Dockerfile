# Same base as the backup image (shared layers, and psql for the database figures)
FROM postgres:18-alpine
RUN apk add --no-cache msmtp curl tzdata
COPY *.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/*.sh
ENTRYPOINT ["entrypoint.sh"]
CMD ["cron"]
