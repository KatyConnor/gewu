#!/bin/bash
java -jar /home/wnn/devcode/ai-code/gewu-platform/gewu-interface/target/gewu-interface-1.0.0-SNAPSHOT.jar > ./logs/gewu-app.log 2>&1;
tail -10f ./logs/gewu-app.log;
