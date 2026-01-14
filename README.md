# LiquorMQ 架构设计与开发指南

## 项目概览
LiquorMQ 是一个基于 Raft 共识算法的分布式高可用消息队列系统，目前处于原型开发阶段。系统旨在通过 gRPC 实现节点间的高效通信，并保证集群的数据强一致性。

## 核心功能与状态

### 已实现功能
1.  **Leader 选举 (Leader Election)**:
    - 节点实现了 Follower、Candidate、Leader 状态机转换。
    - 支持 RequestVote RPC 处理及投票逻辑。
    - 具备随机化的选举超时机制以避免选票瓜分。
2.  **心跳机制 (Heartbeat)**:
    - Leader 节点定期向 Followers 发送 AppendEntries RPC（心跳）。
    - 节点间能够维持连接并感知 Leader 存活状态。
    - **注**: 已添加日志输出以显式观察心跳交互。

### 待完善功能
1.  **日志复制 (Log Replication)**:
    - 当前 AppendEntries 实现了基础框架，但日志同步的完整逻辑（如 `nextIndex`, `matchIndex` 的动态更新及日志冲突解决）尚待完善。
    - 消息的持久化存储目前使用 `InMemoryRaftLog`（内存存储），需对接磁盘存储。
2.  **状态机应用 (State Machine Application)**:
    - 消息应用到业务状态机的流程依赖于日志复制的提交索引更新，需同步完善。

## 系统架构

### 1. Broker (代理节点)
系统的核心服务节点，集成了 Raft 共识模块与消息处理模块。
- **端口配置**:
    - Web 服务端口: `8080`, `8081`, `8082`
    - gRPC 通信端口: `9090`, `9091`, `9092`

### 2. Raft 共识层
- **RaftNode**: `org.liquor.liquormq.raft.node.RaftNode`
    - 核心控制器，负责状态流转、定时器管理（选举/心跳）。
- **RaftLog**: `org.liquor.liquormq.raft.storage.RaftLog`
    - 日志接口，目前实现为 `InMemoryRaftLog`。
- **StateMachine**: 状态机接口，用于处理已提交的日志命令。

### 3. 通信层 (gRPC)
基于 Protobuf 定义 (`raft.proto`) 生成服务代码。
- **RaftService**: 处理收到的 RPC 请求 (`requestVote`, `appendEntries`)。
- **RaftPeer**: 封装对其他节点的 gRPC 客户端调用。

## 开发与测试

### 常见问题与修复
- **Maven 编译失败**:
    - 之前可能遇到 `javax.annotation.Generated` 找不到符号的问题。
    - **修复**: 已更新 `pom.xml` 将 Spring Boot 版本升级至 `3.2.1` 并完善了 Lombok 配置，确保依赖正确解析。
- **心跳不可见**:
    - 之前控制台无心跳日志。
    - **修复**: 已在 `RaftNode.java` 中添加 `INFO` 级别的日志输出，分别记录发送和接收心跳的事件。

### 如何启动与测试
1.  **简易启动 (Windows)**:
    - 根目录下提供了 `start-cluster.bat` 脚本。
    - 双击即可一键启动 3 个 CMD 窗口，分别运行 3 个 Broker 实例。
    - 这是比手动 `java -jar` 更简便的方式。
2.  **IDEA 开发调试**:
    - 请参考 `TESTING.md` 中的 "选项 2" 配置 IDEA 的 Compound Run Configuration，可在一个界面管理 3 个节点。

## 技术栈
- **语言**: Java 17
- **核心框架**: Spring Boot 3.2.1
- **RPC**: gRPC 1.58.0 + Protobuf 3.24.0
- **构建**: Maven
