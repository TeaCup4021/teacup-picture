# Local Docker Infrastructure

This Compose project runs the MySQL, Redis, and MinIO dependencies used by Teacup Picture during local development. The backend and frontend continue to run on the host.

The infrastructure images are pinned to MySQL 8.0.46 and Redis 7.4.9 Alpine. Version changes must be reviewed and committed with this Compose file.

Docker Desktop groups both containers under one `teacup-picture` project:

```text
teacup-picture
|- mysql
|- redis
|- minio
`- minio-init
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
MinIO API: http://127.0.0.1:19000
MinIO Console: http://127.0.0.1:19001
```

Start the backend from the repository root with matching environment variables:

```powershell
$env:DB_URL='jdbc:mysql://127.0.0.1:13306/teacup_picture?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:DB_USERNAME='teacup'
$env:DB_PASSWORD='<MYSQL_PASSWORD from docker/.env>'
$env:REDIS_HOST='127.0.0.1'
$env:REDIS_PORT='16379'
$env:REDIS_PASSWORD='<REDIS_PASSWORD from docker/.env>'
$env:MINIO_ENDPOINT='http://127.0.0.1:19000'
$env:MINIO_APP_ACCESS_KEY='<MINIO_APP_ACCESS_KEY from docker/.env>'
$env:MINIO_APP_SECRET_KEY='<MINIO_APP_SECRET_KEY from docker/.env>'
$env:MINIO_BUCKET='teacup-pictures'
$env:OPENAI_API_BASE_URL='https://claudenb.com'
$env:OPENAI_API_KEY='<key whose group exposes gpt-image-2>'
Set-Location backend
mvn spring-boot:run
```

The local Spring profile also imports `docker/.env` as a Java properties file. Values in that file must not be wrapped in quotes. The configured API key and base URL must belong to the same OpenAI-compatible service, and the key's group must expose the image model configured by the backend.

Flyway is the only schema manager. MySQL initialization creates the database and application account, while the backend applies versioned migrations when it starts. `minio-init` creates a private picture bucket and a bucket-scoped application account. It is expected to exit successfully after initialization.

All services belong to the same Compose project named `teacup-picture`. MinIO data is retained in the `teacup-picture-minio-data` named volume. The browser never receives MinIO credentials or object keys; image access goes through the backend permission checks.

MinIO is the repository's only business picture storage. Do not add COS or another storage provider, expose this private bucket to browsers, or let frontend code construct object URLs. See `docs/picture-storage.md` for the cross-stack contract.

## Lifecycle

```powershell
# Stop containers and retain data
docker compose --env-file docker\.env -f docker\compose.yml stop

# Start existing containers
docker compose --env-file docker\.env -f docker\compose.yml start

# Remove containers and retain named volumes
docker compose --env-file docker\.env -f docker\compose.yml down

# Follow service logs
docker compose --env-file docker\.env -f docker\compose.yml logs -f mysql redis minio minio-init
```

Do not add `--volumes` to `down` unless local database and Redis data are intentionally being discarded. The existing standalone `mysql-server` container is unrelated to this project and is not modified by these commands.

## Validation

MySQL, Redis, and MinIO should report `healthy`; `minio-init` should report an exit code of `0`:

```powershell
docker compose --env-file docker\.env -f docker\compose.yml ps
docker compose --env-file docker\.env -f docker\compose.yml exec mysql sh -c 'mysqladmin ping -h localhost -uroot -p"$MYSQL_ROOT_PASSWORD"'
docker compose --env-file docker\.env -f docker\compose.yml exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" ping'
docker compose --env-file docker\.env -f docker\compose.yml exec minio curl -f http://localhost:9000/minio/health/live
```

Changing a password in `.env` does not update credentials inside an existing named volume. To rotate local credentials without deleting data, change them inside the corresponding service first, then update `.env`.

## Legacy Picture Migration

Normal backend startup leaves existing local picture records unchanged. To migrate records whose `picture.objectKey` is still null, set `teacup.storage.migration.enabled=true` (or `TEACUP_STORAGE_MIGRATION_ENABLED=true` if the property is exposed by your profile) for one run, start the backend, and then disable it again. The runner is idempotent and reports processed, skipped, and failed counts. Only legacy local URLs under `PICTURE_LEGACY_LOCAL_ROOT` are supported; old COS objects must first be exported to that directory or imported through an equivalent one-shot tool.
