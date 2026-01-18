package org.liquor.liquormq.raft.node.state;

import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.grpc.VoteRequest;
import org.liquor.liquormq.grpc.VoteResponse;
import org.liquor.liquormq.raft.enums.RaftState;
import org.liquor.liquormq.raft.node.RaftNode;
import org.liquor.liquormq.raft.node.RaftPeer;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class CandidateState extends AbstractState {

    public CandidateState(RaftNode node) {
        super(node);
    }

    @Override
    public RaftState getType() {
        return RaftState.CANDIDATE;
    }

    @Override
    public void start() {
        startElection();
    }

    @Override
    public void stop() {
        // 停止选举相关的任务（如果有）
    }

    private void startElection() {
        long newTerm = node.incrementTermAndGet();
        node.voteFor(node.getMyId()); // 投给自己
        node.resetElectionTimeout(); // 这里的超时是 "选举超时"，如果在时间内没当选，则重试

        log.info("开始选举，针对任期 {}", newTerm);

        // 使用 Set 记录获得的选票来源（线程安全 Set）
        java.util.Set<Integer> votesReceived = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        votesReceived.add(node.getMyId()); // 加上自己的一票

        for (RaftPeer peer : node.getPeers()) {
            CompletableFuture.runAsync(() -> {
                try {
                    VoteRequest request = VoteRequest.newBuilder()
                            .setTerm(newTerm)
                            .setCandidateId(node.getMyId())
                            .setLastLogIndex(node.getLastLogIndex())
                            .setLastLogTerm(node.getLastLogTerm())
                            .build();

                    log.info("向节点 {} 发送 RequestVote: Term={}, LastLogIndex={}, LastLogTerm={}", peer.getId(), newTerm, node.getLastLogIndex(), node.getLastLogTerm());

                    VoteResponse response = peer.getStub().requestVote(request);

                    handleVoteResponse(response, newTerm, votesReceived, peer.getId());
                } catch (Exception e) {
                    log.error("向节点 {} 请求投票失败: {}", peer.getId(), e.getMessage());
                }
            });
        }
    }

    private void handleVoteResponse(VoteResponse response, long electionTerm, java.util.Set<Integer> votesReceived, int peerId) {
        log.info("收到节点 {} 的 VoteResponse: Term={}, VoteGranted={}", peerId, response.getTerm(), response.getVoteGranted());

        // 检查任期：如果发现更高任期，立即转 Follower
        if (response.getTerm() > electionTerm) {
            synchronized (node) { // 简单同步，实际需更细粒度
                 if (response.getTerm() > node.getCurrentTerm()) {
                     node.updateTermAndConvert(response.getTerm());
                 }
            }
            return;
        }

        // 检查是否赢得选票
        if (response.getVoteGranted()) {
             // 关键：检查此响应是否属于当前任期和当前状态
             // 如果在等待过程中已经变成 Follower 或 Leader，或开始了新任期，则丢弃
             if (node.getCurrentTerm() != electionTerm || node.getState() != RaftState.CANDIDATE) {
                 return;
             }

             votesReceived.add(peerId);
             int votes = votesReceived.size();
             int majority = (node.getPeers().size() + 1) / 2 + 1;

             if (votes >= majority) {
                 synchronized (node) {
                     // 再次检查状态，避免重复转换
                     if (node.getCurrentTerm() == electionTerm && node.getState() == RaftState.CANDIDATE) {
                         log.info("获得大多数选票 ({}/{}) 来源: {}, 正在转换为 LEADER",
                                 votes, node.getPeers().size() + 1, votesReceived);
                         node.convert(RaftState.LEADER);
                     }
                 }
             }
        }
    }
}

