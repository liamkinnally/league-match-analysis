FROM postgres:17.11-bookworm

RUN apt-get update \
    && apt-get install --yes --no-install-recommends awscli ca-certificates python3 \
    && rm -rf /var/lib/apt/lists/*

COPY backup.sh /usr/local/bin/league-analysis-backup
RUN chmod 0755 /usr/local/bin/league-analysis-backup

ENTRYPOINT ["/usr/local/bin/league-analysis-backup"]
