package org.liquor.liquormq.raft.node.state;

import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.raft.enums.RaftState;
import org.liquor.liquormq.raft.node.RaftNode;

@Slf4j
public class FollowerState extends AbstractState {

    public FollowerState(RaftNode node) {
        super(node);
    }

    @Override
    public RaftState getType() {
        return RaftState.FOLLOWER;
    }

    @Override
    public void start() {
        // 重置选举定时器，开始倒计时
        node.resetElectionTimeout();
    }

    @Override
    public void stop() {
        // 停止时不需要做太多事情，定时器会在状态切换时在 Node 层面被取消或重置
    }
}

