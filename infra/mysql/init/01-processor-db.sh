#!/bin/sh
set -eu
# MYSQL_USER is restricted to the same safe identifier format as the MySQL image.
case "$MYSQL_USER" in ''|*[!a-zA-Z0-9_]*) echo "Unsupported MYSQL_USER" >&2; exit 1;; esac
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -u root <<EOSQL
CREATE DATABASE IF NOT EXISTS processor;
GRANT ALL PRIVILEGES ON processor.* TO '$MYSQL_USER'@'%';
EOSQL
