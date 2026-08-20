#!/bin/bash
## 2. 以开启追踪的方式启动应用
OTEL_TRACING_ENABLED=true java -jar /home/wnn/devcode/ai-code/gewu-platform/gewu-interface/target/gewu-interface-1.0.0-SNAPSHOT.jar > ./logs/gewu-app.log 2>&1;
tail -10f ./logs/gewu-app.log;
