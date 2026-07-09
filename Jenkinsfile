// Jenkinsfile - 格物平台 CI/CD 流水线
// 适用于 Jenkins Pipeline (Declarative Syntax)

pipeline {
    agent any

    environment {
        MAVEN_HOME = tool 'Maven-3.8'
        JAVA_HOME = tool 'JDK-21'
        DOCKER_REGISTRY = 'registry.gewu.com'
        IMAGE_TAG = "${BUILD_NUMBER}"
    }

    stages {
        stage('代码检出') {
            steps {
                checkout scm
                echo "代码检出完成: ${env.GIT_COMMIT}"
            }
        }

        stage('代码扫描') {
            steps {
                echo '执行 SonarQube 代码扫描...'
                sh '''
                    mvn sonar:sonar \
                      -Dsonar.projectKey=gewu-platform \
                      -Dsonar.host.url=${SONAR_HOST_URL} \
                      -Dsonar.login=${SONAR_TOKEN}
                '''
            }
            post {
                failure {
                    echo '代码扫描失败: 存在 Blocker 或 Critical 问题'
                    currentBuild.result = 'FAILURE'
                }
            }
        }

        stage('单元测试') {
            steps {
                echo '执行单元测试...'
                sh 'mvn clean test -pl gewu-common,gewu-domain,gewu-application'
            }
            post {
                always {
                    junit '*/target/surefire-reports/*.xml'
                    jacoco execFile: '*/target/jacoco.exec'
                }
                failure {
                    echo '单元测试失败'
                    currentBuild.result = 'FAILURE'
                }
            }
        }

        stage('集成测试') {
            when {
                branch 'main'
            }
            steps {
                echo '启动 Testcontainers 集成测试...'
                sh '''
                    docker-compose -f docker-compose.test.yml up -d
                    mvn verify -pl gewu-infrastructure,gewu-interface
                    docker-compose -f docker-compose.test.yml down
                '''
            }
            post {
                always {
                    junit '*/target/failsafe-reports/*.xml'
                }
            }
        }

        stage('Maven 构建') {
            steps {
                echo 'Maven 打包...'
                sh 'mvn clean package -DskipTests'
            }
            post {
                success {
                    archiveArtifacts artifacts: '*/target/*.jar', fingerprint: true
                }
            }
        }

        stage('镜像构建') {
            when {
                branch 'main'
            }
            steps {
                echo '构建 Docker 镜像...'
                sh '''
                    docker build -t ${DOCKER_REGISTRY}/gewu-gateway:${IMAGE_TAG} -f gewu-gateway/Dockerfile .
                    docker build -t ${DOCKER_REGISTRY}/gewu-interface:${IMAGE_TAG} -f gewu-interface/Dockerfile .
                    docker build -t ${DOCKER_REGISTRY}/gewu-application:${IMAGE_TAG} -f gewu-application/Dockerfile .
                    docker build -t ${DOCKER_REGISTRY}/gewu-sandbox:${IMAGE_TAG} -f gewu-sandbox/Dockerfile .
                '''
            }
        }

        stage('镜像扫描') {
            when {
                branch 'main'
            }
            steps {
                echo 'Trivy 镜像安全扫描...'
                sh '''
                    trivy image --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_REGISTRY}/gewu-gateway:${IMAGE_TAG}
                    trivy image --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_REGISTRY}/gewu-interface:${IMAGE_TAG}
                '''
            }
            post {
                failure {
                    echo '镜像扫描发现高危漏洞'
                    currentBuild.result = 'FAILURE'
                }
            }
        }

        stage('数据库迁移验证') {
            when {
                branch 'main'
            }
            steps {
                echo '验证 Flyway 数据库迁移脚本...'
                sh '''
                    docker-compose up -d ob
                    sleep 60
                    mvn flyway:validate -pl gewu-infrastructure
                    docker-compose down
                '''
            }
        }

        stage('推送镜像') {
            when {
                branch 'main'
            }
            steps {
                echo '推送镜像到私有仓库...'
                withCredentials([usernamePassword(credentialsId: 'docker-registry', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh '''
                        docker login -u ${DOCKER_USER} -p ${DOCKER_PASS} ${DOCKER_REGISTRY}
                        docker push ${DOCKER_REGISTRY}/gewu-gateway:${IMAGE_TAG}
                        docker push ${DOCKER_REGISTRY}/gewu-interface:${IMAGE_TAG}
                        docker push ${DOCKER_REGISTRY}/gewu-application:${IMAGE_TAG}
                        docker push ${DOCKER_REGISTRY}/gewu-sandbox:${IMAGE_TAG}
                    '''
                }
            }
        }

        stage('部署到 Dev 环境') {
            when {
                branch 'develop'
            }
            steps {
                echo '部署到 Dev 环境...'
                sh '''
                    kubectl set image deployment/gewu-gateway \
                      gateway=${DOCKER_REGISTRY}/gewu-gateway:${IMAGE_TAG} \
                      -n gewu-dev
                    kubectl rollout status deployment/gewu-gateway -n gewu-dev --timeout=300s
                '''
            }
        }

        stage('冒烟测试') {
            when {
                branch 'develop'
            }
            steps {
                echo '执行冒烟测试...'
                sh '''
                    curl -f http://gewu-dev.gewu.com/actuator/health || exit 1
                    curl -f http://gewu-dev.gewu.com/api/v1/users/me || exit 1
                '''
            }
        }

        stage('部署到 Staging 环境') {
            when {
                branch 'main'
            }
            steps {
                echo '部署到 Staging 环境...'
                sh '''
                    kubectl set image deployment/gewu-gateway \
                      gateway=${DOCKER_REGISTRY}/gewu-gateway:${IMAGE_TAG} \
                      -n gewu-staging
                    kubectl rollout status deployment/gewu-gateway -n gewu-staging --timeout=300s
                '''
            }
        }

        stage('E2E 测试') {
            when {
                branch 'main'
            }
            steps {
                echo '执行端到端测试...'
                sh '''
                    cd gewu-web
                    npm run test:e2e -- --baseUrl=http://gewu-staging.gewu.com
                '''
            }
        }

        stage('金丝雀发布') {
            when {
                branch 'main'
            }
            steps {
                echo '金丝雀发布到生产环境 (5%)...'
                sh '''
                    kubectl set image deployment/gewu-gateway-canary \
                      gateway=${DOCKER_REGISTRY}/gewu-gateway:${IMAGE_TAG} \
                      -n gewu-prod
                    kubectl scale deployment/gewu-gateway-canary --replicas=1 -n gewu-prod
                    sleep 300  # 观察 5 分钟
                '''
            }
        }

        stage('生产全量发布') {
            when {
                branch 'main'
                expression {
                    // 金丝雀验证通过（手动审批或自动判断）
                    input message: '金丝雀验证通过？', ok: '继续发布'
                    return true
                }
            }
            steps {
                echo '生产全量发布...'
                sh '''
                    kubectl set image deployment/gewu-gateway \
                      gateway=${DOCKER_REGISTRY}/gewu-gateway:${IMAGE_TAG} \
                      -n gewu-prod
                    kubectl rollout status deployment/gewu-gateway -n gewu-prod --timeout=600s
                '''
            }
        }
    }

    post {
        success {
            echo '流水线执行成功'
            // 发送通知
            sh '''
                curl -X POST ${DINGTALK_WEBHOOK} \
                  -H 'Content-Type: application/json' \
                  -d '{"msgtype":"text","text":{"content":"格物平台构建成功: ${BUILD_NUMBER}"}}'
            '''
        }
        failure {
            echo '流水线执行失败'
            sh '''
                curl -X POST ${DINGTALK_WEBHOOK} \
                  -H 'Content-Type: application/json' \
                  -d '{"msgtype":"text","text":{"content":"格物平台构建失败: ${BUILD_NUMBER}"}}'
            '''
        }
        always {
            // 清理工作空间
            cleanWs()
        }
    }
}
