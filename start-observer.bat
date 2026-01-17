@echo off
echo Starting LiquorMQ Cluster Observer Dashboard...
echo Logs will be aggregated here.
title LiquorMQ Observer
call mvnw spring-boot:run -Dspring-boot.run.main-class=org.liquor.liquormq.observer.ObserverDashboardApplication
pause

