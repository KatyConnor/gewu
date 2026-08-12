pipeline {
    agent any

    environment {
        DOCKER_REGISTRY = 'registry.cn-hangzhou.aliyuncs.com'
        DOCKER_NAMESPACE = 'gewu-platform'
        IMAGE_NAME = "${DOCKER_REGISTRY}/${DOCKER_NAMESPACE}/gewu-platform"
        JAVA_HOME = '/usr/lib/jvm/java-21-openjdk'
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn clean compile -T 4 -B'
            }
        }

        stage('Test') {
            steps {
                sh 'mvn test'
            }
            post {
                always {
                    junit '**/surefire-reports/TEST-*.xml'
                }
            }
        }

        stage('Security Scan') {
            steps {
                sh '''
                    trivy fs --severity CRITICAL,HIGH --exit-code 1 . || true
                '''
            }
        }

        stage('Package') {
            steps {
                sh 'mvn package -DskipTests -B'
            }
        }

        stage('Build Docker Image') {
            when {
                branch 'main'
            }
            steps {
                script {
                    docker.withRegistry("https://${DOCKER_REGISTRY}", 'docker-credentials') {
                        def image = docker.build("${IMAGE_NAME}:${env.BUILD_NUMBER}")
                        image.push()
                        image.push('latest')
                    }
                }
            }
        }

        stage('Deploy to Staging') {
            when {
                branch 'main'
            }
            steps {
                sh '''
                    kubectl config use-context staging
                    kubectl set image deployment/gewu-platform \
                        gewu-platform=${IMAGE_NAME}:${env.BUILD_NUMBER} \
                        -n gewu-staging
                    kubectl rollout status deployment/gewu-platform \
                        -n gewu-staging --timeout=300s
                '''
            }
        }

        stage('Deploy to Production') {
            when {
                branch 'main'
            }
            input {
                message "Deploy to production?"
                ok "Yes, deploy it"
            }
            steps {
                sh '''
                    kubectl config use-context production
                    kubectl set image deployment/gewu-platform \
                        gewu-platform=${IMAGE_NAME}:${env.BUILD_NUMBER} \
                        -n gewu
                    kubectl rollout status deployment/gewu-platform \
                        -n gewu --timeout=300s
                '''
            }
        }
    }

    post {
        failure {
            slackSend(
                color: 'danger',
                message: "Build Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}"
            )
        }
        success {
            slackSend(
                color: 'good',
                message: "Build Success: ${env.JOB_NAME} #${env.BUILD_NUMBER}"
            )
        }
    }
}
