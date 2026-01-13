package org.liquor.liquormq.raft;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.grpc.*;
import org.liquor.liquormq.raft.storage.RaftLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RaftNode {

    private volatile RaftState state = RaftState.FOLLOWER;

    // 持久状态
    private final AtomicLong currentTerm = new AtomicLong(0);
    private final AtomicInteger votedFor = new AtomicInteger(-1);

    @Autowired
    private RaftLog raftLog;

    @Autowired
    private StateMachine stateMachine;

    @Autowired
    private RaftProperties raftProperties;

    // 易变状态
    private volatile long commitIndex = 0;
    private volatile long lastApplied = 0;

    // 领导者上的易变状态
    private final ConcurrentMap<Integer, Long> nextIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Long> matchIndex = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private ScheduledFuture<?> electionTimeoutTask;
    private ScheduledFuture<?> heartbeatTask;

    // 配置
    private int myId;
    private final List<RaftPeer> peers = new ArrayList<>();

    @PostConstruct
    public void init() {
        this.myId = raftProperties.getNodeId();

        for (RaftProperties.PeerConfig peerConfig : raftProperties.getPeers()) {
             // 不要将自己添加到对等节点列表
             if (peerConfig.getId() != myId) {
                 peers.add(new RaftPeer(peerConfig.getId(), peerConfig.getHost(), peerConfig.getPort()));
             }
        }

        resetElectionTimeout();
        log.info("RaftNode started with ID: {}", myId);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdown();
        peers.forEach(RaftPeer::shutdown);
    }

    // --- RPC 处理程序 (由 GrpcService 调用) ---

    public synchronized VoteResponse handleRequestVote(VoteRequest request) {
        log.debug("Received RequestVote from candidate {}", request.getCandidateId());

        boolean voteGranted = false;
        long term = currentTerm.get();

        if (request.getTerm() > term) {
            currentTerm.set(request.getTerm());
            becomeFollower(request.getTerm());
        }

        if (request.getTerm() == currentTerm.get() &&
            (votedFor.get() == -1 || votedFor.get() == request.getCandidateId()) &&
            isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm())) {

            voteGranted = true;
            votedFor.set(request.getCandidateId());
            resetElectionTimeout();
            log.info("Vote granted to candidate {}", request.getCandidateId());
        }

        return VoteResponse.newBuilder()
                .setTerm(currentTerm.get())
                .setVoteGranted(voteGranted)
                .build();
    }

    public synchronized AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        long term = currentTerm.get();

        if (request.getTerm() < term) {
            return AppendEntriesResponse.newBuilder().setTerm(term).setSuccess(false).build();
        }

        if (request.getTerm() >= term) {
            currentTerm.set(request.getTerm());
            becomeFollower(request.getTerm());
            // 如果需要，更新领导者 ID
        }

        resetElectionTimeout(); // 收到有效的心跳/追加请求

        // 检查之前的日志条目
        if (request.getPrevLogIndex() > 0) {
            long lastLogIndex = getLastLogIndex();
            if (request.getPrevLogIndex() > lastLogIndex) {
                return AppendEntriesResponse.newBuilder().setTerm(currentTerm.get()).setSuccess(false).build();
            }

            LogEntry prevEntry = raftLog.getEntry(request.getPrevLogIndex());
            if (prevEntry != null && prevEntry.getTerm() != request.getPrevLogTerm()) {
                return AppendEntriesResponse.newBuilder().setTerm(currentTerm.get()).setSuccess(false).build();
            }
        }

        // 处理条目
        List<LogEntry> entries = request.getEntriesList();
        long index = request.getPrevLogIndex();

        for (LogEntry entry : entries) {
            index++;
            LogEntry existing = raftLog.getEntry(index);
            if (existing != null) {
                if (existing.getTerm() != entry.getTerm()) {
                    // 冲突，在此处截断
                    raftLog.truncateFrom(index);
                    raftLog.append(entry);
                }
                // 如果任期匹配，我们假设它是相同的条目 (幂等性)
            } else {
                raftLog.append(entry);
            }
        }

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
        votedFor.set(-1);
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel(false);
        }
        resetElectionTimeout();
    }

    private void becomeLeader() {
        if (state != RaftState.CANDIDATE) return;

        log.info("Becoming LEADER for term {}", currentTerm.get());
        state = RaftState.LEADER;

        if (electionTimeoutTask != null) {
            electionTimeoutTask.cancel(false);
        }

        // 初始化领导者状态
        for (RaftPeer peer : peers) {
            nextIndex.put(peer.getId(), getLastLogIndex() + 1);
            matchIndex.put(peer.getId(), 0L);
        }

        // 开始发送心跳
        startHeartbeat();
    }

    private synchronized void startElection() {
        if (state == RaftState.LEADER) return;

        log.info("Starting election for term {}", currentTerm.get() + 1);
        state = RaftState.CANDIDATE;
        long newTerm = currentTerm.incrementAndGet();
        votedFor.set(myId);
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
                        becomeFollower(response.getTerm());
                        // 如果更高，原子地更新当前任期
                        currentTerm.set(Math.max(currentTerm.get(), response.getTerm()));
                    } else if (response.getVoteGranted()) {
                        if (votesReceived.incrementAndGet() > (peers.size() + 1) / 2) {
                            becomeLeader();
                        }
                    }
                } catch (Exception e) {
                    log.error("Error requesting vote from peer {}: {}", peer.getId(), e.getMessage());
                }
            });
        }
    }

    private void startHeartbeat() {
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (state != RaftState.LEADER) {
                heartbeatTask.cancel(false);
                return;
            }

            long term = currentTerm.get();
            for (RaftPeer peer : peers) {
                CompletableFuture.runAsync(() -> {
                     try {
                         AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                                 .setTerm(term)
                                 .setLeaderId(myId)
                                 .setPrevLogIndex(getLastLogIndex()) // 简化版
                                 .setPrevLogTerm(getLastLogTerm()) // 简化版
                                 .setLeaderCommit(commitIndex)
                                 .build();

                         AppendEntriesResponse response = peer.getStub().appendEntries(request);

                         if (response.getTerm() > term) {
                             becomeFollower(response.getTerm());
                             currentTerm.set(Math.max(currentTerm.get(), response.getTerm()));
                         }
                         // 在此处处理日志匹配成功/失败以进行复制

                     } catch (Exception e) {
                         log.warn("Failed to send heartbeat to peer {}: {}", peer.getId(), e.getMessage());
                     }
                });
            }
        }, 0, 100, TimeUnit.MILLISECONDS); // 每 100ms 心跳一次
    }

    private void resetElectionTimeout() {
        if (electionTimeoutTask != null && !electionTimeoutTask.isDone()) {
            electionTimeoutTask.cancel(false);
        }
        // 150-300ms 之间的随机超时
        long delay = 150 + (long) (Math.random() * 150);
        electionTimeoutTask = scheduler.schedule(this::startElection, delay, TimeUnit.MILLISECONDS);
    }

    private boolean isLogUpToDate(long lastLogIndex, long lastLogTerm) {
        long myLastLogIndex = getLastLogIndex();
        long myLastLogTerm = getLastLogTerm();

        if (lastLogTerm != myLastLogTerm) {
            return lastLogTerm > myLastLogTerm;
        }
        return lastLogIndex >= myLastLogIndex;
    }

    private long getLastLogIndex() {
        return raftLog.getLastLogIndex();
    }

    private long getLastLogTerm() {
        return raftLog.getLastLogTerm();
    }

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
        log.info("Leader proposed command at index {}: {}", index, command);
        // 复制将在下一次心跳时发生
        return true;
    }
}
