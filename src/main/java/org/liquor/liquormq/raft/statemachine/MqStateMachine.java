package org.liquor.liquormq.raft.statemachine;

import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.raft.node.RaftProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Slf4j
@Component
public class MqStateMachine implements StateMachine {

    @Autowired
    private RaftProperties properties;

    private Path stateFile;

    @PostConstruct
    public void init() {
        String filename = "mq-state-" + properties.getNodeId() + ".data";
        this.stateFile = Paths.get(properties.getDataDir(), filename);

        // 重启时，由于 RaftLog 是持久化的，RaftNode 会从 lastApplied=0 开始重放所有日志。
        // 因此我们要么支持幂等写入，要么在启动时清空状态文件，让重放重建状态。
        //这里选择清空重建模式，简单且保证一致性。
        try {
            if (stateFile.getParent() != null) {
                Files.createDirectories(stateFile.getParent());
            }
            // 创建或截断文件
            Files.write(stateFile, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Initialized State Machine storage at {}, truncated for replay.", stateFile);
        } catch (IOException e) {
            log.error("Failed to init state file", e);
        }
    }

    @Override
    public synchronized void apply(byte[] command) {
        String data = new String(command, StandardCharsets.UTF_8);
        log.info("Applying command to state machine: {}", data);

        try {
            // 追加写入
            Files.writeString(stateFile, data + System.lineSeparator(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to persist state", e);
        }
    }
}
