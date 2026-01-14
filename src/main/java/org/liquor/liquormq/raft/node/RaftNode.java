package org.liquor.liquormq.raft.node;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.grpc.*;
import org.liquor.liquormq.raft.statemachine.StateMachine;
import org.liquor.liquormq.raft.enums.RaftState;
import org.liquor.liquormq.raft.storage.RaftLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RaftNode {

    // 节点当前状态 (FOLLOWER, CANDIDATE, LEADER)
    private volatile RaftState state = RaftState.FOLLOWER;

    // 持久状态 (在所有服务器上)
    // 服务器看到的最新任期号 (初始为 0，单调递增)
    private final AtomicLong currentTerm = new AtomicLong(0);
    // 在当前任期内收到选票的候选人 ID (如果没有则为 -1)
    private final AtomicInteger votedFor = new AtomicInteger(-1);

    @Autowired
    private RaftLog raftLog;

    @Autowired
    private StateMachine stateMachine;

    @Autowired
    private RaftProperties raftProperties;

    @Autowired
    private org.liquor.liquormq.raft.storage.RaftMetaStorage metaStorage;

    // 易变状态 (在所有服务器上)
    // 已知已提交的最高日志条目的索引 (初始为 0，单调递增)
    private volatile long commitIndex = 0;
    // 已经应用到状态机的最高日志条目的索引 (初始为 0，单调递增)
    private volatile long lastApplied = 0;

    // 易变状态 (仅在领导者上)
    // 对于每个服务器，需要发送给它的下一个日志条目的索引 (初始为领导者最后的日志索引 + 1)
    private final ConcurrentMap<Integer, Long> nextIndex = new ConcurrentHashMap<>();
    // 对于每个服务器，已知的已经复制到该服务器的最高日志条目的索引 (初始为 0，单调递增)
    private final ConcurrentMap<Integer, Long> matchIndex = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    // 选举超时任务
    private ScheduledFuture<?> electionTimeoutTask;
    // 心跳任务
    private ScheduledFuture<?> heartbeatTask;

    // 配置
    private int myId;
    private final List<RaftPeer> peers = new ArrayList<>();

    @PostConstruct
    public void init() {
        this.myId = raftProperties.getNodeId();

        // 加载持久化元数据
        org.liquor.liquormq.raft.storage.RaftMetadata meta = metaStorage.load();
        this.currentTerm.set(meta.getCurrentTerm());
        this.votedFor.set(meta.getVotedFor());

        for (RaftProperties.PeerConfig peerConfig : raftProperties.getPeers()) {
             // 不要将自己添加到对等节点列表
             if (peerConfig.getId() != myId) {
                 peers.add(new RaftPeer(peerConfig.getId(), peerConfig.getHost(), peerConfig.getPort()));
             }
        }

        // 移除 init 中的 resetElectionTimeout，防止在 Spring 容器启动过程中（端口未打开前）触发选举
        // resetElectionTimeout();
        log.info("RaftNode 初始化完成，等待应用启动...");
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        log.info("应用完全启动 (Ports Bound)，RaftNode 开始运行...");
        // 第一次启动给予极大的超时范围 (3000ms - 5000ms)，确保有足够时间建立连接并收到现有 Leader 的心跳
        // 这不是违反 Raft，而是为了适应发生物理网络连接建立延迟时的工程优化
        resetElectionTimeout(6.0);
    }

    // 持久化 currentTerm 和 votedFor
    private void persistMetadata() {
        metaStorage.save(currentTerm.get(), votedFor.get());
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdown();
        peers.forEach(RaftPeer::shutdown);
    }

    // --- RPC 处理程序 (由 GrpcService 调用) ---

    // 处理来自候选人的投票请求
    public synchronized VoteResponse handleRequestVote(VoteRequest request) {
        log.debug("收到来自候选人 {} 的投票请求 (Term: {})", request.getCandidateId(), request.getTerm());

        boolean voteGranted = false;
        long term = currentTerm.get();
        long requestTerm = request.getTerm();

        // 1. 如果请求的任期小于当前任期，拒绝投票
        if (requestTerm < term) {
            return VoteResponse.newBuilder().setTerm(term).setVoteGranted(false).build();
        }

        // 如果请求的任期大于当前任期，更新自己的任期并转为 Follower
        if (requestTerm > term) {
            currentTerm.set(requestTerm);
            votedFor.set(-1);
            persistMetadata();
            becomeFollower(requestTerm);
            // 注意：这里成为 Follower 后，代码继续向下执行，评估是否给这个新任期的候选人投票
        }

        // 2. 如果 (未投票 || 已经投给了该候选人)，并且候选人的日志至少和自己一样新，则投票
        if ((votedFor.get() == -1 || votedFor.get() == request.getCandidateId()) &&
            isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm())) {

            voteGranted = true;
            votedFor.set(request.getCandidateId());
            persistMetadata();
            resetElectionTimeout();
            log.info("投票给候选人 {}", request.getCandidateId());
        }

        return VoteResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setVoteGranted(voteGranted)
                .build();
    }

    // 处理来自领导者的追加日志请求 (心跳或日志复制)
    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        long term = currentTerm.get();

        // 1. 如果请求的任期小于当前任期，返回失败
        if (request.getTerm() < term) {
            return AppendEntriesResponse.newBuilder().setTerm(term).setSuccess(false).build();
        }

        // 收到来自当前或更高任期领导者的请求，更新任期并转为跟随者
        if (request.getTerm() >= term) {
            if (request.getTerm() > term) {
                currentTerm.set(request.getTerm());
                votedFor.set(-1);
                persistMetadata();
            }
            becomeFollower(request.getTerm());
            // 如果需要，更新领导者 ID
        }

        resetElectionTimeout(); // 收到有效的心跳/追加请求，重置选举计时器

        // 2. 一致性检查：检查之前的日志条目是否匹配
        if (request.getPrevLogIndex() > 0) {
            long lastLogIndex = getLastLogIndex();
            // 如果 prevLogIndex 超出了我的日志范围
            if (request.getPrevLogIndex() > lastLogIndex) {
                return AppendEntriesResponse.newBuilder().setTerm(currentTerm.get()).setSuccess(false).build();
            }

            // 如果 prevLogIndex 处的日志任期不匹配
            LogEntry prevEntry = raftLog.getEntry(request.getPrevLogIndex());
            if (prevEntry != null && prevEntry.getTerm() != request.getPrevLogTerm()) {
                return AppendEntriesResponse.newBuilder().setTerm(currentTerm.get()).setSuccess(false).build();
            }
        }

        // 3. 处理日志冲突和追加新条目
        List<LogEntry> entries = request.getEntriesList();
        long index = request.getPrevLogIndex();

        if (entries.isEmpty()) {
            log.debug("收到来自领导者 {} 的心跳", request.getLeaderId());
        }

        for (LogEntry entry : entries) {
            index++;
            LogEntry existing = raftLog.getEntry(index);
            if (existing != null) {
                if (existing.getTerm() != entry.getTerm()) {
                    // 4. 发生冲突，删除现有条目及其之后的所有条目
                    raftLog.truncateFrom(index);
                    raftLog.append(entry);
                }
                // 如果任期匹配，我们假设它是相同的条目 (幂等性)
            } else {
                // 5. 追加不存在的新条目
                raftLog.append(entry);
            }
        }

        // 5. 如果 leaderCommit > commitIndex，将 commitIndex 设置为 min(leaderCommit, 新日志条目索引)
        if (request.getLeaderCommit() > commitIndex) {
            commitIndex = Math.min(request.getLeaderCommit(), getLastLogIndex());
            applyLog();
        }

        return AppendEntriesResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setSuccess(true)
                .build();
    }

    // --- 内部逻辑 ---

    private void becomeFollower(long term) {
        state = RaftState.FOLLOWER;
        // votedFor 不在这里重置。必须在任期增加时显式重置。
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel(false);
        }
        resetElectionTimeout();
    }

    private void becomeLeader() {
        if (state != RaftState.CANDIDATE) return;

        log.info("当选为 LEADER，任期 {}", currentTerm.get());
        state = RaftState.LEADER;

        if (electionTimeoutTask != null) {
            electionTimeoutTask.cancel(false);
        }

        // 初始化领导者状态
        for (RaftPeer peer : peers) {
            nextIndex.put(peer.getId(), getLastLogIndex() + 1);
            matchIndex.put(peer.getId(), 0L);
        }

        // 开始发送心跳和复制日志
        startLeaderLoop();
    }

    private synchronized void startElection() {
        if (state == RaftState.LEADER) return;

        log.info("开始选举，针对任期 {}", currentTerm.get() + 1);
        state = RaftState.CANDIDATE;
        long newTerm = currentTerm.incrementAndGet();
        votedFor.set(myId);
        persistMetadata();
        resetElectionTimeout();

        AtomicInteger votesReceived = new AtomicInteger(1); // 投给自己

        for (RaftPeer peer : peers) {
            CompletableFuture.runAsync(() -> {
                try {
                    VoteRequest request = VoteRequest.newBuilder()
                            .setTerm(newTerm)
                            .setCandidateId(myId)
                            .setLastLogIndex(getLastLogIndex())
                            .setLastLogTerm(getLastLogTerm())
                            .build();

                    VoteResponse response = peer.getStub().requestVote(request);

                    if (response.getTerm() > newTerm) {
                        synchronized (RaftNode.this) {
                            long current = currentTerm.get();
                            if (response.getTerm() > current) {
                                currentTerm.set(response.getTerm());
                                votedFor.set(-1);
                                persistMetadata();
                            }
                            becomeFollower(response.getTerm());
                        }
                    } else if (response.getVoteGranted()) {
                        synchronized (RaftNode.this) {
                             // 关键修复：检查此响应是否属于当前任期和当前状态
                             if (currentTerm.get() != newTerm || state != RaftState.CANDIDATE) {
                                 log.debug("忽略过期或无效的投票响应 (Term: {}, Current: {}, State: {})", newTerm, currentTerm.get(), state);
                                 return;
                             }
                        }
                        if (votesReceived.incrementAndGet() > (peers.size() + 1) / 2) {
                            synchronized (RaftNode.this) {
                                // 再次检查，防止并发导致的多次 becomeLeader
                                if (currentTerm.get() == newTerm && state == RaftState.CANDIDATE) {
                                    becomeLeader();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("向节点 {} 请求投票失败: {}", peer.getId(), e.getMessage());
                }
            });
        }
    }

    private void startLeaderLoop() {
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (state != RaftState.LEADER) {
                heartbeatTask.cancel(false);
                return;
            }

            long term = currentTerm.get();
            for (RaftPeer peer : peers) {
                 replicateLog(peer, term);
            }
        }, 0, raftProperties.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
    }

    private void replicateLog(RaftPeer peer, long term) {
        CompletableFuture.runAsync(() -> {
            try {
                long nextIdx = nextIndex.getOrDefault(peer.getId(), 1L);
                long prevLogIndex = nextIdx - 1;
                long prevLogTerm = 0;

                if (prevLogIndex > 0) {
                    LogEntry prevEntry = raftLog.getEntry(prevLogIndex);
                    if (prevEntry != null) {
                        prevLogTerm = prevEntry.getTerm();
                    }
                    // 如果 prevEntry 为 null (被快照截断)，需要 InstallSnapshot (暂未实现)
                }

                // 获取要发送给该跟随者的日志条目
                List<LogEntry> entries = raftLog.getEntriesFrom(nextIdx);

                AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                        .setTerm(term)
                        .setLeaderId(myId)
                        .setPrevLogIndex(prevLogIndex)
                        .setPrevLogTerm(prevLogTerm)
                        .setLeaderCommit(commitIndex)
                        .addAllEntries(entries)
                        .build();

                AppendEntriesResponse response = peer.getStub().appendEntries(request);

                // RPC 成功，重置失败计数
                peer.resetFailures();

                if (response.getTerm() > term) {
                    synchronized (RaftNode.this) {
                        long current = currentTerm.get();
                        if (response.getTerm() > current) {
                            currentTerm.set(response.getTerm());
                            votedFor.set(-1);
                            persistMetadata();
                        }
                        becomeFollower(response.getTerm());
                    }
                    return;
                }

                if (response.getSuccess()) {
                    // 复制成功，更新 nextIndex 和 matchIndex
                    if (!entries.isEmpty()) {
                        long lastEntryIndex = entries.get(entries.size() - 1).getIndex();
                        // nextIndex 设置为最后复制的条目索引 + 1
                        nextIndex.put(peer.getId(), lastEntryIndex + 1);
                        // matchIndex 设置为最后复制的条目索引
                        matchIndex.put(peer.getId(), lastEntryIndex);
                        // 尝试推进 commitIndex
                        updateCommitIndex();
                    }
                } else {
                    // 复制失败（一致性检查失败），回退 nextIndex 并重试
                    // 优化：可以实现快速回退，这里简单减 1
                    long newNextIndex = Math.max(1, nextIdx - 1);
                    nextIndex.put(peer.getId(), newNextIndex);
                }

            } catch (Exception e) {
                int failures = peer.incrementAndGetFailures();
                if (failures == 1) {
                    log.warn("向节点 {} 复制日志失败: {}", peer.getId(), e.getMessage());
                } else if (failures % 50 == 0) { // 每 50 次 (约 5秒) 记录一次警告
                    log.warn("向节点 {} 复制日志持续失败 (已失败 {} 次): {}", peer.getId(), failures, e.getMessage());
                } else {
                    log.debug("向节点 {} 复制日志失败: {}", peer.getId(), e.getMessage());
                }
            }
        });
    }

    private synchronized void updateCommitIndex() {
        // 计算大多数节点已复制的日志索引
        List<Long> indices = new ArrayList<>(matchIndex.values());
        indices.add(getLastLogIndex()); // 加上自己
        Collections.sort(indices);

        // 取中位数 (例如 3 个节点取索引 1，5 个节点取索引 2)
        // 假设 indices 是排序后的 matchIndex 列表 [m1, m2, m3, m4, m5]
        // committed index 是 N，使得 matchIndex >= N 的节点数过半
        // 排序后，索引 (n-1)/2 处的元素即为那个 N (假设 n 是奇数或偶数的大多数逻辑)
        int n = indices.size();
        long N = indices.get((n - 1) / 2);

        if (N > commitIndex) {
            LogEntry entry = raftLog.getEntry(N);
            // 只有当前任期的日志可以通过计算副本数提交 (Raft 安全性规则 5.4.2)
            // 旧任期的日志只能通过提交当前任期的日志来间接提交
            if (entry != null && entry.getTerm() == currentTerm.get()) {
                log.info("Leader CommitIndex 从 {} 更新为 {}", commitIndex, N);
                commitIndex = N;
                applyLog();
            }
        }
    }

    private void resetElectionTimeout() {
        resetElectionTimeout(1.0);
    }

    private void resetElectionTimeout(double factor) {
        if (electionTimeoutTask != null && !electionTimeoutTask.isDone()) {
            electionTimeoutTask.cancel(false);
        }
        long min = (long) (raftProperties.getElectionTimeoutMin() * factor);
        long max = (long) (raftProperties.getElectionTimeoutMax() * factor);
        long delay = min + (long) (Math.random() * (max - min));

        electionTimeoutTask = scheduler.schedule(this::startElection, delay, TimeUnit.MILLISECONDS);
    }




    private long getLastLogIndex() {
        return raftLog.getLastLogIndex();
    }

    private long getLastLogTerm() {
        return raftLog.getLastLogTerm();
    }

    // 检查候选人的日志是否至少和自己的一样新
    private boolean isLogUpToDate(long candidateLastLogIndex, long candidateLastLogTerm) {
        long myLastLogTerm = getLastLogTerm();
        long myLastLogIndex = getLastLogIndex();

        if (candidateLastLogTerm != myLastLogTerm) {
            return candidateLastLogTerm > myLastLogTerm;
        }
        return candidateLastLogIndex >= myLastLogIndex;
    }

    // 将已提交的日志条目应用到状态机
    private void applyLog() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = raftLog.getEntry(lastApplied);
            if (entry != null && !entry.getCommand().isEmpty()) {
                stateMachine.apply(entry.getCommand().toByteArray());
            }
        }
    }

    /**
     * 向状态机提议新命令的客户端 API。
     * @param command 命令数据
     * @return 如果被接受 (节点是领导者) 则为 true，否则为 false
     */
    public boolean propose(String command) {
        if (state != RaftState.LEADER) {
            return false;
        }

        long index = getLastLogIndex() + 1;
        LogEntry entry = LogEntry.newBuilder()
                .setTerm(currentTerm.get())
                .setIndex(index)
                .setCommand(ByteString.copyFromUtf8(command))
                .build();

        raftLog.append(entry);
        matchIndex.put(myId, index);
        log.info("Leader 提议命令，索引 {}: {}", index, command);
        // 复制将在下一次心跳时发送给跟随者
        return true;
    }

    /**
     * 获取节点当前状态快照
     */
    public RaftNodeStatus getNodeStatus() {
        long lastIdx = getLastLogIndex();
        long lastTerm = getLastLogTerm();

        // 获取最近的10条日志用于展示
        long startShowIndex = Math.max(1, lastIdx - 10 + 1);
        List<org.liquor.liquormq.grpc.LogEntry> recentEntries = raftLog.getEntriesFrom(startShowIndex);

        List<RaftNodeStatus.LogEntryInfo> simplifiedLogs = recentEntries.stream()
                .map(e -> RaftNodeStatus.LogEntryInfo.builder()
                        .index(e.getIndex())
                        .term(e.getTerm())
                        .command(e.getCommand().toStringUtf8())
                        .build())
                .collect(Collectors.toList());

        return RaftNodeStatus.builder()
                .nodeId(myId)
                .state(state)
                .currentTerm(currentTerm.get())
                .votedFor(votedFor.get())
                .commitIndex(commitIndex)
                .lastApplied(lastApplied)
                .lastLogIndex(lastIdx)
                .lastLogTerm(lastTerm)
                .nextIndices(state == RaftState.LEADER ? new java.util.HashMap<>(nextIndex) : null)
                .matchIndices(state == RaftState.LEADER ? new java.util.HashMap<>(matchIndex) : null)
                .logSize((int)lastIdx) // 假设 index 连续且从1开始，则大小约等于 lastIndex
                .recentLogEntries(simplifiedLogs)
                .build();
    }
}
