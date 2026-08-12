# Local Docker Infrastructure

This Compose project runs the MySQL and Redis dependencies used by Teacup Picture during local development. The backend and frontend continue to run on the host during M1.

The infrastructure images are pinned to MySQL 8.0.46 and Redis 7.4.9 Alpine. Version changes must be reviewed and committed with this Compose file.

Docker Desktop groups both containers under one `teacup-picture` project:

```text
teacup-picture
|- mysql
`- redis
```

## First Start

Create the local environment file from `.env.example` and replace both example passwords. The committed example is documentation only; `.env` is ignored by Git.

```powershell
Copy-Item docker\.env.example docker\.env
docker compose --env-file docker\.env -f docker\compose.yml up -d
docker compose --env-file docker\.env -f docker\compose.yml ps
```

The default host endpoints are:

```text
MySQL: 127.0.0.1:13306
Redis: 127.0.0.1:16379
```

Start the backend from the repository root with matching environment variables:

```powershell
$env:DB_URL='jdbc:mysql://127.0.0.1:13306/teacup_picture?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:DB_USERNAME='teacup'
$env:DB_PASSWORD='<MYSQL_PASSWORD from docker/.env>'
$env:REDIS_HOST='127.0.0.1'
$env:REDIS_PORT='16379'
$env:REDIS_PASSWORD='<REDIS_PASSWORD from docker/.env>'
Set-Location backend
mvn spring-boot:run
```

Flyway is the only schema manager. MySQL initialization creates the database and application account, while the backend applies versioned migrations when it starts.

## Lifecycle

```powershell
# Stop containers and retain data
docker compose --env-file docker\.env -f docker\compose.yml stop

# Start existing containers
docker compose --env-file docker\.env -f docker\compose.yml start

# Remove containers and retain named volumes
docker compose --env-file docker\.env -f docker\compose.yml down

# Follow service logs
docker compose --env-file docker\.env -f docker\compose.yml logs -f mysql redis
```

Do not add `--volumes` to `down` unless local database and Redis data are intentionally being discarded. The existing standalone `mysql-server` container is unrelated to this project and is not modified by these commands.

## Validation

Both services should report `healthy`:

```powershell
docker compose --env-file docker\.env -f docker\compose.yml ps
docker compose --env-file docker\.env -f docker\compose.yml exec mysql sh -c 'mysqladmin ping -h localhost -uroot -p"$MYSQL_ROOT_PASSWORD"'
docker compose --env-file docker\.env -f docker\compose.yml exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" ping'
```

Changing a password in `.env` does not update credentials inside an existing named volume. To rotate local credentials without deleting data, change them inside the corresponding service first, then update `.env`.
