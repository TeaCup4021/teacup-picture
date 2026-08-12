# Teacup图片系统扩容方案

## 1. 数据库层扩容

### 1.1 读写分离架构
```yaml
# 主从复制配置示例
spring:
  datasource:
    master:
      url: jdbc:mysql://master-db:3306/teacup_picture
      username: ${DB_USERNAME}
      password: ${DB_PASSWORD}
    slave:
      url: jdbc:mysql://slave-db:3306/teacup_picture
      username: ${DB_USERNAME}
      password: ${DB_PASSWORD}
```

### 1.2 分库分表策略
- **按用户ID分库**：user_id % 4，分散到4个数据库实例
- **按时间分表**：picture表按月分表，如picture_202601, picture_202602
- **空间表独立库**：space相关表单独部署

### 1.3 数据库优化配置
```sql
-- 连接池优化
SET GLOBAL max_connections = 2000;
SET GLOBAL innodb_buffer_pool_size = 8G;
SET GLOBAL query_cache_size = 256M;

-- 索引优化
CREATE INDEX idx_user_space_create_time ON picture(user_id, space_id, create_time);
CREATE INDEX idx_space_category ON picture(space_id, category);
CREATE INDEX idx_review_status ON picture(review_status, create_time);
```

## 2. 缓存层扩容

### 2.1 Redis集群部署
```yaml
spring:
  redis:
    cluster:
      nodes:
        - redis-node1:6379
        - redis-node2:6379
        - redis-node3:6379
        - redis-node4:6379
        - redis-node5:6379
        - redis-node6:6379
    lettuce:
      pool:
        max-active: 200
        max-idle: 50
        min-idle: 10
```

### 2.2 多级缓存架构
```
L1缓存(本地): Caffeine (10万条)
L2缓存(分布式): Redis Cluster (100万条)
L3缓存(持久化): MySQL
```

### 2.3 缓存预热策略
```java
@Component
public class CacheWarmUpService {
    
    @Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点执行
    public void warmUpHotData() {
        // 预热热门空间数据
        List<Space> hotSpaces = spaceService.getTopSpaces(1000);
        hotSpaces.forEach(space -> {
            stringRedisTemplate.opsForValue().set(
                "space:hot:" + space.getId(), 
                JSONUtil.toJsonStr(space),
                Duration.ofHours(24)
            );
        });
    }
}
```

## 3. 应用层水平扩展

### 3.1 微服务拆分
```
用户服务(user-service) - 端口: 8081
图片服务(picture-service) - 端口: 8082  
空间服务(space-service) - 端口: 8083
分析服务(analyze-service) - 端口: 8084
网关服务(gateway-service) - 端口: 8080
```

### 3.2 负载均衡配置
```nginx
upstream picture_backend {
    least_conn;
    server app-server-1:8080 weight=3 max_fails=3 fail_timeout=30s;
    server app-server-2:8080 weight=3 max_fails=3 fail_timeout=30s;
    server app-server-3:8080 weight=2 max_fails=3 fail_timeout=30s;
}

server {
    listen 80;
    server_name api.teacup.com;
    
    location /api/ {
        proxy_pass http://picture_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

### 3.3 线程池优化
```java
@Configuration
public class OptimizedThreadPoolConfig {
    
    @Bean
    public ThreadPoolExecutor optimizedThreadPool() {
        return new ThreadPoolExecutor(
            50,   // corePoolSize - 增加核心线程数
            200,  // maximumPoolSize - 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000), // 限制队列长度
            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
        );
    }
}
```

## 4. 存储层扩容

### 4.1 CDN加速配置
```yaml
cdn:
  enabled: true
  domain: cdn.teacup.com
  providers:
    - name: tencent-cdn
      ak: ${CDN_AK}
      sk: ${CDN_SK}
```

### 4.2 图片压缩优化
```java
@Component
public class ImageOptimizationService {
    
    public byte[] compressImage(byte[] imageData, String format) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            // WebP压缩，减少30-50%体积
            return ImageCompressionUtil.compressToWebP(image, 0.8f);
        } catch (Exception e) {
            log.error("图片压缩失败", e);
            return imageData;
        }
    }
}
```

## 5. 监控告警体系

### 5.1 性能监控指标
```
应用层: QPS、响应时间、错误率、JVM内存使用率
数据库: 连接数、慢查询数、CPU使用率、磁盘IO
缓存: 命中率、内存使用率、连接数
存储: 带宽使用率、请求延迟
```

### 5.2 告警阈值设置
```yaml
monitoring:
  alerts:
    - metric: response_time
      threshold: 1000ms
      level: warning
    - metric: error_rate  
      threshold: 5%
      level: critical
    - metric: db_connections
      threshold: 1800
      level: warning
```

## 6. 扩容实施路线图

### 第一阶段 (1-10万DAU)
- ✅ 数据库读写分离
- ✅ Redis主从架构
- ✅ 应用多实例部署
- ✅ 基础监控搭建

### 第二阶段 (10-50万DAU)  
- ✅ 数据库分库分表
- ✅ Redis集群部署
- ✅ 微服务拆分
- ✅ CDN加速接入

### 第三阶段 (50-100万DAU)
- ✅ 多区域部署
- ✅ 智能DNS路由
- ✅ 边缘计算节点
- ✅ AI智能缓存

## 7. 成本估算

| 方案 | 月成本估算 | 性能提升 |
|------|------------|----------|
| 数据库读写分离 | ¥2,000-5,000 | 2-3倍 |
| Redis集群 | ¥1,500-3,000 | 5-10倍 |
| 应用水平扩展 | ¥3,000-8,000 | 3-5倍 |
| CDN加速 | ¥2,000-5,000 | 用户体验显著提升 |
| **总计** | **¥8,500-21,000** | **整体10-20倍性能提升** |

## 8. 风险控制

### 8.1 数据一致性保障
- 使用分布式事务(XA/TCC)
- 最终一致性补偿机制
- 数据同步监控告警

### 8.2 服务降级策略
```java
@Component
public class DegradationStrategy {
    
    @CircuitBreaker(name = "picture-service", fallbackMethod = "fallbackGetPictures")
    public List<PictureVO> getPictures(PictureQueryRequest request) {
        // 正常业务逻辑
    }
    
    public List<PictureVO> fallbackGetPictures(PictureQueryRequest request, Exception ex) {
        // 降级处理：返回缓存数据或简化结果
        return getCachedPictures(request);
    }
}
```

### 8.3 灰度发布流程
1. 小流量测试(1-5%)
2. 逐步放大流量(25%→50%→100%)  
3. 全量上线监控
4. 回滚预案准备