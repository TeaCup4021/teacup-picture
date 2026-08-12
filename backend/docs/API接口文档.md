# 茶杯图片管理系统 - 后端接口文档

## 文档说明

- **基础URL**: `http://localhost:8123/api`
- **接口前缀**: `/api`
- **数据格式**: JSON
- **字符编码**: UTF-8
- **认证方式**: Session/Cookie (Sa-Token)

---

## 通用响应格式

所有接口均返回统一的响应格式：

```json
{
  "code": 200,
  "data": {},
  "message": ""
}
```

**字段说明：**
- `code`: 状态码（200表示成功，其他为错误码）
- `data`: 响应数据（具体结构见各接口说明）
- `message`: 响应消息（错误时包含错误信息）

---

## 一、用户模块 (User)

### 1.1 用户注册

**接口地址**: `POST /api/user/register`

**请求参数**:
```json
{
  "userAccount": "string",    // 用户账号（必填）
  "userPassword": "string",   // 用户密码（必填）
  "checkPassword": "string",  // 确认密码（必填）
  "userName": "string"        // 用户昵称（可选）
}
```

**响应数据**: `Long` - 新用户ID

**权限要求**: 无

---

### 1.2 用户登录

**接口地址**: `POST /api/user/login`

**请求参数**:
```json
{
  "userAccount": "string",    // 用户账号（必填）
  "userPassword": "string"    // 用户密码（必填）
}
```

**响应数据**: `LoginUserVO`
```json
{
  "id": "string",
  "userAccount": "string",
  "userName": "string",
  "userAvatar": "string",
  "userProfile": "string",
  "userRole": "string",
  "createTime": "datetime"
}
```

**权限要求**: 无

---

### 1.3 获取当前登录用户信息

**接口地址**: `GET /api/user/get/login`

**请求参数**: 无

**响应数据**: `LoginUserVO` (同上)

**权限要求**: 需要登录

---

### 1.4 用户注销

**接口地址**: `POST /api/user/logout`

**请求参数**: 无

**响应数据**: `Boolean` - 是否注销成功

**权限要求**: 需要登录

---

### 1.5 创建用户（管理员）

**接口地址**: `POST /api/user/add`

**请求参数**:
```json
{
  "userAccount": "string",    // 用户账号（必填）
  "userName": "string",       // 用户昵称（可选）
  "userAvatar": "string",     // 用户头像（可选）
  "userProfile": "string",    // 用户简介（可选）
  "userRole": "string"        // 用户角色（可选，默认user）
}
```

**响应数据**: `Long` - 新用户ID

**权限要求**: 仅管理员

---

### 1.6 删除用户（管理员）

**接口地址**: `POST /api/user/delete`

**请求参数**:
```json
{
  "id": 123    // 用户ID（必填）
}
```

**响应数据**: `Boolean` - 是否删除成功

**权限要求**: 仅管理员

---

### 1.7 更新用户（管理员）

**接口地址**: `POST /api/user/update`

**请求参数**:
```json
{
  "id": 123,                // 用户ID（必填）
  "userAccount": "string",  // 用户账号（可选）
  "userName": "string",     // 用户昵称（可选）
  "userAvatar": "string",   // 用户头像（可选）
  "userProfile": "string",  // 用户简介（可选）
  "userRole": "string"      // 用户角色（可选）
}
```

**响应数据**: `Boolean` - 是否更新成功

**权限要求**: 仅管理员

---

### 1.8 根据ID获取用户（管理员）

**接口地址**: `GET /api/user/get?id=123`

**请求参数**: 
- `id` (query参数): 用户ID

**响应数据**: `User` (完整用户信息，未脱敏)

**权限要求**: 仅管理员

---

### 1.9 分页获取用户列表（管理员）

**接口地址**: `POST /api/user/list/page/vo`

**请求参数**:
```json
{
  "current": 1,             // 当前页码（默认1）
  "pageSize": 10,           // 每页条数（默认10）
  "sortField": "string",    // 排序字段（可选）
  "sortOrder": "descend",   // 排序顺序：ascend/descend（默认descend）
  "id": 123,                // 用户ID（可选）
  "userAccount": "string",  // 用户账号（可选）
  "userName": "string",     // 用户昵称（可选）
  "userRole": "string"      // 用户角色（可选）
}
```

