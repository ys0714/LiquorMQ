package org.liquor.liquormq.raft.storage;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.grpc.LogEntry;
import org.liquor.liquormq.raft.node.RaftProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于文件的 Raft 日志实现。
 * 启动时读取全部日志到内存，支持持久化追加和截断。
 */
@Slf4j
@Component
@Primary
public class FileBasedRaftLog implements RaftLog {

    private final Path logPath;
    private final List<LogEntry> entries = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> fileOffsets = Collections.synchronizedList(new ArrayList<>());
    private RandomAccessFile fileAccess;

    public FileBasedRaftLog(RaftProperties properties) {
        String dir = properties.getDataDir();
        String filename = "raft-log-" + properties.getNodeId() + ".bin";
        this.logPath = Paths.get(dir, filename);
        init();
    }

    private void init() {
        try {
            if (logPath.getParent() != null && !Files.exists(logPath.getParent())) {
                Files.createDirectories(logPath.getParent());
            }
            this.fileAccess = new RandomAccessFile(logPath.toFile(), "rw");

            // 加载现有日志
            if (fileAccess.length() > 0) {
                loadEntries();
            }
        } catch (IOException e) {
            log.error("Failed to initialize Raft log file", e);
            throw new RuntimeException(e);
        }
    }

    private void loadEntries() throws IOException {
        log.info("Loading Raft log from {}", logPath);
        fileAccess.seek(0);
        long currentPos = 0;
        long fileLength = fileAccess.length();

        while (currentPos < fileLength) {
            try {
                // 使用 parseDelimitedFrom 需要 InputStream，这里用 BufferedInputStream 包装 FileInputStream
                // 但为了获取偏移量，我们需要手动处理或者使用 FilterInputStream 计数
                // 由于 parseDelimitedFrom 会消耗流，更简单的方法是：
                // 使用 GPB 的 parseDelimitedFrom(InputStream)

                // 为了精确控制 RandomAccessFile 位置，我们暂时采用手动读取长度的方式
                // Protobuf writeDelimitedTo 写的是 varint32 长度 + 字节
                // 我们重新实现简单的读取逻辑

                int firstByte = fileAccess.read();
                if (firstByte == -1) break;

                // 读取 Varint32 长度
                int size = readRawVarint32(firstByte, fileAccess);

                // 读取 body
                byte[] data = new byte[size];
                fileAccess.readFully(data);

                LogEntry entry = LogEntry.parseFrom(data);
                entries.add(entry);

                currentPos = fileAccess.getFilePointer();
                fileOffsets.add(currentPos);

            } catch (EOFException e) {
                // Unexpected EOF
                log.warn("Unexpected EOF while reading log file, truncating to valid length");
                fileAccess.setLength(currentPos);
                break;
            }
        }
        log.info("Loaded {} entries from disk", entries.size());
    }

    // 从 CodedInputStream 借用的逻辑
    private int readRawVarint32(int firstByte, RandomAccessFile input) throws IOException {
        if ((firstByte & 0x80) == 0) {
            return firstByte;
        }
        int result = firstByte & 0x7f;
        int offset = 7;
        for (; offset < 32; offset += 7) {
            int b = input.read();
            if (b == -1) {
                throw new EOFException();
            }
            result |= (b & 0x7f) << offset;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        // Should not reach here for valid varint32
        throw new IOException("Malformed varint");
    }

    @Override
    public synchronized long append(LogEntry entry) {
        try {
            // 定位到文件末尾
            fileAccess.seek(fileAccess.length());
            entry.writeDelimitedTo(new DataOutputStream(new FileOutputStream(fileAccess.getFD())));
            // 确保刷盘？为了性能通常依赖操作系统，或者 periodic fsync。
            // 强一致性要求每次 write 后 fsync。
            // fileAccess.getChannel().force(false);

            long endPos = fileAccess.getFilePointer();
            entries.add(entry);
            fileOffsets.add(endPos);

            return entries.size();
        } catch (IOException e) {
            log.error("Failed to append log entry", e);
            throw new RuntimeException(e);
        }
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
            // 清空所有
            try {
                fileAccess.setLength(0);
                entries.clear();
                fileOffsets.clear();
            } catch (IOException e) {
                log.error("Failed to truncate log", e);
                throw new RuntimeException(e);
            }
            return;
        }

        if (index > entries.size()) {
            return;
        }

        // index 是基于 1 的。要保留 index-1 个条目。
        // 保留 entries[0...index-2]
        // 新的 offset 是 fileOffsets[index-2]

        int keepCount = (int) index - 1;
        long newLength = fileOffsets.get(keepCount - 1);

        try {
            fileAccess.setLength(newLength);

            // 更新内存
            int currentSize = entries.size();
            // subList clear 是最高效的
            entries.subList(keepCount, currentSize).clear();
            fileOffsets.subList(keepCount, currentSize).clear();

            // 确保指针正确
            fileAccess.seek(newLength);

        } catch (IOException e) {
            log.error("Failed to truncate log to length " + newLength, e);
            throw new RuntimeException(e);
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

