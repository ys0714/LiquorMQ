package org.liquor.liquormq.raft.node.state;

import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.grpc.AppendEntriesRequest;
import org.liquor.liquormq.grpc.AppendEntriesResponse;
import org.liquor.liquormq.raft.enums.RaftState;
import org.liquor.liquormq.raft.node.RaftNode;
import org.liquor.liquormq.raft.node.RaftPeer;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LeaderState extends AbstractState {

    private ScheduledFuture<?> heartbeatTask;

    public LeaderState(RaftNode node) {
        super(node);
    }

    @Override
    public RaftState getType() {
        return RaftState.LEADER;
    }

    @Override
    public void start() {
        log.info("当选为 LEADER，任期 {}", node.getCurrentTerm());
        node.setLeaderId(node.getMyId());

        // 停止选举定时器 (由 Node 的 convert 统一处理或在此处确保)
        // 初始化 Leader 特有的 nextIndex/matchIndex
        node.getLogReplicator().initLeaderState();

        // 提交一个 No-Op 日志 (空命令) 以确立当前任期的日志权威性
        // 这能防止那些没有任何日志的节点 (如刚重启且丢失内存日志) 凭借高任期抢夺 Leader
        node.propose("");

        // 立即发送心跳
        startLeaderLoop();
    }

    @Override
    public void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
    }

    private void startLeaderLoop() {
        // 使用 node 的 scheduler
        heartbeatTask = node.getScheduler().scheduleAtFixedRate(() -> {
            // 再次检查状态，尽管 stop() 会取消，但防御性编程
            if (node.getState() != RaftState.LEADER) {
                if(heartbeatTask != null) heartbeatTask.cancel(false);
                return;
            }
            node.getLogReplicator().sendHeartbeats();
        }, 0, node.getRaftProperties().getHeartbeatInterval(), TimeUnit.MILLISECONDS);
    }
}
