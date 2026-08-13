# 图片存储架构

## 唯一实现

Teacup Picture 的业务图片统一存放在 Docker Compose 项目 `teacup-picture` 中的私有 MinIO bucket `teacup-pictures`。MinIO 是当前唯一允许的业务图片持久化实现。

- 后端只能通过 `PictureStorage` 写入、读取和删除图片对象，当前实现为 `MinioPictureStorage`。
- 原图和缩略图分别保存为对象键，数据库只保存 `objectKey`、`thumbnailObjectKey` 和图片元数据。
- 浏览器只能使用后端返回的 `/api/v1/pictures/{id}/content` 或 `/api/v1/public/pictures/{id}/content` URL。
- 前端不得拼接 MinIO endpoint、bucket 或对象键，也不得接收、保存 MinIO 凭据和预签名 URL。
- 私有 bucket 不开放匿名读取。私有图片和公开图片均先经过后端权限或公开状态校验。

## 开发约束

禁止在正常业务链路中新增 COS、OSS、S3、云厂商 SDK、本地文件持久化或第二套图片存储抽象。需要更换存储实现时，必须先形成架构决策，完成全量数据迁移、删除补偿、OpenAPI 和前端访问方式评审；不得通过并存多个 Provider 临时绕过迁移。

新增图片来源也必须在后端落入 MinIO：包括本地上传、URL 导入、批量抓取、AI 生成结果、编辑器导出和未来版本资产。供应商 URL 只能作为导入源，不能作为数据库中的长期业务图片地址。

`LocalPictureStorage` 仅供一次性存量迁移读取旧本地文件，不能注入资源接口或任何正常上传/读取链路。旧 COS 数据必须先离线导出，再通过迁移工具写入 MinIO；项目不再引入 COS SDK。

## 对象与生命周期

对象键格式：

```text
spaces/{spaceId}/pictures/{uuid}/original.{format}
spaces/{spaceId}/pictures/{uuid}/thumbnail.jpeg
```

- 上传数据库写入失败时，删除已上传的原图和缩略图。
- 图片删除通过 `storage_delete_outbox` 可靠重试对象删除。
- 存量迁移默认关闭，仅在显式设置 `TEACUP_STORAGE_MIGRATION_ENABLED=true` 时运行。
- MinIO 数据由 `teacup-picture-minio-data` named volume 持久化；禁止把 `docker compose down --volumes` 作为普通停止命令。

本地启动和账号初始化见 [`../docker/README.md`](../docker/README.md)。
