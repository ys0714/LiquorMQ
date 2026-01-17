package org.liquor.liquormq.raft.storage;

import org.liquor.liquormq.grpc.LogEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RaftLog 的简单内存实现。
 * 非持久化！仅用于测试/原型设计。
 */
// @Component
public class InMemoryRaftLog implements RaftLog {

    // 索引 0 未使用，以便于基于 1 的索引，或者我们只是处理偏移量。
    // 这里我们使用基于 0 的列表，因此列表中的索引 i 对应日志索引 i+1。
    private final List<LogEntry> entries = Collections.synchronizedList(new ArrayList<>());

    @Override
    public synchronized long append(LogEntry entry) {
        entries.add(entry);
        return entries.size(); // 逻辑索引 (基于 1，因为列表大小现在是计数)
    }

    @Override
    public synchronized LogEntry getEntry(long index) {
        if (index <= 0 || index > entries.size()) {
            return null;
        }
        return entries.get((int) index - 1);
    }

    @Override
    public synchronized long getLastLogIndex() {
        return entries.size();
    }

    @Override
    public synchronized long getLastLogTerm() {
        if (entries.isEmpty()) {
            return 0;
        }
        return entries.get(entries.size() - 1).getTerm();
    }

    @Override
    public synchronized void truncateFrom(long index) {
        if (index <= 1) {
            entries.clear();
            return;
        }
        if (index > entries.size()) {
            return;
        }
        // 索引是基于 1 的。
        // 如果 index=2，我们保留索引 1 (列表 idx 0)。从列表 idx 1 开始删除。
        int fromListIndex = (int) index - 1;
        if (fromListIndex < entries.size()) {
            entries.subList(fromListIndex, entries.size()).clear();
        }
    }

    @Override
    public synchronized List<LogEntry> getEntriesFrom(long fromIndex) {
        if (fromIndex > entries.size()) {
            return new ArrayList<>();
        }
        int fromListIndex = Math.max(0, (int) fromIndex - 1);
        return new ArrayList<>(entries.subList(fromListIndex, entries.size()));
    }
}

