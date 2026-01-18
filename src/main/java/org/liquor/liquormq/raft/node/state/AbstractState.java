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
        log.info("收到 RequestVote 请求. Request(CandidateId={}, Term={}, LastLogIndex={}, LastLogTerm={}). CurrentState(MyId={}, Term={}, VotedFor={}, LastLogIndex={}, LastLogTerm={}, State={})",
                request.getCandidateId(), request.getTerm(), request.getLastLogIndex(), request.getLastLogTerm(),
                node.getMyId(), node.getCurrentTerm(), node.getVotedFor(), node.getLastLogIndex(), node.getLastLogTerm(), node.getState());

        long term = node.getCurrentTerm();
        long requestTerm = request.getTerm();

        // 1. 如果请求的任期小于当前任期，拒绝投票
        if (requestTerm < term) {
            log.info("拒绝 RequestVote: 请求任期 {} 小于 当前任期 {}", requestTerm, term);
            return VoteResponse.newBuilder().setTerm(term).setVoteGranted(false).build();
        }

        // 如果请求的任期大于当前任期，无论当前是什么状态，都应更新任期并转为 Follower
        // 注意：具体的投票逻辑（是否已投给别人）由各个具体状态或通用逻辑处理
        if (requestTerm > term) {
            log.info("收到 RequestVote 任期 ({}) > 当前任期 ({})，更新任期并转为 Follower", requestTerm, term);
            node.updateTermAndConvert(requestTerm);
            // 状态已切换，应当委托给新状态处理请求，以确保逻辑的一致性
            return node.handleRequestVote(request);
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
             // 状态已切换，委托给新状态
             return node.handleAppendEntries(request);
        } else if (getType() != RaftState.FOLLOWER) {
             // 同任期的 Leader 发来心跳，Candidate 应该转 Follower
             node.convert(RaftState.FOLLOWER);
             // 状态已切换，委托给新状态
             return node.handleAppendEntries(request);
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
        boolean isLogUpToDate = node.isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm());
        if (node.canVoteFor(request.getCandidateId()) && isLogUpToDate) {
            synchronized (node) {
                // 在同步块中再次检查是否可以投票，防止并发情况下的重复投票
                if (node.canVoteFor(request.getCandidateId())) {
                    voteGranted = true;
                    node.voteFor(request.getCandidateId());
                    node.resetElectionTimeout();
                    log.info("{} 投票给候选人 {} 候选人Term= {}",request.getTerm(), node.getMyId(),request.getCandidateId());
                } else {
                    // 如果在此期间已经被其他请求投票了，则不进行投票
                    voteGranted = false;
                    log.info("并发情况下已被其他候选人投票，拒绝候选人 {} 的投票请求", request.getCandidateId());
                }
            }
        } else {
             if (!isLogUpToDate) {
                 log.info("拒绝投票给候选人 {}: 它的日志不够新。MyLast(Term={}, Index={}), Candidate(Term={}, Index={})",
                         request.getCandidateId(),
                         node.getLastLogTerm(), node.getLastLogIndex(),
                         request.getLastLogTerm(), request.getLastLogIndex());
             } else {
                 log.info("拒绝投票给候选人 {}: 已经投给了 {}", request.getCandidateId(), node.getVotedFor());
             }
        }

        VoteResponse response = VoteResponse.newBuilder()
                .setTerm(node.getCurrentTerm())
                .setVoteGranted(voteGranted)
                .build();

        log.info("处理 RequestVote 完成. 返回 Response(Term={}, VoteGranted={}). FinalState(MyId={}, Term={}, VotedFor={})",
                response.getTerm(), response.getVoteGranted(), node.getMyId(), node.getCurrentTerm(), node.getVotedFor());

        return response;
    }
}
