# Docker 容器化部署最佳实践

## 1. Docker 基础概念

Docker 是一个开源的容器化平台，用于构建、发布和运行应用程序。Docker 的核心思想是将应用程序及其依赖打包在一个轻量级、可移植的容器中，实现"一次构建，到处运行"。

### 1.1 容器与虚拟机

容器和虚拟机都提供资源隔离，但实现方式完全不同：

- **虚拟机**：通过 Hypervisor 模拟完整的操作系统，每个 VM 包含独立的内核。启动时间通常在分钟级别，内存开销在 GB 级别。
- **容器**：共享宿主机的内核，通过 Linux 的 Namespace 和 Cgroups 实现隔离。启动时间在秒级，内存开销在 MB 级别。

容器的轻量特性使其成为微服务架构的理想部署单元。

### 1.2 镜像分层

Docker 镜像由多个只读层（Layer）组成，每层代表 Dockerfile 中的一条指令。层的共享机制使得多个容器可以共享相同的基础镜像层，显著减少存储空间。

理解镜像分层对于优化 Dockerfile 至关重要：
- 频繁变化的层应该放在 Dockerfile 的后面
- 不常变化的层（如基础镜像、依赖安装）应该放在前面
- 利用构建缓存可以显著加速镜像构建

## 2. Dockerfile 编写规范

### 2.1 多阶段构建

多阶段构建（Multi-stage Build）是优化镜像大小的关键技术。在构建阶段使用包含编译工具的完整镜像，在运行阶段使用精简的基础镜像：

```dockerfile
# 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

使用多阶段构建后，最终镜像只包含 JRE 和应用 JAR，不包含 Maven、源码和构建缓存，镜像大小可从 800MB 减小到 200MB 左右。

### 2.2 基础镜像选择

选择合适的基础镜像对安全性和镜像大小都有重要影响：

- **Alpine Linux**：镜像最小（约 5MB），但使用 musl libc，某些 Java 库可能不兼容
- **Distroless**：Google 提供的极简镜像，只包含运行时，没有 shell 和包管理器
- **Ubuntu/Debian**：兼容性最好，但镜像较大（约 70-100MB）

对于 Java 应用，推荐使用 Eclipse Temurin 的 JRE Alpine 镜像：
```dockerfile
FROM eclipse-temurin:21-jre-alpine
```

### 2.3 镜像标签管理

镜像标签（Tag）是镜像版本管理的重要手段：

- 避免使用 `latest` 标签，它可能导致不可重现的构建
- 使用语义化版本号（如 `v1.2.3`）或 Git commit SHA 作为标签
- 生产环境应该锁定具体的镜像摘要（digest），如 `image@sha256:abc123...`

## 3. Docker Compose 编排

### 3.1 基本配置

Docker Compose 用于定义和运行多容器应用。通过 `docker-compose.yml` 文件声明服务、网络和存储卷：

```yaml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - DB_HOST=postgres
    depends_on:
      postgres:
        condition: service_healthy
    restart: unless-stopped

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: argus
      POSTGRES_USER: argus
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U argus"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
```

### 3.2 网络配置

Docker Compose 自动为每个项目创建独立的网络，服务之间可以通过服务名互相访问。对于需要跨主机通信的场景，可以使用 overlay 网络：

```yaml
networks:
  backend:
    driver: overlay
    attachable: true
```

### 3.3 健康检查

健康检查（Healthcheck）用于检测容器是否正常运行。Docker 会定期执行健康检查命令，根据结果更新容器状态：

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 40s
```

`depends_on` 配合 `condition: service_healthy` 可以确保依赖服务完全启动后再启动当前服务。

## 4. 生产环境部署

### 4.1 资源限制

生产环境中必须为容器设置资源限制，防止某个容器耗尽宿主机资源：

```yaml
services:
  app:
    deploy:
      resources:
        limits:
          cpus: '2.0'
          memory: 2G
        reservations:
          cpus: '0.5'
          memory: 512M
```

Java 应用的内存设置应该与容器内存限制协调：
```dockerfile
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

`-XX:MaxRAMPercentage=75.0` 表示 JVM 堆内存最多使用容器内存限制的 75%，剩余 25% 留给堆外内存、线程栈和操作系统。

### 4.2 日志管理

容器的日志默认写入 stdout 和 stderr，Docker 通过日志驱动（Log Driver）将日志转发到不同的目的地。

推荐使用 JSON 文件日志驱动，并配置日志轮转：
```yaml
services:
  app:
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "5"
```

对于集中式日志管理，可以使用 ELK（Elasticsearch + Logstash + Kibana）或 Loki + Grafana 方案。

### 4.3 安全最佳实践

- **非 root 用户运行**：在 Dockerfile 中创建专用用户，避免以 root 身份运行应用
- **只读文件系统**：通过 `read_only: true` 防止容器修改文件系统
- **镜像扫描**：使用 Trivy 或 Snyk 扫描镜像中的安全漏洞
- **Secret 管理**：使用 Docker Secrets 或外部密钥管理服务，避免在环境变量中存储敏感信息

```dockerfile
# 创建非 root 用户
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
```

### 4.4 滚动更新

Docker Swarm 和 Kubernetes 都支持滚动更新策略，在不中断服务的情况下逐步替换旧版本容器：

```yaml
deploy:
  update_config:
    parallelism: 1
    delay: 30s
    failure_action: rollback
    order: start-first
```

`start-first` 策略先启动新容器再停止旧容器，确保服务始终有可用实例。

## 5. 容器网络深度解析

### 5.1 网络模式

Docker 支持多种网络模式：
- **bridge**：默认模式，容器通过虚拟网桥通信
- **host**：容器直接使用宿主机的网络栈，性能最好但隔离性差
- **overlay**：跨主机通信，用于 Docker Swarm 集群
- **macvlan**：为容器分配独立的 MAC 地址，使容器像物理机一样存在于网络中

### 5.2 端口映射

端口映射将容器内部端口暴露到宿主机。需要注意：
- `-p 8080:8080` 表示将宿主机的 8080 端口映射到容器的 8080 端口
- `-p 127.0.0.1:8080:8080` 只允许本机访问，适合不需要外部访问的服务
- 生产环境建议使用反向代理（如 Nginx、Traefik）统一管理端口

## 6. 存储卷管理

### 6.1 数据持久化

容器的文件系统是临时的，容器删除后数据会丢失。使用 Docker Volume 可以实现数据持久化：

```yaml
volumes:
  - postgres_data:/var/lib/postgresql/data  # 命名卷
  - ./config:/app/config:ro                 # 绑定挂载（只读）
  - /tmp:/tmp:tmpfs                         # tmpfs 挂载
```

### 6.2 备份策略

对于数据库等有状态服务，应该定期备份数据卷：

```bash
# 备份 PostgreSQL 数据
docker run --rm -v postgres_data:/data -v $(pwd):/backup \
    alpine tar czf /backup/postgres_backup_$(date +%Y%m%d).tar.gz -C /data .
```

备份文件应该存储在异地（如对象存储），并定期验证备份的可恢复性。
