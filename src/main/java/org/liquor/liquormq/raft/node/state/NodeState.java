package org.liquor.liquormq.raft.node.state;

import org.liquor.liquormq.grpc.AppendEntriesRequest;
import org.liquor.liquormq.grpc.AppendEntriesResponse;
import org.liquor.liquormq.grpc.VoteRequest;
import org.liquor.liquormq.grpc.VoteResponse;
import org.liquor.liquormq.raft.enums.RaftState;

/**
 * Raft 状态行为接口 (State Pattern)
 * 定义不同角色 (Follower, Candidate, Leader) 在接收 RPC 和定时器事件时的特定行为。
 */
public interface NodeState {

    /**
     * 获取当前状态枚举
     */
    RaftState getType();

    /**
     * 生命周期开始时调用 (进入该状态)
     */
    void start();

    /**
     * 生命周期结束时调用 (退出该状态)
     */
    void stop();

    /**
     *处理 RequestVote RPC
     */
    VoteResponse handleRequestVote(VoteRequest request);

    /**
     * 处理 AppendEntries RPC
     */
    AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request);
}

