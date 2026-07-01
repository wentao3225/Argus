# API 网关设计与流量治理

## 1. API 网关概述

API 网关是微服务架构中的核心组件，作为系统的统一入口，负责请求路由、协议转换、安全认证、流量控制等功能。API 网关将横切关注点（Cross-Cutting Concerns）从业务服务中抽离，使开发者专注于业务逻辑。

### 1.1 为什么需要 API 网关

在没有 API 网关的架构中，客户端需要直接调用各个微服务，面临以下问题：

- **客户端复杂度高**：客户端需要知道每个服务的地址和协议
- **安全风险大**：每个服务都需要独立实现认证和授权
- **跨域问题**：浏览器端需要处理多个服务的 CORS 配置
- **协议不统一**：内部服务可能使用 gRPC，外部需要 REST

API 网关作为统一入口，解决了上述所有问题。

### 1.2 主流 API 网关

**Spring Cloud Gateway**：基于 Spring WebFlux 的响应式网关，与 Spring 生态深度集成。支持路由断言、过滤器链、限流和熔断。

**Kong**：基于 Nginx/OpenResty 的高性能网关，支持丰富的插件生态。社区版免费，企业版提供管理界面和技术支持。

**APISIX**：Apache 顶级项目，基于 etcd 存储配置，支持热更新。性能优于 Kong，配置变更无需重启。

**Envoy**：由 Lyft 开发的高性能代理，Istio 服务网格的数据面。支持 gRPC 原生代理和高级负载均衡。

## 2. 路由设计

### 2.1 路由规则

API 网关的路由规则定义了请求如何映射到后端服务。常见的路由匹配条件包括：

- **路径匹配**：`/api/v1/users/**` → 用户服务
- **方法匹配**：`GET /api/orders` → 订单查询服务，`POST /api/orders` → 订单创建服务
- **请求头匹配**：`X-Tenant-Id: premium` → 高级租户专用服务
- **查询参数匹配**：`?version=2` → V2 版本服务

