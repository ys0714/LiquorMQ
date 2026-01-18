# 🥃 LiquorMQ

[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/technologies/downloads/#java17)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.0-green.svg)](https://spring.io/projects/spring-boot)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**LiquorMQ** 是一个基于 **Raft 共识算法** 原生构建的分布式强一致性消息队列（Distributed Strong Consistency Message Queue）。

不同于 Kafka 追求极致吞吐量的 Hybrid (AP) 设计，LiquorMQ 致力于探索 **CP (Consistency & Partition Tolerance)** 模型在消息中间件中的深度实践。它保证在 $f < n/2$ 的节点故障下，已提交（Committed）的消息**绝对不丢失**且**严格有序**。

> ⚠️ **注意**: 本项目目前处于原型（Prototype）阶段，核心算法遵循 Raft 论文标准实现，旨在作为分布式系统一致性研究的参考范例。

---

## ✨ 核心特性 (Key Features)

- **🛡️ 强一致性 (Strong Consistency)**
  全链路集成 Raft 算法。从 Leader 选举到日志复制，均经过严格的 Term 与 Index 校验，杜绝“脑裂”与数据覆盖。
  
- **💾 数据持久化 (Persistence)**
  实现了基于 Append-Only Log 的文件存储引擎 (`FileBasedRaftLog`)。即使节点宕机重启，也能通过重放日志恢复状态，不再依赖内存。

- **⚡ 高可用性 (High Availability)**
  支持多节点集群部署。Leader 故障自动检测与毫秒级故障转移（Failover），确保服务的高可用性。

- **🔗 高性能通信**
  节点间均采用 **gRPC** (Protobuf) 进行通信，保证了低延迟与跨语言扩展的可能性。

- **🔍 可观测性**
  内置 HTTP 监控接口，实时查看 Leader 状态、Term 届数、CommitIndex 及日志水位。

---

## 🛠️ 技术栈 (Tech Stack)

| 组件 | 技术 | 说明 |
| :--- | :--- | :--- |
| **Language** | Java 17 | 核心开发语言 |
| **Framework** | Spring Boot 3.x | 容器与应用启动 |
| **RPC** | gRPC 1.58 + Protobuf | 节点间高效通信 |
| **Build** | Maven | 依赖管理与构建 |
| **Storage** | Java NIO | 文件系统直接交互 |

---

## 🚀 快速开始 (Quick Start)

### 环境依赖
- JDK 17+
- Maven 3.6+

### 1. 编译项目
```bash
mvn clean package -DskipTests
```

### 2. 启动集群 (Local Cluster)
为了演示 Raft 的共识特性，建议在本地启动 3 个实例组成最小集群。

**启动节点 1 (Bootstrap Node)**
```bash
java -jar target/liquorMQ-0.0.1-SNAPSHOT.jar \
  --server.port=8081 \
  --grpc.server.port=9091 \
  --liquormq.raft.node-id=1
```

**启动节点 2**
```bash
java -jar target/liquorMQ-0.0.1-SNAPSHOT.jar \
  --server.port=8082 \
  --grpc.server.port=9092 \
  --liquormq.raft.node-id=2
```

**启动节点 3**
```bash
java -jar target/liquorMQ-0.0.1-SNAPSHOT.jar \
  --server.port=8083 \
  --grpc.server.port=9093 \
  --liquormq.raft.node-id=3
```

> **Tip**: 启动后，节点会自动进行 Leader 选举。你可以通过日志观察到 `当选为 LEADER` 或 `成为 FOLLOWER` 的信息。

---

## 🔌 API 使用 (API Usage)

### 查看节点状态
`GET /api/raft/status`

获取当前节点视角下的集群状态。

```bash
curl http://localhost:8081/api/raft/status
```
**Response:**
```json
{
  "nodeId": 1,
  "state": "LEADER",
  "currentTerm": 44,
  "votedFor": 1,
  "commitIndex": 120,
  "lastLogIndex": 125
}
```

### 发送数据 (Write Data)
`POST /api/raft/send`

向集群提交一条命令（Log Entry）。**注意：只有 Leader 节点能处理写请求**。

```bash
curl -X POST -d "SET key=value" http://localhost:8081/api/raft/send
```

---

## 🗺️ 路线图 (Roadmap)

- [x] **Core Raft**
    - [x] Leader Election (Random Timeout, Split Vote Handling)
    - [x] Log Replication (AppendEntries RPC)
    - [x] Safety Rules (Log Matching, Term Check)
- [x] **Persistence**
    - [x] File-based Log Storage (WAL)
    - [x] Metadata Storage (Term, VotedFor)
- [x] **Optimization**
    - [x] Pre-Vote / Startup Warmup
    - [x] Concurrency Safety (Synchronized State Transitions)
- [ ] **Advanced Features**
    - [ ] Log Compaction (Snapshotting)
    - [ ] Dynamic Membership Change (Add/Remove Node)
    - [ ] Multi-Raft / Batching (Performance)

---

## 🤝 贡献 (Contributing)

欢迎提交 Issue 和 Pull Request！本项目适合作为学习 Raft 算法的练手项目。

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 提交 Pull Request

## 📄 许可证 (License)

Distributed under the Apache 2.0 License. See `LICENSE` for more information.

