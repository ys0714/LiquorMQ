# LiquorMQ 架构设计

## 概览
LiquorMQ 是一个基于 Raft 共识算法的分布式高可用消息队列系统，支持多节点集群部署。系统通过 gRPC 实现节点间通信及客户端交互。

## 核心组件

### 1. Broker (代理)
Broker 是系统的核心服务器组件，负责处理客户端的消息生产和消费请求。
- **角色**: Follower（跟随者）, Candidate（候选人）, Leader（领导者）。
- **功能**:
  - 接收客户端的生产/消费请求。
  - 通过 Raft 协议维护集群一致性。
  - 管理消息存储和分发。

### 2. Raft 共识层
Raft 层负责实现分布式一致性，确保日志复制和选主过程的正确性。
- **RaftNode**: Raft 算法的核心实现，管理节点状态（角色、任期、投票等）。
- **LogModule**: 负责日志的持久化存储和检索。
- **ConsensusModule**: 实现选主和日志复制逻辑，处理 RequestVote 和 AppendEntries RPC。
- **StateMachine**: 状态机模块，将已提交的日志应用到业务逻辑（如消息存储）。

### 3. 网络通信层 (gRPC)
- **RaftService**: gRPC 服务端实现，接收其他节点的 RPC 请求。
- **RaftClient**: gRPC 客户端封装，用于向其他节点发送 RPC 请求。

### 4. 存储层
- **MessageStore**: 消息存储模块，负责持久化消息数据，支持 KV 存储或文件系统。

## 技术栈
- **语言**: Java 17
- **框架**: Spring Boot
- **RPC 框架**: gRPC + Protobuf
- **构建工具**: Maven

## 实现细节

### 依赖配置 (pom.xml)
- 添加 `net.devh:grpc-server-spring-boot-starter` 依赖，用于 gRPC 服务端集成。
- 添加标准的 gRPC 和 Protobuf 库。
- 配置 `protobuf-maven-plugin` 插件，用于生成 gRPC 和 Protobuf 的 Java 类。

### 协议定义 (src/main/proto/raft.proto)
- 定义 `VoteRequest` 和 `VoteResponse`，用于 Leader 选举。
- 定义 `AppendEntriesRequest` 和 `AppendEntriesResponse`，用于日志复制和心跳。
- 定义 `LogEntry`，用于封装状态机命令。

### Raft 核心组件
- **RaftState**: 枚举类，定义 FOLLOWER, CANDIDATE, LEADER 三种状态。
- **RaftPeer**: 封装 gRPC 客户端，用于与其他节点通信。
- **RaftNode**: Raft 算法的核心实现，负责选主、日志复制、心跳维护等。
- **RaftServiceImpl**: gRPC 服务端实现，处理传入的 RPC 请求并委托给 RaftNode。

### 存储层
- **LogModule**: 提供日志的追加、读取和持久化功能。
- **MessageStore**: 负责消息的存储和检索，支持状态机应用。

