package org.liquor.liquormq.observer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

// Exclude RaftNode and other components to keep this lightweight
@SpringBootApplication
@ComponentScan(
    basePackages = "org.liquor.liquormq.observer",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "org.liquor.liquormq.raft.*"
    )
)
public class ObserverDashboardApplication {
    public static void main(String[] args) {
        // 设置不同的端口，避免冲突
        System.setProperty("server.port", "8090");
        System.setProperty("grpc.server.port", "9099");
        // 激活观察者服务端功能
        System.setProperty("liquormq.observer.server.enabled", "true");
        SpringApplication.run(ObserverDashboardApplication.class, args);
    }
}
