#!/bin/bash
java -jar /home/wnn/devcode/ai-code/gewu-platform/gewu-sandbox/target/gewu-sandbox-1.0.0-SNAPSHOT.jar > ./logs/gewu-sandbox.log 2>&1;
tail -10f ./logs/gewu-sandbox.log;
