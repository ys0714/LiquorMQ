package org.liquor.liquormq.raft.node;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.grpc.AppendEntriesRequest;
import org.liquor.liquormq.grpc.AppendEntriesResponse;
import org.liquor.liquormq.grpc.LogEntry;
import org.liquor.liquormq.raft.enums.RaftState;
import org.liquor.liquormq.raft.storage.RaftLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 负责日志复制逻辑的组件。
 * 包含 Leader 发送 AppendEntries 的逻辑，以及 Follower 处理 AppendEntries 的核心数据逻辑。
 */
@Slf4j
public class LogReplicator {

    private final RaftNode node;
    private final RaftLog raftLog;
    // 易变状态 (仅在领导者上)
    private final ConcurrentMap<Integer, Long> nextIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Long> matchIndex = new ConcurrentHashMap<>();

    public LogReplicator(RaftNode node, RaftLog raftLog) {
        this.node = node;
        this.raftLog = raftLog;
    }

    /**
     * 当成为 Leader 时初始化状态
     */
    public void initLeaderState() {
        long lastLogIndex = raftLog.getLastLogIndex();
        for (RaftPeer peer : node.getPeers()) {
            // 乐观假设：每个 Follower 都保持同步，从 lastLogIndex + 1 开始发送
            nextIndex.put(peer.getId(), lastLogIndex + 1);
            matchIndex.put(peer.getId(), 0L);
        }
    }

    public ConcurrentMap<Integer, Long> getNextIndex() {
        return nextIndex;
    }

    public ConcurrentMap<Integer, Long> getMatchIndex() {
        return matchIndex;
    }

    /**
     * Leader 向所有 Peers 发送心跳或日志
     */
    public void sendHeartbeats() {
         long term = node.getCurrentTerm();
         for (RaftPeer peer : node.getPeers()) {
             replicateLogToPeer(peer, term);
         }
    }

    private void replicateLogToPeer(RaftPeer peer, long term) {
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
                        .setLeaderId(node.getMyId())
                        .setPrevLogIndex(prevLogIndex)
                        .setPrevLogTerm(prevLogTerm)
                        .setLeaderCommit(node.getCommitIndex())
                        .addAllEntries(entries)
                        .build();

                AppendEntriesResponse response = peer.getStub().appendEntries(request);

                peer.resetFailures();

                synchronized (node) {
                    // 关键检查：如果回调回来时，节点已经不是当前任期的 Leader，则忽略响应
                    if (node.getCurrentTerm() != term || node.getState() != RaftState.LEADER) {
                        return;
                    }

                    if (response.getTerm() > term) {
                        node.updateTermAndConvert(response.getTerm());
                        return;
                    }

                    if (response.getSuccess()) {
                        if (!entries.isEmpty()) {
                            long lastEntryIndex = entries.get(entries.size() - 1).getIndex();
                            nextIndex.put(peer.getId(), lastEntryIndex + 1);
                            matchIndex.put(peer.getId(), lastEntryIndex);
                            updateCommitIndex();
                        }
                    } else {
                        // 复制失败（一致性检查失败），回退 nextIndex 并重试
                        long newNextIndex = Math.max(1, nextIdx - 1);
                        nextIndex.put(peer.getId(), newNextIndex);
                    }
                }

            } catch (Exception e) {
                int failures = peer.incrementAndGetFailures();
                if (failures == 1 || failures % 50 == 0) {
                    log.warn("向节点 {} 复制日志失败 (num={}): {}", peer.getId(), failures, e.getMessage());
                } else {
                    log.debug("向节点 {} 复制日志失败: {}", peer.getId(), e.getMessage());
                }
            }
        });
    }

    private synchronized void updateCommitIndex() {
        // 计算大多数节点已复制的日志索引
        List<Long> indices = new ArrayList<>(matchIndex.values());
        indices.add(raftLog.getLastLogIndex()); // 加上自己
        Collections.sort(indices);

        int n = indices.size();
        long N = indices.get((n - 1) / 2);

        if (N > node.getCommitIndex()) {
            LogEntry entry = raftLog.getEntry(N);
            // Raft 安全性规则 5.4.2: 只能提交当前任期的日志
            if (entry != null && entry.getTerm() == node.getCurrentTerm()) {
                log.info("Leader CommitIndex 从 {} 更新为 {}", node.getCommitIndex(), N);
                node.setCommitIndex(N);
                node.applyLog();
            }
        }
    }

    /**
     * Follower 处理 AppendEntries 的核心数据逻辑
     */
    public synchronized AppendEntriesResponse handleAppendEntriesLogic(AppendEntriesRequest request) {
        // 1. 已由 State 检查了 checkTerm，这里假设 Term 合法

        // 2. 一致性检查
        if (request.getPrevLogIndex() > 0) {
            long lastLogIndex = raftLog.getLastLogIndex();
            if (request.getPrevLogIndex() > lastLogIndex) {
                return AppendEntriesResponse.newBuilder().setTerm(node.getCurrentTerm()).setSuccess(false).build();
            }

            LogEntry prevEntry = raftLog.getEntry(request.getPrevLogIndex());
            if (prevEntry != null && prevEntry.getTerm() != request.getPrevLogTerm()) {
                return AppendEntriesResponse.newBuilder().setTerm(node.getCurrentTerm()).setSuccess(false).build();
            }
        }

        // 3. 处理日志冲突及追加
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
                    log.warn("发现日志冲突，截断本地日志。索引: {}, 现有任期: {}, 新任期: {}", index, existing.getTerm(), entry.getTerm());
                    raftLog.truncateFrom(index);
                    raftLog.append(entry);
                }
            } else {
                raftLog.append(entry);
            }
        }

        // 4. 更新 CommitIndex
        if (request.getLeaderCommit() > node.getCommitIndex()) {
            long newCommitIndex = Math.min(request.getLeaderCommit(), raftLog.getLastLogIndex());
            node.setCommitIndex(newCommitIndex);
            node.applyLog();
        }

        return AppendEntriesResponse.newBuilder()
                .setTerm(node.getCurrentTerm())
                .setSuccess(true)
                .build();
    }
}

