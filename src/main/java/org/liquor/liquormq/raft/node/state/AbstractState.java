package org.liquor.liquormq.raft.node.state;

import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.grpc.AppendEntriesRequest;
import org.liquor.liquormq.grpc.AppendEntriesResponse;
import org.liquor.liquormq.grpc.VoteRequest;
import org.liquor.liquormq.grpc.VoteResponse;
import org.liquor.liquormq.raft.enums.RaftState;
import org.liquor.liquormq.raft.node.RaftNode;

/**
 * 抽象状态基类，包含通用的 RPC 预处理逻辑
 */
@Slf4j
public abstract class AbstractState implements NodeState {

    protected final RaftNode node;

    public AbstractState(RaftNode node) {
        this.node = node;
    }

    @Override
    public VoteResponse handleRequestVote(VoteRequest request) {
        long term = node.getCurrentTerm();
        long requestTerm = request.getTerm();

        // 1. 如果请求的任期小于当前任期，拒绝投票
        if (requestTerm < term) {
            return VoteResponse.newBuilder().setTerm(term).setVoteGranted(false).build();
        }

        // 如果请求的任期大于当前任期，无论当前是什么状态，都应更新任期并转为 Follower
        // 注意：具体的投票逻辑（是否已投给别人）由各个具体状态或通用逻辑处理
        if (requestTerm > term) {
            log.info("收到 RequestVote 任期 ({}) > 当前任期 ({})，更新任期并转为 Follower", requestTerm, term);
            node.updateTermAndConvert(requestTerm);
            // 这里不直接返回，因为转为 Follower 后还需要处理这次投票请求
            // 但由于状态模式的切换，通常我们会让新的状态对象接管或在这里完成通用部分。
            // 简单起见，这里让当前的逻辑继续，因为 updateTermAndConvert 会切换状态对象，
            // 但当前方法调用是基于旧对象的。这是一个典型的状态模式陷阱。
            // 实际上，如果发生状态转换，当前处理应该中断或委托给新状态。
            // 为了简化，我们假设 RaftNode 里的 currentTerm 已经更新。
        }

        // 调用具体的投票判断逻辑 - 这部分逻辑在所有状态下其实是很通用的
        return processVoteLogic(request);
    }

    @Override
    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        long term = node.getCurrentTerm();
        long requestTerm = request.getTerm();

        // 1. 如果请求的任期小于当前任期，返回失败
        if (requestTerm < term) {
            return AppendEntriesResponse.newBuilder().setTerm(term).setSuccess(false).build();
        }

        // 如果收到 >= currentTerm 的 AppendEntries：
        // 1. 也是 Leader (不可能，除非脑裂或 Term 错乱，这里按 Raft 规范应转 Follower)
        // 2. 是 Candidate (转 Follower)
        // 3. 是 Follower (重置超时)

        if (requestTerm > term) {
             log.info("收到 AppendEntries 任期 ({}) > 当前任期 ({})，更新任期并转为 Follower", requestTerm, term);
             node.updateTermAndConvert(requestTerm);
        } else if (getType() != RaftState.FOLLOWER) {
             // 同任期的 Leader 发来心跳，Candidate 应该转 Follower
             node.convert(RaftState.FOLLOWER);
        }

        // 更新 LeaderID，重置选举定时器等逻辑在 RaftNode 的通用方法或 Follower 状态中处理
        node.setLeaderId(request.getLeaderId());
        node.resetElectionTimeout();

        return node.getLogReplicator().handleAppendEntriesLogic(request);
    }

    // 提取通用的投票逻辑
    protected VoteResponse processVoteLogic(VoteRequest request) {
        boolean voteGranted = false;
        // 逻辑委托回 Node 或在此实现
        // ... (原 RaftNode 中的 vote 逻辑) ...
        // 由于需要访问 votedFor 和 log，最好还是调用 node 的方法

        // 2. 如果 (未投票 || 已经投给了该候选人)，并且候选人的日志至少和自己一样新，则投票
        if (node.canVoteFor(request.getCandidateId()) &&
            node.isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm())) {

            voteGranted = true;
            node.voteFor(request.getCandidateId());
            node.resetElectionTimeout();
            log.info("投票给候选人 {}", request.getCandidateId());
        }

        return VoteResponse.newBuilder()
                .setTerm(node.getCurrentTerm())
                .setVoteGranted(voteGranted)
                .build();
    }
}

