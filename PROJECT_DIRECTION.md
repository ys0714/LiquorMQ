# LiquorMQ 项目演进方向深度分析与简历指导

## 1. 现状回顾与核心差异

你目前的发现非常准确，这触及了分布式系统设计的核心权衡：**CAP 理论**。

*   **Kafka (KRaft + ISR)**:
    *   **架构**: 控制面用 Raft (强一致)，数据面用 ISR (主备复制).
    *   **目的**: 极致的吞吐量。Raft 的 Append 流程（RTT + 磁盘刷盘）对于海量日志数据来说可能太慢了，ISR 允许在这个牺牲一点点可用性或一致性之间灵活配置 (`acks=all` vs `acks=1`)。
    *   **分类**: 这是一个偏向 **AP** (高可用/高性能) 但在特定配置下能达到 CP 的系统。

*   **LiquorMQ (现在的状态)**:
    *   **架构**: 数据直接走 Raft。
    *   **类似业界产品**: **RabbitMQ (Quorum Queues)**, **NATS JetStream**, **TiKV**, **Etcd**.
    *   **特点**: **CP (强一致性 + 分区容错)**。每一条消息都保证落盘并达成共识才返回成功。绝对不丢数据，但在极高并发下受限于 Leader 的 IO 能力。

---

## 2. 两条演进路线：你的简历想要讲什么故事？

为了让这个项目在简历上发光，我有两个建议方向。这取决于你想展现哪方面的能力。

### 🚀 路线 A：深钻共识算法 —— "Multi-Raft 分布式存储" (推荐)

**核心理念**: 坚持全链路 Raft，但解决 Raft 的性能瓶颈。这也是 **TiKV** (TiDB 的存储层) 和 **CockroachDB** 的做法。

*   **痛点**: 如果整个 Broker 只有一个 Raft Group，那么所有消息都要经过同一个 Leader 排队，这无法利用多核和多磁盘。
*   **解决方案 (Multi-Raft)**:
    *   引入 **Partition (分区)** 概念。
    *   **每个 Partition 是一个独立的 Raft Group**。
    *   单台 Broker 节点上同时运行多个 Raft 实例（例如：Broker 1 是 Partition A 的 Leader，同时是 Partition B 的 Follower）。
*   **简历亮点**:
    1.  **"实现了 Multi-Raft 架构"**: 这种词汇非常抓眼球，表明你懂的不仅仅是基础 Raft，还懂由于共识带来的扩展性问题及解法。
    2.  **"解决了分片热点问题"**: 所有的 Broker 都能做 Leader（服务不同分区），流量被均匀打散。
    3.  **"强一致性消息队列"**: 适用于金融级场景，不同于 Kafka 的日志流。

### ⚙️ 路线 B：复刻 Kafka —— "控制面与数据面分离" (挑战大)

**核心理念**: 把你现在的 Raft 代码封装成 "Controller"，另外写一套 "Store" 做数据复制.

*   **做法**:
    1.  **Limit Raft**: 现在的 Raft 节点只处理 `CreateTopic`, `RegisterBroker` 这类元数据请求。
    2.  **Data Node**: 新建一种节点，它们不跑 Raft，只跑简单的 TCP 数据接收和主从同步 (Primary-Backup)。
    3.  **Coordination**: Controller 告诉 Data Node："你是 Partition 1 的 Leader，他是 Follower"。
*   **简历亮点**:
    1.  **"架构设计能力"**: 展现了你理解为什么要把元数据和数据流分开。
    2.  **"混合一致性模型"**: 同时驾驭 Paxos 类（Raft）和 Primary-Backup 类复制。

---

## 3. 我的建议：坚持路线 A (Multi-Raft + Batching)

为什么不建议路线 B？因为写一个 ISR 复制协议本身工作量很大，而且容易写成 "低配版 Kafka"。
相反，**把 Raft 做到极致**（路线 A）更能体现深度。

### 建议的开发计划 (Roadmap for Resume)

#### 阶段 1: 完善单体 Raft (当前阶段)
*   完成日志持久化 (RocksDB/File)。
*   完成快照 (Snapshot) —— *这是简历必问点，因为日志无限增长是不可能的。*

#### 阶段 2: 性能优化 (体现对系统瓶颈的理解)
*   **Batching (批量提交)**: 作为一个 MQ，不可以来一条消息就做一次 Raft RPC。要攒几毫秒或是攒几KB一起发。
    *   *简历语*: "通过 Batching 机制将 TPS 提升了 10 倍"。
*   **Pipeline (流水线)**: Leader 发送 AppendEntries 不需要等上一次回复就可以发下一次。

#### 阶段 3: 架构升级 (Multi-Raft)
*   在 `RaftNode` 外层套一个 `RaftGroupManager`。
*   消息带上 `PartitionID`，路由到不同的 Raft 状态机。

---

## 4. 如何在面试中通过本项目展现 "分布式一致性理解"

当面试官问你 "你为什么用 Raft 做消息同步，Kafka 都不用？" 时，你可以这样回答（**满分答案**）：

> "我深入研究过 Kafka 的架构，我知道 Kafka 使用 ISR 是为了在不需要强一致性共识的场景下换取极致吞吐。
>
> 但我的项目 LiquorMQ **定位不同**。我将其设计为一个 **金融级强一致消息队列**（类似于 RabbitMQ Quorum Queues）。
> 在这个设计中，由于要求 '消息绝对不丢' 和 '严格有序'，我选择了全链路 Raft。
>
> 同时也为了解决 Raft 的单点性能问题，我规划了 **Multi-Raft** 架构（或实现了 Batching），利用数据分片来横向扩展吞吐能力。这让我在这个项目中完整实践了从**共识算法实现**到**分布式系统扩展性治理**的全过程。"

---

### 总结
你现在的代码非常有价值，不需要推倒重来。
**建议：** 你的下一个目标应该是 **"日志持久化"** 和 **"批量提交 (Batching)"**。这两点做完，就是一个非常完整的分布式强一致存储 Demo 了。

