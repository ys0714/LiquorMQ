package org.liquor.liquormq.raft.storage;

import org.liquor.liquormq.grpc.LogEntry;

import java.util.List;

public interface RaftLog {
    /**
     * 向日志追加条目。
     * @param entry 要追加的条目。
     * @return 追加条目的索引。
     */
    long append(LogEntry entry);

    /**
     * 获取指定索引处的条目。
     * @param index 索引 (基于 1)。
     * @return 条目，如果未找到则返回 null。
     */
    LogEntry getEntry(long index);

    /**
     * 获取日志中最后一个条目的索引。
     * @return 最后一个索引，如果为空则为 0。
     */
    long getLastLogIndex();

    /**
     * 获取日志中最后一个条目的任期。
     * @return 最后一个任期，如果为空则为 0。
     */
    long getLastLogTerm();

    /**
     * 从给定索引 (包含) 开始删除条目直到末尾。
     * 用于发现冲突条目时。
     * @param index 要开始删除的索引。
     */
    void truncateFrom(long index);

    /**
     * 检索一定范围的条目。
     * @param fromIndex 包含的起始索引。
     * @return 条目列表。
     */
    List<LogEntry> getEntriesFrom(long fromIndex);
}
