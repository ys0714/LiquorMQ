package org.liquor.liquormq.raft.node;

import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.grpc.*;
import org.liquor.liquormq.raft.enums.RaftState;
import org.liquor.liquormq.raft.node.state.*;
import org.liquor.liquormq.raft.statemachine.StateMachine;
import org.liquor.liquormq.raft.storage.RaftLog;
import org.liquor.liquormq.raft.storage.RaftMetaStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RaftNode {

    // --- 状态模式委托 ---
    private volatile NodeState nodeState;
    @Getter
    private LogReplicator logReplicator;

    // --- 持久状态 ---
    private final AtomicLong currentTerm = new AtomicLong(0);
    private final AtomicInteger votedFor = new AtomicInteger(-1);

    // --- 依赖组件 ---
    @Autowired @Getter
    private RaftLog raftLog;
    @Autowired
    private StateMachine stateMachine;
    @Autowired @Getter
    private RaftProperties raftProperties;
    @Autowired
    private RaftMetaStorage metaStorage;

    // --- 易变状态 ---
    @Getter
    private volatile long commitIndex = 0;
    private volatile long lastApplied = 0;

    // --- 节点信息 ---
    private volatile int leaderId = -1;
    private long lastStateChangeTime = System.currentTimeMillis();
    @Getter
    private int myId;
    @Getter
    private final List<RaftPeer> peers = new ArrayList<>();

    // --- 调度器 ---
    @Getter
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private ScheduledFuture<?> electionTimeoutTask;

    @PostConstruct
    public void init() {
        this.myId = raftProperties.getNodeId();
        this.logReplicator = new LogReplicator(this, raftLog);

        // 初始状态为 Follower
        this.nodeState = new FollowerState(this);

        // 加载元数据
        org.liquor.liquormq.raft.storage.RaftMetadata meta = metaStorage.load();

        // 修复：检测数据丢失
        if (raftLog.getLastLogIndex() == 0 && meta.getCurrentTerm() > 0) {
            log.warn("检测到日志数据丢失 (可能是 InMemoryLog 重启) 但存在旧任期 (Term={})。正在重置任期为 0...", meta.getCurrentTerm());
            this.currentTerm.set(0);
            this.votedFor.set(-1);
            persistMetadata();
        } else {
            this.currentTerm.set(meta.getCurrentTerm());
            this.votedFor.set(meta.getVotedFor());
        }

        for (RaftProperties.PeerConfig peerConfig : raftProperties.getPeers()) {
             if (peerConfig.getId() != myId) {
                 peers.add(new RaftPeer(peerConfig.getId(), peerConfig.getHost(), peerConfig.getPort()));
             }
        }
        log.info("RaftNode 初始化完成，等待应用启动...");
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        log.info("应用完全启动，RaftNode 开始运行...");
        // 初始启动，使用 6倍因子
        resetElectionTimeout(6.0);
    }

    @PreDestroy
    public void stop() {
        if (nodeState != null) nodeState.stop();
        scheduler.shutdown();
        peers.forEach(RaftPeer::shutdown);
    }

    // --- 已对外暴露的 API (保持兼容) ---

    public synchronized VoteResponse handleRequestVote(VoteRequest request) {
        return nodeState.handleRequestVote(request);
    }

    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        return nodeState.handleAppendEntries(request);
    }

    public boolean propose(String command) {
        if (nodeState.getType() != RaftState.LEADER) {
            return false;
        }

        long index = getLastLogIndex() + 1;
        LogEntry entry = LogEntry.newBuilder()
                .setTerm(currentTerm.get())
                .setIndex(index)
                .setCommand(ByteString.copyFromUtf8(command))
                .build();

        raftLog.append(entry);
        // Leader 自己的 matchIndex
        logReplicator.getMatchIndex().put(myId, index);
        log.info("Leader 提议命令，索引 {}: {}", index, command);

        logReplicator.sendHeartbeats();

        return true;
    }

    public RaftNodeStatus getNodeStatus() {
        long lastIdx = getLastLogIndex();
        long startShowIndex = Math.max(1, lastIdx - 10 + 1);
        List<LogEntry> recentEntries = raftLog.getEntriesFrom(startShowIndex);

        List<RaftNodeStatus.LogEntryInfo> simplifiedLogs = recentEntries.stream()
                .map(e -> RaftNodeStatus.LogEntryInfo.builder()
                        .index(e.getIndex())
                        .term(e.getTerm())
                        .command(e.getCommand().toStringUtf8())
                        .build())
                .collect(Collectors.toList());

        return RaftNodeStatus.builder()
                .nodeId(myId)
                .state(nodeState.getType())
                .currentTerm(currentTerm.get())
                .votedFor(votedFor.get())
                .commitIndex(commitIndex)
                .lastApplied(lastApplied)
                .lastLogIndex(lastIdx)
                .lastLogTerm(getLastLogTerm())
                .nextIndices(nodeState.getType() == RaftState.LEADER ? new java.util.HashMap<>(logReplicator.getNextIndex()) : null)
                .matchIndices(nodeState.getType() == RaftState.LEADER ? new java.util.HashMap<>(logReplicator.getMatchIndex()) : null)
                .logSize((int)lastIdx)
                .recentLogEntries(simplifiedLogs)
                .build();
    }

    // --- State & Context 方法 (供状态类使用) ---

    public RaftState getState() {
        return nodeState.getType();
    }

    public long getCurrentTerm() {
        return currentTerm.get();
    }

    public int getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(int leaderId) {
        this.leaderId = leaderId;
    }

    public void setCommitIndex(long index) {
        this.commitIndex = index;
    }

    public void persistMetadata() {
        metaStorage.save(currentTerm.get(), votedFor.get());
    }

    public long incrementTermAndGet() {
        long t = currentTerm.incrementAndGet();
        persistMetadata();
        return t;
    }

    public long getLastLogIndex() {
        return raftLog.getLastLogIndex();
    }

    public long getLastLogTerm() {
        return raftLog.getLastLogTerm();
    }

    // 状态切换核心方法
    public synchronized void convert(RaftState newStateEnum) {
        if (nodeState.getType() == newStateEnum) return;

        RaftState oldState = nodeState.getType();
        long duration = System.currentTimeMillis() - lastStateChangeTime;
        log.info("状态流转: {} (持续 {} ms) -> {}", oldState, duration, newStateEnum);
        lastStateChangeTime = System.currentTimeMillis();

        // 1. 停止旧状态
        nodeState.stop();

        // 2. 创建新状态
        switch (newStateEnum) {
            case FOLLOWER:
                this.nodeState = new FollowerState(this);
                this.leaderId = -1; // 转为 Follower 时先重置 LeaderID，等待心跳确认
                break;
            case CANDIDATE:
                this.nodeState = new CandidateState(this);
                break;
            case LEADER:
                this.nodeState = new LeaderState(this);
                break;
            default:
                throw new IllegalArgumentException("Unknown state: " + newStateEnum);
        }

        // 3. 启动新状态
        nodeState.start();
    }

    // 辅助方法：更新任期并转为 Follower
    public void updateTermAndConvert(long newTerm) {
        currentTerm.set(newTerm);
        votedFor.set(-1);
        persistMetadata();
        convert(RaftState.FOLLOWER);
    }

    // 辅助方法：投票判断
    public boolean canVoteFor(int candidateId) {
        int v = votedFor.get();
        return (v == -1 || v == candidateId);
    }

    public void voteFor(int candidateId) {
        votedFor.set(candidateId);
        persistMetadata();
    }

    public boolean isLogUpToDate(long candidateLastLogIndex, long candidateLastLogTerm) {
        long myLastLogTerm = getLastLogTerm();
        long myLastLogIndex = getLastLogIndex();

        if (candidateLastLogTerm != myLastLogTerm) {
            return candidateLastLogTerm > myLastLogTerm;
        }
        return candidateLastLogIndex >= myLastLogIndex;
    }

    // 状态机应用
    public void applyLog() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = raftLog.getEntry(lastApplied);
            if (entry != null && !entry.getCommand().isEmpty()) {
                stateMachine.apply(entry.getCommand().toByteArray());
            }
        }
    }

    public void resetElectionTimeout() {
        resetElectionTimeout(1.0);
    }

    // 暴露给状态使用 (Start/Reset)
    public void resetElectionTimeout(double factor) {
        if (electionTimeoutTask != null && !electionTimeoutTask.isDone()) {
            electionTimeoutTask.cancel(false);
        }
        long min = (long) (raftProperties.getElectionTimeoutMin() * factor);
        long max = (long) (raftProperties.getElectionTimeoutMax() * factor);
        long delay = min + (long) (Math.random() * (max - min));

        log.info("启动选举定时器: {} ms 后触发", delay);
        // 注意：这里需要再次检查状态，避免在 Leader 状态下触发
        electionTimeoutTask = scheduler.schedule(() -> {
            synchronized (this) {
                // 如果当前不是 Leader，则触发选举
                if (getState() != RaftState.LEADER) {
                     // 触发选举 -> 转为 Candidate
                     convert(RaftState.CANDIDATE);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}