Spring Cloud Gateway 路由配置示例：
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service
          predicates:
            - Path=/api/v1/users/**
            - Method=GET,POST
          filters:
            - StripPrefix=2
            - name: CircuitBreaker
              args:
                name: user-cb
                fallbackUri: forward:/fallback/users
```

### 2.2 服务发现集成

API 网关通常与服务注册中心集成，通过服务名（而非硬编码的 IP 地址）路由请求。这使得后端服务可以动态扩缩容，网关自动感知实例变化。

`lb://user-service` 表示使用负载均衡器从注册中心获取 `user-service` 的实例列表。

### 2.3 灰度路由

灰度发布（Canary Release）通过路由规则将部分流量引导到新版本服务，逐步验证新版本的稳定性。灰度路由的常见策略：

- **按比例分流**：10% 流量到新版本，90% 到旧版本
- **按用户分流**：内部员工或特定用户组路由到新版本
- **按地域分流**：特定地区的用户路由到新版本
- **按请求特征分流**：特定 Header 或参数的请求路由到新版本

## 3. 安全认证

### 3.1 JWT 认证

JWT（JSON Web Token）是 API 网关最常用的认证方式。JWT 包含三部分：Header（算法类型）、Payload（用户信息和权限）、Signature（签名）。

JWT 认证流程：
1. 用户通过登录接口获取 Access Token 和 Refresh Token
2. 客户端在每个请求的 Authorization 头中携带 Access Token
3. 网关验证 JWT 的签名和有效期
4. 验证通过后将用户信息注入请求头，转发给下游服务

JWT 的安全最佳实践：
- 使用 RS256 非对称算法，网关只需公钥即可验证
- Access Token 有效期设置为 15-30 分钟
- Refresh Token 存储在数据库中，支持主动撤销
- 将用户 ID、角色、权限等信息放入 JWT Claims

### 3.2 OAuth 2.0

OAuth 2.0 是一个授权框架，定义了四种授权模式：

- **授权码模式（Authorization Code）**：最安全的模式，适用于 Web 应用
- **隐式模式（Implicit）**：简化版授权码模式，适用于 SPA（已不推荐）
- **密码模式（Resource Owner Password）**：适用于第一方应用
- **客户端凭证模式（Client Credentials）**：适用于服务间调用

API 网关作为 OAuth 2.0 的资源服务器，负责验证 Access Token 的有效性。

### 3.3 API Key 管理

API Key 是最简单的认证方式，适用于第三方开发者接入。API Key 管理应包含：

- Key 的生成和分发
- Key 的权限范围控制（可访问的 API 列表）
- Key 的调用频率限制
- Key 的过期和吊销

## 4. 流量控制

### 4.1 限流算法

**令牌桶算法（Token Bucket）**：系统以固定速率向桶中添加令牌，请求需要消耗一个令牌才能通过。桶有最大容量，多余的令牌会被丢弃。令牌桶允许突发流量（桶中有积累的令牌）。

**漏桶算法（Leaky Bucket）**：请求进入桶中，系统以固定速率从桶中取出请求处理。桶满时新请求被拒绝。漏桶强制平滑输出速率。

**滑动窗口算法**：维护一个时间窗口内的请求计数。相比固定窗口，滑动窗口避免了窗口边界处的突发流量问题。

Spring Cloud Gateway 集成 Redis 实现分布式限流：
```yaml
filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 10    # 每秒放行请求数
      redis-rate-limiter.burstCapacity: 20     # 突发容量
      redis-rate-limiter.requestedTokens: 1    # 每次请求消耗的令牌数
```

### 4.2 熔断降级

API 网关的熔断机制保护系统免受下游服务故障的影响。当某个服务的错误率超过阈值时，网关直接返回降级响应，不再将请求转发到故障服务。

熔断器的三种状态：
- **关闭（Closed）**：正常状态，请求正常转发
- **打开（Open）**：错误率超阈值，所有请求直接降级
- **半开（Half-Open）**：冷却期结束后，允许部分请求通过以检测恢复

降级策略包括：
- 返回缓存数据
- 返回默认值
- 返回友好的错误提示
- 路由到备用服务

### 4.3 请求合并

对于客户端频繁发起的细粒度请求，API 网关可以将多个请求合并为一个批量请求发送给后端服务，减少网络往返次数。

请求合并的实现方式：
- 客户端使用 GraphQL 的批量查询
- 网关层使用 BFF（Backend for Frontend）聚合多个服务的响应
- 使用 DataLoader 模式自动合并同一数据源的请求

## 5. 可观测性

### 5.1 请求日志

API 网关应该记录每个请求的详细信息，包括：
- 请求方法、路径、查询参数
- 客户端 IP、User-Agent
- 用户身份（从 JWT 中提取）
- 响应状态码和延迟时间
- 上游服务的响应时间

结构化日志格式（JSON）便于日志聚合和分析：
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "method": "POST",
  "path": "/api/v1/orders",
  "status": 201,
  "duration_ms": 156,
  "user_id": "u-12345",
  "client_ip": "192.168.1.100",
  "upstream": "order-service",
  "upstream_duration_ms": 120
}
```

### 5.2 链路追踪

API 网关是链路追踪的起点，负责生成 Trace ID 并通过请求头传递给下游服务。网关应该集成 OpenTelemetry 或 SkyWalking 等追踪系统。

### 5.3 告警规则

API 网关应该配置以下告警规则：
- **错误率告警**：某个服务的 5xx 错误率超过 5%
- **延迟告警**：P99 延迟超过 2 秒
- **限流告警**：某个 API 的限流触发次数异常增加
- **熔断告警**：熔断器打开事件

## 6. 性能优化

### 6.1 响应缓存

对于读多写少的 API，可以在网关层缓存响应结果。缓存策略包括：
- 基于 URL 和查询参数的缓存键
- 通过 Cache-Control 头控制缓存过期
- 支持缓存清除（PURGE）操作
- 使用 Redis 作为分布式缓存存储

### 6.2 请求压缩

网关可以对响应体进行 gzip 或 brotli 压缩，减少网络传输数据量。对于 JSON 响应，压缩率通常在 70%-90%。

### 6.3 连接池优化

网关与后端服务之间使用连接池复用 TCP 连接，避免频繁建立和关闭连接的开销。关键参数：
- 最大连接数
- 最大空闲连接数
- 连接超时时间
- 空闲连接回收时间

对于 HTTP/2，还可以利用多路复用特性，在单个 TCP 连接上并发多个请求。