**响应数据**: `Page<UserVO>`
```json
{
  "records": [UserVO],      // 用户VO列表
  "total": 100,             // 总记录数
  "size": 10,               // 每页条数
  "current": 1,             // 当前页码
  "pages": 10               // 总页数
}
```

**权限要求**: 仅管理员

---

## 二、图片模块 (Picture)

### 2.1 上传文件图片

**接口地址**: `POST /api/picture/upload`

**请求类型**: `multipart/form-data`

**请求参数**:
- `file`: 图片文件（必填）
- `spaceId`: 空间ID（可选）
- `name`: 图片名称（可选）
- `introduction`: 图片简介（可选）
- `category`: 图片分类（可选）
- `tags`: 图片标签数组（可选）

**响应数据**: `PictureVO`
```json
{
  "id": "string",
  "url": "string",
  "thumbnailUrl": "string",
  "name": "string",
  "introduction": "string",
  "category": "string",
  "tags": ["string"],
  "picSize": 123456,
  "picWidth": 1920,
  "picHeight": 1080,
  "picScale": 1.78,
  "picFormat": "jpg",
  "picColor": "string",
  "userId": "string",
  "spaceId": "string",
  "createTime": "datetime",
  "editTime": "datetime",
  "updateTime": "datetime",
  "permissionList": ["string"]
}
```

**权限要求**: 需要空间上传权限

---

### 2.2 通过URL上传图片

**接口地址**: `POST /api/picture/upload/url`

**请求参数**:
```json
{
  "fileUrl": "string",      // 图片URL（必填）
  "spaceId": 123,           // 空间ID（可选）
  "name": "string",         // 图片名称（可选）
  "introduction": "string", // 图片简介（可选）
  "category": "string",     // 图片分类（可选）
  "tags": ["string"]        // 图片标签（可选）
}
```

**响应数据**: `PictureVO` (同上)

**权限要求**: 需要空间上传权限

---

### 2.3 删除图片

**接口地址**: `POST /api/picture/delete`

**请求参数**:
```json
{
  "id": 123    // 图片ID（必填）
}
```

**响应数据**: `Boolean` - 是否删除成功

**权限要求**: 需要图片删除权限，且为图片上传者或管理员

---

### 2.4 获取图片详情

**接口地址**: `GET /api/picture/get/vo?id=123`

**请求参数**: 
- `id` (query参数): 图片ID

**响应数据**: `PictureVO` (同上)

**权限要求**: 需要空间查看权限

---

### 2.5 编辑图片

**接口地址**: `POST /api/picture/edit`

**请求参数**:
```json
{
  "id": 123,                // 图片ID（必填）
  "name": "string",         // 图片名称（可选）
  "introduction": "string", // 图片简介（可选）
  "category": "string",     // 图片分类（可选）
  "tags": ["string"]        // 图片标签（可选）
}
```

**响应数据**: `Boolean` - 是否编辑成功

**权限要求**: 需要图片编辑权限

---

### 2.6 更新图片（管理员）

**接口地址**: `POST /api/picture/update`

**请求参数**:
```json
{
  "id": 123,                // 图片ID（必填）
  "url": "string",          // 图片URL（可选）
  "thumbnailUrl": "string", // 缩略图URL（可选）
  "name": "string",         // 图片名称（可选）
  "introduction": "string", // 图片简介（可选）
  "category": "string",     // 图片分类（可选）
  "tags": ["string"],       // 图片标签（可选）
  "reviewStatus": 0,        // 审核状态（可选）
  "reviewMessage": "string" // 审核信息（可选）
}
```

**响应数据**: `Boolean` - 是否更新成功

**权限要求**: 仅管理员

---

### 2.7 分页获取图片列表

**接口地址**: `POST /api/picture/list/page`

**请求参数**:
```json
{
  "current": 1,             // 当前页码（默认1）
  "pageSize": 10,           // 每页条数（默认10）
  "sortField": "string",    // 排序字段（可选）
  "sortOrder": "descend",   // 排序顺序（默认descend）
  "id": 123,                // 图片ID（可选）
  "name": "string",         // 图片名称（可选，模糊查询）
  "introduction": "string", // 图片简介（可选，模糊查询）
  "category": "string",     // 图片分类（可选）
  "tags": ["string"],       // 图片标签（可选）
  "userId": 123,            // 用户ID（可选）
  "spaceId": 123,           // 空间ID（可选）
  "reviewStatus": 0         // 审核状态（可选）
}
```

