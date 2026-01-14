package org.liquor.liquormq.raft.node;

import lombok.Builder;
import lombok.Data;
import org.liquor.liquormq.raft.enums.RaftState;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class RaftNodeStatus {
    private int nodeId;
    private RaftState state;
    private long currentTerm;
    private int votedFor;
    private long commitIndex;
    private long lastApplied;
    private long lastLogIndex;
    private long lastLogTerm;

    // 仅 Leader 有效数据
    private Map<Integer, Long> nextIndices;
    private Map<Integer, Long> matchIndices;

    // 为了监控方便，可以带上部分最近的日志，或者只展示日志摘要
    // 这里我们简单展示一下日志总数，具体日志内容可能太大了，可以通过参数控制是否返回
    private int logSize;

    // 可选：展示最近的N条日志
    private List<LogEntryInfo> recentLogEntries;

    @Data
    @Builder
    public static class LogEntryInfo {
        private long index;
        private long term;
        private String command;
    }
}

