pipeline {
    agent none

    environment {
        DOCKERHUB_CREDENTIALS = credentials('dockerhub-credentials')
        DOCKERHUB_IMAGE       = "narlechitane38200/paymybuddy"
        SONAR_TOKEN           = credentials('sonarcloud-token')
        SONAR_ORG             = "jenkins-sonar-miniprojet"
        SONAR_PROJECT_KEY     = "jenkins-sonar-miniprojet"
        SLACK_CHANNEL         = "#mini-projet-jenkins-radouane"
        APP_PORT              = "8080"
        DB_PORT               = "3306"
        STAGING_HOST          = "ec2-3-235-91-231.compute-1.amazonaws.com"
        PROD_HOST             = "ec2-100-48-90-3.compute-1.amazonaws.com"
    }

    stages {

        // ─────────────────────────────────────────
        // TESTS
        // ─────────────────────────────────────────
        stage('Tests') {
            agent {
                docker {
                    image 'maven:3.9-eclipse-temurin-17'
                    args  '-v $HOME/.m2:/root/.m2'
                }
            }
            steps {
                dir('PayMyBuddy') {
                    sh 'mvn clean test'
                }
            }
            post {
                always {
                    junit 'PayMyBuddy/target/surefire-reports/*.xml'
                }
            }
        }

        // ─────────────────────────────────────────
        // QUALITE DU CODE
        // ─────────────────────────────────────────
        stage('Code Quality - SonarCloud') {
            agent {
                docker {
                    image 'maven:3.9-eclipse-temurin-17'
                    args  '-v $HOME/.m2:/root/.m2'
                }
            }
            steps {
                dir('PayMyBuddy') {
                    sh """
                        mvn sonar:sonar \
                            -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                            -Dsonar.organization=${SONAR_ORG} \
                            -Dsonar.host.url=https://sonarcloud.io \
                            -Dsonar.token=${SONAR_TOKEN}
                    """
                }
            }
        }

        // ─────────────────────────────────────────
        // BUILD & PUSH DOCKER
        // ─────────────────────────────────────────
        stage('Build & Push Docker Image') {
            agent {
                docker {
                    image 'docker:24'
                    args  '-v /var/run/docker.sock:/var/run/docker.sock'
                }
            }
            steps {
                dir('PayMyBuddy') {
                    sh """
                        docker build \
                            -t ${DOCKERHUB_IMAGE}:${BUILD_NUMBER} \
                            -t ${DOCKERHUB_IMAGE}:latest .
                    """
                }
                sh """
                    echo "${DOCKERHUB_CREDENTIALS_PSW}" | \
                        docker login -u "${DOCKERHUB_CREDENTIALS_USR}" --password-stdin
                    docker push ${DOCKERHUB_IMAGE}:${BUILD_NUMBER}
                    docker push ${DOCKERHUB_IMAGE}:latest
                    docker logout
                """
            }
        }

        // ─────────────────────────────────────────
        // DEPLOY STAGING
        // ─────────────────────────────────────────
        stage('Deploy - Staging') {
            agent { label 'built-in' }
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId:    'mysql-credentials',
                        usernameVariable: 'MYSQL_USER',
                        passwordVariable: 'MYSQL_PASSWORD'
                    )
                ]) {
                    script {
                        writeFile file: '/tmp/deploy-staging.sh', text: """#!/bin/bash
set -e

docker network inspect paymybuddy-net >/dev/null 2>&1 || \\
    docker network create paymybuddy-net

docker rm -f paymybuddy-db 2>/dev/null || true
docker volume rm paymybuddy-db-data 2>/dev/null || true
docker run -d --name paymybuddy-db \\
    --network paymybuddy-net \\
    -e MYSQL_DATABASE=db_paymybuddy \\
    -e MYSQL_USER=${env.MYSQL_USER} \\
    -e MYSQL_PASSWORD=${env.MYSQL_PASSWORD} \\
    -e MYSQL_ROOT_PASSWORD=${env.MYSQL_PASSWORD} \\
    -v /tmp/create.sql:/docker-entrypoint-initdb.d/create.sql \\
    -p ${env.DB_PORT}:3306 \\
    mysql:8.0

until docker exec paymybuddy-db mysqladmin ping \\
    -u root -p${env.MYSQL_PASSWORD} --silent 2>/dev/null; do
    echo "Waiting for MySQL to be ready..."; sleep 3
done
echo "MySQL is ready!"

docker rm -f paymybuddy-app 2>/dev/null || true
docker run -d --name paymybuddy-app \\
    --network paymybuddy-net \\
    -e SPRING_DATASOURCE_URL=jdbc:mysql://paymybuddy-db:3306/db_paymybuddy \\
    -e SPRING_DATASOURCE_USERNAME=${env.MYSQL_USER} \\
    -e SPRING_DATASOURCE_PASSWORD=${env.MYSQL_PASSWORD} \\
    -p ${env.APP_PORT}:8080 \\
    ${env.DOCKERHUB_IMAGE}:${env.BUILD_NUMBER}
"""
                    }
                    sshagent(credentials: ['ec2-ssh-key']) {
                        sh '''
                            set -e
                            mkdir -p ~/.ssh && chmod 700 ~/.ssh
                            ssh-keyscan -H $STAGING_HOST >> ~/.ssh/known_hosts

                            scp -B -o StrictHostKeyChecking=no \
                                $WORKSPACE/PayMyBuddy/initdb/create.sql \
                                ubuntu@$STAGING_HOST:/tmp/create.sql

                            scp -B -o StrictHostKeyChecking=no \
                                /tmp/deploy-staging.sh \
                                ubuntu@$STAGING_HOST:/tmp/deploy-staging.sh

                            ssh -o StrictHostKeyChecking=no ubuntu@$STAGING_HOST \
                                "chmod +x /tmp/deploy-staging.sh && /tmp/deploy-staging.sh"
                        '''
                    }
                }
            }
        }

        // ─────────────────────────────────────────
        // VALIDATION STAGING
        // ─────────────────────────────────────────
        stage('Validation Tests - Staging') {
            agent {
                docker { image 'curlimages/curl:latest' }
            }
            steps {
                sleep(time: 30, unit: 'SECONDS')
                sh '''
                    curl --retry 10 --retry-delay 5 --retry-connrefused \
                        -f http://$STAGING_HOST:$APP_PORT/actuator/health
                '''
            }
        }

        // ─────────────────────────────────────────
        // DEPLOY PRODUCTION
        // ─────────────────────────────────────────
        stage('Deploy - Production') {
            when  { branch 'main' }
            agent { label 'built-in' }
            input {
                message "Déployer en Production ?"
                ok      "Go Production"
            }
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId:    'mysql-credentials',
                        usernameVariable: 'MYSQL_USER',
                        passwordVariable: 'MYSQL_PASSWORD'
                    )
                ]) {
                    script {
                        writeFile file: '/tmp/deploy-prod.sh', text: """#!/bin/bash
set -e

docker network inspect paymybuddy-net >/dev/null 2>&1 || \\
    docker network create paymybuddy-net

docker rm -f paymybuddy-db 2>/dev/null || true
docker volume rm paymybuddy-db-data 2>/dev/null || true
docker run -d --name paymybuddy-db \\
    --network paymybuddy-net \\
    -e MYSQL_DATABASE=db_paymybuddy \\
    -e MYSQL_USER=${env.MYSQL_USER} \\
    -e MYSQL_PASSWORD=${env.MYSQL_PASSWORD} \\
    -e MYSQL_ROOT_PASSWORD=${env.MYSQL_PASSWORD} \\
    -v /tmp/create.sql:/docker-entrypoint-initdb.d/create.sql \\
    -p ${env.DB_PORT}:3306 \\
    mysql:8.0

until docker exec paymybuddy-db mysqladmin ping \\
    -u root -p${env.MYSQL_PASSWORD} --silent 2>/dev/null; do
    echo "Waiting for MySQL to be ready..."; sleep 3
done
echo "MySQL is ready!"

docker rm -f paymybuddy-app 2>/dev/null || true
docker run -d --name paymybuddy-app \\
    --network paymybuddy-net \\
    -e SPRING_DATASOURCE_URL=jdbc:mysql://paymybuddy-db:3306/db_paymybuddy \\
    -e SPRING_DATASOURCE_USERNAME=${env.MYSQL_USER} \\
    -e SPRING_DATASOURCE_PASSWORD=${env.MYSQL_PASSWORD} \\
    -p ${env.APP_PORT}:8080 \\
    ${env.DOCKERHUB_IMAGE}:${env.BUILD_NUMBER}
"""
                    }
                    sshagent(credentials: ['ec2-ssh-key']) {
                        sh '''
                            set -e
                            mkdir -p ~/.ssh && chmod 700 ~/.ssh
                            ssh-keyscan -H $PROD_HOST >> ~/.ssh/known_hosts

                            scp -B -o StrictHostKeyChecking=no \
                                $WORKSPACE/PayMyBuddy/initdb/create.sql \
                                ubuntu@$PROD_HOST:/tmp/create.sql

                            scp -B -o StrictHostKeyChecking=no \
                                /tmp/deploy-prod.sh \
                                ubuntu@$PROD_HOST:/tmp/deploy-prod.sh

                            ssh -o StrictHostKeyChecking=no ubuntu@$PROD_HOST \
                                "chmod +x /tmp/deploy-prod.sh && /tmp/deploy-prod.sh"
                        '''
                    }
                }
            }
        }

        // ─────────────────────────────────────────
        // VALIDATION PRODUCTION
        // ─────────────────────────────────────────
        stage('Validation Tests - Production') {
            when { branch 'main' }
            agent {
                docker { image 'curlimages/curl:latest' }
            }
            steps {
                sleep(time: 30, unit: 'SECONDS')
                sh '''
                    curl --retry 10 --retry-delay 5 --retry-connrefused \
                        -f http://$PROD_HOST:$APP_PORT/actuator/health
                '''
            }
        }
    }

    // ─────────────────────────────────────────
    // NOTIFICATIONS SLACK
    // ─────────────────────────────────────────
    post {
        success {
            slackSend(
                channel: env.SLACK_CHANNEL,
                color:   'good',
                message: "Pipeline SUCCESS - ${env.JOB_NAME} #${env.BUILD_NUMBER} (${env.BUILD_URL})"
            )
        }
        failure {
            slackSend(
                channel: env.SLACK_CHANNEL,
                color:   'danger',
                message: "Pipeline FAILED - ${env.JOB_NAME} #${env.BUILD_NUMBER} (${env.BUILD_URL})"
            )
        }
        unstable {
            slackSend(
                channel: env.SLACK_CHANNEL,
                color:   'warning',
                message: "Pipeline UNSTABLE - ${env.JOB_NAME} #${env.BUILD_NUMBER}"
            )
        }
    }
}