**响应数据**: `Page<Picture>` - 图片实体分页结果

**权限要求**: 无

---

### 2.8 分页获取图片VO列表

**接口地址**: `POST /api/picture/list/page/vo`

**请求参数**: 同 2.7

**响应数据**: `Page<PictureVO>` - 图片VO分页结果

**权限要求**: 
- 公共空间：仅显示审核通过的圖片
- 私有空间：需要空间权限

---

### 2.9 分页获取图片VO列表（带缓存，管理员）

**接口地址**: `POST /api/picture/list/page/vo/cache`

**请求参数**: 同 2.7

**响应数据**: `Page<PictureVO>` - 图片VO分页结果（带缓存）

**权限要求**: 仅管理员

---

### 2.10 获取图片标签和分类

**接口地址**: `GET /api/picture/tag_category`

**请求参数**: 无

**响应数据**: `PictureTagCategory`
```json
{
  "tagList": ["热门", "搞笑", "生活", "高清", "艺术", "校园", "背景", "简历", "创意"],
  "categoryList": ["模板", "电商", "表情包", "素材", "海报"]
}
```

**权限要求**: 无

---

### 2.11 图片审核（管理员）

**接口地址**: `POST /api/picture/review`

**请求参数**:
```json
{
  "id": 123,                // 图片ID（必填）
  "reviewStatus": 0,        // 审核状态：0-待审核，1-通过，2-拒绝（必填）
  "reviewMessage": "string" // 审核信息（可选）
}
```

**响应数据**: `Boolean` - 是否审核成功

**权限要求**: 仅管理员

---

### 2.12 批量上传图片

**接口地址**: `POST /api/picture/upload/batch`

**请求参数**:
```json
{
  "searchText": "string",   // 搜索关键词（必填）
  "count": 10,              // 上传数量（必填）
  "namePrefix": "string",   // 名称前缀（可选）
  "category": "string",     // 分类（可选）
  "tags": ["string"]        // 标签（可选）
}
```

**响应数据**: `Integer` - 成功上传的数量

**权限要求**: 需要登录

---

### 2.13 颜色搜索图片

**接口地址**: `POST /api/picture/search/color`

**请求参数**:
```json
{
  "spaceId": 123,           // 空间ID（必填）
  "picColor": "string"      // 目标颜色值（必填）
}
```

**响应数据**: `List<PictureVO>` - 相似颜色的图片列表

**权限要求**: 需要空间查看权限

---

### 2.14 批量编辑图片

**接口地址**: `POST /api/picture/edit/batch`

**请求参数**:
```json
{
  "pictureIdList": [123, 456],  // 图片ID列表（必填）
  "category": "string",         // 分类（可选）
  "tags": ["string"],           // 标签（可选）
  "nameRule": "string"          // 命名规则（可选）
}
```

**响应数据**: `Boolean` - 是否编辑成功

**权限要求**: 需要图片编辑权限

---

### 2.15 创建扩图任务

**接口地址**: `POST /api/picture/out_painting/create_task`

**请求参数**:
```json
{
  "pictureId": 123,         // 图片ID（必填）
  "xScale": 2.0,            // X轴扩展倍数（可选，默认2.0）
  "yScale": 2.0             // Y轴扩展倍数（可选，默认2.0）
}
```

**响应数据**: `CreateOutPaintingTaskResponse`
```json
{
  "taskId": "string",       // 任务ID
  "status": "string"        // 任务状态
}
```

**权限要求**: 需要登录

---

### 2.16 查询扩图任务状态

**接口地址**: `GET /api/picture/out_painting/get_task?taskId=xxx`

**请求参数**: 
- `taskId` (query参数): 任务ID

**响应数据**: `GetOutPaintingTaskResponse`
```json
{
  "taskId": "string",
  "status": "string",       // 任务状态：PENDING/RUNNING/SUCCESS/FAILED
  "outputImageUrl": "string" // 输出图片URL（成功时）
}
```

**权限要求**: 无

---

### 2.17 获取空间级别列表

**接口地址**: `GET /api/picture/list/level`

**请求参数**: 无

