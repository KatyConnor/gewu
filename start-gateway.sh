#!/bin/bash
java -jar /home/wnn/devcode/ai-code/gewu-platform/gewu-gateway/target/gewu-gateway-1.0.0-SNAPSHOT.jar > ./logs/gewu-gateway.log 2>&1;
tail -10f ./logs/gewu-gateway.log;
