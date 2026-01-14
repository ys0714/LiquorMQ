package org.liquor.liquormq.raft.storage;

import lombok.extern.slf4j.Slf4j;
import org.liquor.liquormq.raft.node.RaftProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@Slf4j
@Component
public class RaftMetaStorage {

    private final Path filePath;

    public RaftMetaStorage(RaftProperties properties) {
        String dir = properties.getDataDir();
        String filename = "raft-meta-" + properties.getNodeId() + ".properties";
        this.filePath = Paths.get(dir, filename);
    }

    public synchronized RaftMetadata load() {
        if (!Files.exists(filePath)) {
            log.info("No existing metadata file found at {}, starting fresh.", filePath);
            return new RaftMetadata(0, -1);
        }
        try (InputStream in = Files.newInputStream(filePath)) {
            Properties props = new Properties();
            props.load(in);
            long term = Long.parseLong(props.getProperty("currentTerm", "0"));
            int votedFor = Integer.parseInt(props.getProperty("votedFor", "-1"));
            log.info("Loaded Raft metadata from {}: term={}, votedFor={}", filePath, term, votedFor);
            return new RaftMetadata(term, votedFor);
        } catch (IOException e) {
            log.error("Failed to load Raft metadata", e);
            throw new RuntimeException("Failed to load metadata", e);
        }
    }

    public synchronized void save(long currentTerm, int votedFor) {
        try {
            if (filePath.getParent() != null && !Files.exists(filePath.getParent())) {
                Files.createDirectories(filePath.getParent());
            }
            Properties props = new Properties();
            props.setProperty("currentTerm", String.valueOf(currentTerm));
            props.setProperty("votedFor", String.valueOf(votedFor));

            try (OutputStream out = Files.newOutputStream(filePath)) {
                props.store(out, "Raft Node Metadata");
            }
        } catch (IOException e) {
            log.error("Failed to save Raft metadata to {}", filePath, e);
        }
    }
}