**响应数据**: `List<SpaceLevel>`
```json
[
  {
    "value": "free",
    "text": "免费版",
    "maxCount": 100,
    "maxSize": 10485760
  }
]
```

**权限要求**: 无

---

## 三、空间模块 (Space)

### 3.1 创建空间

**接口地址**: `POST /api/space/add`

**请求参数**:
```json
{
  "spaceName": "string",    // 空间名称（必填）
  "spaceLevel": "string",   // 空间级别（可选，默认free）
  "description": "string"   // 空间描述（可选）
}
```

**响应数据**: `Long` - 新空间ID

**权限要求**: 需要登录

---

### 3.2 删除空间

**接口地址**: `POST /api/space/delete`

**请求参数**:
```json
{
  "id": 123    // 空间ID（必填）
}
```

**响应数据**: `Boolean` - 是否删除成功

**权限要求**: 仅空间创建者或管理员

---

### 3.3 更新空间（管理员）

**接口地址**: `POST /api/space/update`

**请求参数**:
```json
{
  "id": 123,                // 空间ID（必填）
  "spaceName": "string",    // 空间名称（可选）
  "spaceLevel": "string",   // 空间级别（可选）
  "description": "string",  // 空间描述（可选）
  "maxCount": 100,          // 最大图片数量（可选）
  "maxSize": 10485760       // 最大空间大小（可选）
}
```

**响应数据**: `Boolean` - 是否更新成功

**权限要求**: 仅管理员

---

### 3.4 获取空间详情（管理员）

**接口地址**: `GET /api/space/get?id=123`

**请求参数**: 
- `id` (query参数): 空间ID

**响应数据**: `Space` - 完整空间信息

**权限要求**: 仅管理员

---

### 3.5 获取空间详情（封装类）

**接口地址**: `GET /api/space/get/vo?id=123`

**请求参数**: 
- `id` (query参数): 空间ID

**响应数据**: `SpaceVO`
```json
{
  "id": "string",
  "spaceName": "string",
  "spaceLevel": "string",
  "description": "string",
  "maxCount": 100,
  "maxSize": 10485760,
  "usedCount": 50,
  "usedSize": 5242880,
  "userId": "string",
  "createTime": "datetime",
  "editTime": "datetime",
  "updateTime": "datetime",
  "user": UserVO,           // 创建者信息
  "permissionList": ["string"] // 权限列表
}
```

**权限要求**: 需要登录

---

### 3.6 分页获取空间列表（管理员）

**接口地址**: `POST /api/space/list/page`

**请求参数**:
```json
{
  "current": 1,             // 当前页码
  "pageSize": 10,           // 每页条数
  "sortField": "string",    // 排序字段
  "sortOrder": "descend",   // 排序顺序
  "id": 123,                // 空间ID
  "spaceName": "string",    // 空间名称（模糊查询）
  "spaceLevel": "string",   // 空间级别
  "userId": 123             // 用户ID
}
```

**响应数据**: `Page<Space>` - 空间实体分页结果

**权限要求**: 仅管理员

---

### 3.7 分页获取空间VO列表

**接口地址**: `POST /api/space/list/page/vo`

**请求参数**: 同 3.6

**响应数据**: `Page<SpaceVO>` - 空间VO分页结果

**权限要求**: 需要登录

---

### 3.8 编辑空间

**接口地址**: `POST /api/space/edit`

**请求参数**:
```json
{
  "id": 123,                // 空间ID（必填）
  "spaceName": "string",    // 空间名称（可选）
  "description": "string"   // 空间描述（可选）
}
```

**响应数据**: `Boolean` - 是否编辑成功

**权限要求**: 仅空间创建者或管理员

---

### 3.9 获取空间级别列表

**接口地址**: `GET /api/space/list/level`

**请求参数**: 无

**响应数据**: `List<SpaceLevel>` (同 2.17)

**权限要求**: 无

---

## 四、空间用户模块 (SpaceUser)

### 4.1 添加空间成员

**接口地址**: `POST /api/spaceUser/add`

**请求参数**:
```json
{
  "spaceId": 123,           // 空间ID（必填）
  "userId": 456,            // 用户ID（必填）
  "spaceRole": "string",    // 空间角色：owner/admin/member（必填）
  "spacePermission": ["string"] // 权限列表（可选）
}
```

