# LiquorMQ

> 一个基于Raft 共识算法构建的强一致性分布式消息队列原型。

LiquorMQ 旨在提供一个易于理解、高度模块化的分布式系统实现，用于学习和研究 Raft 算法以及分布式强一致性系统的构建细节。

## 核心特性

*   **Raft 共识算法实现**:
    *   **Leader 选举 (Election)**: 完整的选举流程，支持随机超时、Term 逻辑及候选人投票限制。
    *   **日志复制 (Log Replication)**: 支持 AppendEntries RPC，实现日志一致性检查和冲突截断。
    *   **安全性 (Safety)**: 遵循 Raft 论文的安全性规则（如 "Log Matching Property", "Election Restriction"）。
    *   **集群稳定优化**: 包含针对启动阶段和网络波动的工程优化（如启动预热期）。
*   **通信层**:
    *   基于 **gRPC** (Protobuf) 的高性能节点间通信。
*   **可观测性**:
    *   提供 HTTP API 用于查看节点内部状态（Term, Role, CommitIndex, Log Summary 等）。
*   **存储**:
    *   **InMemoryRaftLog**: 目前采用内存日志存储（开发原形阶段）。
    *   **RaftMetaStorage**: 关键元数据（Term, VotedFor）文件持久化。

## 技术栈

*   **语言**: Java 17
*   **框架**: Spring Boot 4.0.1
*   **通信**: gRPC 1.58.0 + Protobuf 3.24.0
*   **构建工具**: Maven

## 快速开始

### 1. 环境准备
确保已安装 JDK 17+ 和 Maven。

### 2. 编译项目
```bash
mvn clean package -DskipTests
```

### 3. 运行集群 (本地模拟)
你可以在本地启动多个实例来模拟一个 Raft 集群。假设我们在 `application.properties` 中预定义了 5 个 Peer 的配置 (端口 9091-9095)。

**启动节点 1**:
```bash
java -jar target/liquorMQ-0.0.1-SNAPSHOT.jar --server.port=8081 --grpc.server.port=9091 --liquormq.raft.node-id=1
```

**启动节点 2**:
```bash
java -jar target/liquorMQ-0.0.1-SNAPSHOT.jar --server.port=8082 --grpc.server.port=9092 --liquormq.raft.node-id=2
```

**启动节点 3**:
```bash
java -jar target/liquorMQ-0.0.1-SNAPSHOT.jar --server.port=8083 --grpc.server.port=9093 --liquormq.raft.node-id=3
```

*(以此类推启动节点 4 和 5)*

> **注意**: 启动后，节点会自动开始选举。你可以观察控制台日志看到 `当选为 LEADER` 或 `成为 FOLLOWER` 的信息。

## API 接口

LiquorMQ 提供了 HTTP 接口来与集群交互。

### 1. 查看节点状态
查看当前节点的角色、任期、日志索引等详细信息。

*   **URL**: `GET /api/raft/status`
*   **示例**:
    ```bash
    curl http://localhost:8081/api/raft/status
    ```
*   **响应**:
    ```json
    {
      "nodeId": 1,
      "state": "LEADER",
      "currentTerm": 44,
      "votedFor": 1,
      "commitIndex": 120,
      "lastLogIndex": 125,
      ...
    }
    ```

### 2. 提议命令 (客户端写请求)
向集群发送一条数据（命令）。该请求必须发送给 **LEADER** 节点，否则会被拒绝（目前未实现自动转发）。

*   **URL**: `POST /api/raft/send`
*   **Body**: 纯文本命令字符串
*   **示例**:
    ```bash
    curl -X POST -d "set key value" http://localhost:8081/api/raft/send
    ```

## 项目结构

```
src/main/java/org/liquor/liquormq/raft/
├── controller/       # HTTP 接口层
├── node/             # Raft 核心节点逻辑 (RaftNode, Timer, RPC Handler)
├── storage/          # 日志与元数据存储接口及实现
├── statemachine/     # 状态机定义
├── grpc/             # gRPC 生成代码与服务实现
└── enums/            # 状态枚举 (Role, Status)
```

## 开发进度与计划

- [x] **Leader 选举**: 包含随机超时与 Split Vote 处理。
- [x] **心跳保活**: Leader 向 Follower 发送 KeepAlive。
- [x] **日志复制** (基础): 单向日志追加同步。
- [x] **一致性检查**: `prevLogIndex/Term` 校验与回退。
- [x] **启动优化**: 防止节点重启时的网络冷启动导致不必要的 Re-election。
- [ ] **日志持久化**: 实现基于 RocksDB 或文件的 WAL (Write Ahead Log)。
- [ ] **快照机制 (Snapshot)**: 日志压缩与 InstallSnapshot RPC。
- [ ] **成员变更**: 动态添加/移除节点。


