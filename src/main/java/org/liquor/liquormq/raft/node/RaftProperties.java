package org.liquor.liquormq.raft.node;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "liquormq.raft")
public class RaftProperties {
    private int nodeId;
    // 选举超时下限 (毫秒)
    private long electionTimeoutMin = 500;
    // 选举超时上限 (毫秒)
    private long electionTimeoutMax = 1000;
    // 心跳间隔 (毫秒)
    private long heartbeatInterval = 100;
    // 数据存储目录
    private String dataDir = "./data";

    private List<PeerConfig> peers = new ArrayList<>();

    @Data
    public static class PeerConfig {
        private int id;
        private String host;
        private int port;
    }
}