**响应数据**: `Long` - 新关联ID

**权限要求**: 需要空间成员管理权限

---

### 4.2 删除空间成员

**接口地址**: `POST /api/spaceUser/delete`

**请求参数**:
```json
{
  "id": 123    // 空间用户关联ID（必填）
}
```

**响应数据**: `Boolean` - 是否删除成功

**权限要求**: 需要空间成员管理权限

---

### 4.3 获取空间成员详情

**接口地址**: `POST /api/spaceUser/get`

**请求参数**:
```json
{
  "spaceId": 123,           // 空间ID（必填）
  "userId": 456             // 用户ID（必填）
}
```

**响应数据**: `SpaceUser`
```json
{
  "id": "string",
  "spaceId": "string",
  "userId": "string",
  "spaceRole": "string",
  "spacePermission": "string",
  "createTime": "datetime",
  "updateTime": "datetime"
}
```

**权限要求**: 需要空间成员管理权限

---

### 4.4 查询空间成员列表

**接口地址**: `POST /api/spaceUser/list`

**请求参数**:
```json
{
  "spaceId": 123,           // 空间ID（可选）
  "userId": 456,            // 用户ID（可选）
  "spaceRole": "string"     // 空间角色（可选）
}
```

**响应数据**: `List<SpaceUserVO>`
```json
[
  {
    "id": "string",
    "spaceId": "string",
    "userId": "string",
    "spaceRole": "string",
    "spacePermission": "string",
    "user": UserVO,         // 用户信息
    "space": SpaceVO,       // 空间信息
    "createTime": "datetime",
    "updateTime": "datetime"
  }
]
```

**权限要求**: 需要空间成员管理权限

---

### 4.5 编辑空间成员

**接口地址**: `POST /api/spaceUser/edit`

**请求参数**:
```json
{
  "id": 123,                // 关联ID（必填）
  "spaceRole": "string",    // 空间角色（可选）
  "spacePermission": ["string"] // 权限列表（可选）
}
```

**响应数据**: `Boolean` - 是否编辑成功

**权限要求**: 需要空间成员管理权限

---

### 4.6 查询我的团队空间

**接口地址**: `POST /api/spaceUser/list/my`

**请求参数**: 无

**响应数据**: `List<SpaceUserVO>` - 当前用户参与的所有空间列表

**权限要求**: 需要登录

---

## 五、空间分析模块 (Space Analyze)

### 5.1 获取空间使用分析

**接口地址**: `POST /api/space/analyze/usage`

**请求参数**:
```json
{
  "spaceId": 123            // 空间ID（必填）
}
```

**响应数据**: `SpaceUsageAnalyzeResponse`
```json
{
  "spaceId": "string",
  "maxCount": 100,
  "usedCount": 50,
  "remainingCount": 50,
  "maxSize": 10485760,
  "usedSize": 5242880,
  "remainingSize": 5242880,
  "usagePercentage": 50.0
}
```

**权限要求**: 需要登录

---

### 5.2 获取空间分类分析

**接口地址**: `POST /api/space/analyze/category`

**请求参数**:
```json
{
  "spaceId": 123            // 空间ID（必填）
}
```

**响应数据**: `List<SpaceCategoryAnalyzeResponse>`
```json
[
  {
    "category": "string",
    "count": 10,
    "percentage": 20.0
  }
]
```

**权限要求**: 需要登录

---

### 5.3 获取空间大小分析

**接口地址**: `POST /api/space/analyze/size`

**请求参数**:
```json
{
  "spaceId": 123            // 空间ID（必填）
}
```

**响应数据**: `List<SpaceSizeAnalyzeResponse>`
```json
[
  {
    "sizeRange": "string",
    "count": 10,
    "percentage": 20.0
  }
]
```

**权限要求**: 需要登录

---

### 5.4 获取空间用户分析

**接口地址**: `POST /api/space/analyze/user`

**请求参数**:
```json
{
  "spaceId": 123            // 空间ID（必填）
}
```

**响应数据**: `List<SpaceUserAnalyzeResponse>`
```json
[
  {
    "userId": "string",
    "userName": "string",
    "userAvatar": "string",
    "pictureCount": 10,
    "percentage": 20.0
  }
]
```

**权限要求**: 需要登录

---

### 5.5 获取空间排名分析

