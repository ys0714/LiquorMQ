@echo off
set JAR_FILE=target/liquorMQ-0.0.1-SNAPSHOT.jar

if not exist "%JAR_FILE%" (
    echo [ERROR] Jar file not found: %JAR_FILE%
    echo Please run 'mvn clean package -DskipTests' first.
    pause
    exit /b
)

echo Starting Raft Cluster (5 Nodes)...

start "Raft Node 1" java -jar %JAR_FILE% --server.port=8081 --grpc.server.port=9091 --liquormq.raft.node-id=1
timeout /t 2 /nobreak >nul

start "Raft Node 2" java -jar %JAR_FILE% --server.port=8082 --grpc.server.port=9092 --liquormq.raft.node-id=2
timeout /t 2 /nobreak >nul

start "Raft Node 3" java -jar %JAR_FILE% --server.port=8083 --grpc.server.port=9093 --liquormq.raft.node-id=3
timeout /t 2 /nobreak >nul

start "Raft Node 4" java -jar %JAR_FILE% --server.port=8084 --grpc.server.port=9094 --liquormq.raft.node-id=4
timeout /t 2 /nobreak >nul

start "Raft Node 5" java -jar %JAR_FILE% --server.port=8085 --grpc.server.port=9095 --liquormq.raft.node-id=5

echo Cluster started!