**接口地址**: `POST /api/space/analyze/rank`

**请求参数**:
```json
{
  "topN": 10                // 排名前N个（可选，默认10）
}
```

**响应数据**: `List<Space>` - 按使用量排名的空间列表

**权限要求**: 需要登录

---

## 六、文件模块 (File)

### 6.1 测试文件上传（管理员）

**接口地址**: `POST /api/file/test/upload`

**请求类型**: `multipart/form-data`

**请求参数**:
- `file`: 文件（必填）

**响应数据**: `String` - 文件路径

**权限要求**: 仅管理员

---

### 6.2 测试文件下载（管理员）

**接口地址**: `GET /api/file/test/download?filepath=/test/xxx.jpg`

**请求参数**: 
- `filepath` (query参数): 文件路径

**响应数据**: 文件流（二进制下载）

**权限要求**: 仅管理员

---

## 七、WebSocket实时协作

### 7.1 图片编辑WebSocket连接

**连接地址**: `ws://localhost:8123/api/ws/picture/edit/{pictureId}`

**路径参数**: 
- `pictureId`: 图片ID

**握手拦截器**: `WsHandshakeInterceptor`

**消息处理器**: `PictureEditHandler`

**消息格式**:
```json
{
  "type": "string",         // 消息类型：EDIT/UNDO/REDO/CURSOR
  "data": {}                // 消息数据
}
```

**权限要求**: 需要登录且有图片编辑权限

---

## 八、权限说明

### 8.1 用户角色

- `user`: 普通用户
- `admin`: 管理员

### 8.2 空间角色

- `owner`: 空间所有者
- `admin`: 空间管理员
- `member`: 空间成员

### 8.3 空间权限常量

- `PICTURE_VIEW`: 查看图片
- `PICTURE_UPLOAD`: 上传图片
- `PICTURE_EDIT`: 编辑图片
- `PICTURE_DELETE`: 删除图片
- `SPACE_USER_MANAGE`: 管理空间成员

---

## 九、错误码说明

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 40000 | 请求参数错误 |
| 40100 | 未登录 |
| 40300 | 无权限 |
| 40400 | 资源不存在 |
| 50000 | 系统内部错误 |

---

## 十、注意事项

1. **认证机制**: 大部分接口需要先登录，登录后Session会自动保存，后续请求会自动携带认证信息
2. **权限校验**: 部分接口使用了注解式权限校验（`@AuthCheck`、`@SaSpaceCheckPermission`），请确保有足够的权限
3. **文件大小限制**: 单个文件上传最大10MB
4. **分页限制**: 部分接口限制了单次最多查询20条数据
5. **跨域配置**: 已配置CORS，支持跨域请求
6. **缓存策略**: 部分接口使用了本地缓存（Caffeine）+ 分布式缓存（Redis）的两级缓存策略

---

## 十一、开发建议

### 前端调用示例

```javascript
// 1. 用户登录
const login = async (userAccount, userPassword) => {
  const response = await fetch('/api/user/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include', // 携带Cookie
    body: JSON.stringify({
      userAccount,
      userPassword
    })
  });
  return await response.json();
};

// 2. 获取图片列表
const getPictureList = async (params) => {
  const response = await fetch('/api/picture/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include',
    body: JSON.stringify(params)
  });
  return await response.json();
};

// 3. 上传图片
const uploadPicture = async (file, formData) => {
  const form = new FormData();
  form.append('file', file);
  form.append('name', formData.name);
  
  const response = await fetch('/api/picture/upload', {
    method: 'POST',
    credentials: 'include',
    body: form
  });
  return await response.json();
};
```

### WebSocket连接示例

```javascript
// 连接WebSocket
const ws = new WebSocket(`ws://localhost:8123/api/ws/picture/edit/${pictureId}`);

ws.onopen = () => {
  console.log('WebSocket连接成功');
};

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('收到消息:', data);
};

ws.onerror = (error) => {
  console.error('WebSocket错误:', error);
};

ws.onclose = () => {
  console.log('WebSocket连接关闭');
};

// 发送消息
ws.send(JSON.stringify({
  type: 'EDIT',
  data: { /* 编辑数据 */ }
}));
```

---

## 十二、联系方式

如有问题，请联系后端开发团队。

**文档版本**: v1.0  
**更新时间**: 2026-04-06
