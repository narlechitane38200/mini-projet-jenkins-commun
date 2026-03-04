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
    }

    stages {

        stage('Tests') {
            agent {
                docker {
                    image 'maven:3.9-eclipse-temurin-17'
                    args '-v $HOME/.m2:/root/.m2'
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

        stage('Code Quality - SonarCloud') {
            agent {
                docker {
                    image 'maven:3.9-eclipse-temurin-17'
                    args '-v $HOME/.m2:/root/.m2'
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

        stage('Build & Push Docker Image') {
            agent {
                docker {
                    image 'docker:24'
                    args '-v /var/run/docker.sock:/var/run/docker.sock'
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
                    echo "${DOCKERHUB_CREDENTIALS_PSW}" | docker login \
                      -u "${DOCKERHUB_CREDENTIALS_USR}" --password-stdin

                    docker push ${DOCKERHUB_IMAGE}:${BUILD_NUMBER}
                    docker push ${DOCKERHUB_IMAGE}:latest
                    docker logout
                """
            }
        }

        stage('Deploy - Staging') {
            agent { label 'built-in' }

            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'mysql-credentials',
                        usernameVariable: 'MYSQL_USER',
                        passwordVariable: 'MYSQL_PASSWORD'
                    ),
                    string(credentialsId: 'staging-ec2-host', variable: 'STAGING_HOST')
                ]) {
                    sshagent(credentials: ['ec2-ssh-key']) {
                        // ✅ Simple quotes : les variables sont résolues par le shell,
                        //    pas par Groovy — élimine le warning d'interpolation sur les secrets
                        sh '''
                            set -e
                            ssh-keyscan -H $STAGING_HOST >> ~/.ssh/known_hosts

                            # Transfert du script SQL d'init vers le serveur Staging
                            scp -o StrictHostKeyChecking=no \
                                PayMyBuddy/initdb/create.sql \
                                ubuntu@$STAGING_HOST:/tmp/create.sql

                            ssh -o StrictHostKeyChecking=no ubuntu@$STAGING_HOST bash << EOF
                                set -e

                                docker network inspect paymybuddy-net >/dev/null 2>&1 || \
                                    docker network create paymybuddy-net

                                docker rm -f paymybuddy-db 2>/dev/null || true
                                docker run -d --name paymybuddy-db \
                                    --network paymybuddy-net \
                                    -e MYSQL_DATABASE=db_paymybuddy \
                                    -e MYSQL_USER="$MYSQL_USER" \
                                    -e MYSQL_PASSWORD="$MYSQL_PASSWORD" \
                                    -e MYSQL_ROOT_PASSWORD="$MYSQL_PASSWORD" \
                                    -v /tmp/create.sql:/docker-entrypoint-initdb.d/create.sql \
                                    -p $DB_PORT:3306 \
                                    mysql:8.0

                                until docker exec paymybuddy-db mysqladmin ping \
                                    -u root -p"$MYSQL_PASSWORD" --silent 2>/dev/null; do
                                    echo "Waiting for MySQL to be ready..."; sleep 3
                                done
                                echo "MySQL is ready!"

                                docker rm -f paymybuddy-app 2>/dev/null || true
                                docker run -d --name paymybuddy-app \
                                    --network paymybuddy-net \
                                    -e SPRING_DATASOURCE_URL=jdbc:mysql://paymybuddy-db:3306/db_paymybuddy \
                                    -e SPRING_DATASOURCE_USERNAME="$MYSQL_USER" \
                                    -e SPRING_DATASOURCE_PASSWORD="$MYSQL_PASSWORD" \
                                    -p $APP_PORT:8080 \
                                    $DOCKERHUB_IMAGE:$BUILD_NUMBER
EOF
                        '''
                    }
                }
            }
        }

        stage('Validation Tests - Staging') {
            agent {
                docker { image 'curlimages/curl:latest' }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'staging-ec2-host', variable: 'STAGING_HOST')
                ]) {
                    sleep(time: 30, unit: 'SECONDS')
                    sh '''
                        curl --retry 10 --retry-delay 5 --retry-connrefused \
                        -f http://$STAGING_HOST:$APP_PORT/actuator/health
                    '''
                }
            }
        }

        stage('Deploy - Production') {
            when { branch 'main' }
            agent { label 'built-in' }

            input {
                message "Déployer en Production ?"
                ok "Go Production"
            }

            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'mysql-credentials',
                        usernameVariable: 'MYSQL_USER',
                        passwordVariable: 'MYSQL_PASSWORD'
                    ),
                    string(credentialsId: 'prod-ec2-host', variable: 'PROD_HOST')
                ]) {
                    sshagent(credentials: ['ec2-ssh-key']) {
                        sh '''
                            set -e
                            ssh-keyscan -H $PROD_HOST >> ~/.ssh/known_hosts

                            # Transfert du script SQL d'init vers le serveur Production
                            scp -o StrictHostKeyChecking=no \
                                PayMyBuddy/initdb/create.sql \
                                ubuntu@$PROD_HOST:/tmp/create.sql

                            ssh -o StrictHostKeyChecking=no ubuntu@$PROD_HOST bash << EOF
                                set -e

                                docker network inspect paymybuddy-net >/dev/null 2>&1 || \
                                    docker network create paymybuddy-net

                                docker rm -f paymybuddy-db 2>/dev/null || true
                                docker run -d --name paymybuddy-db \
                                    --network paymybuddy-net \
                                    -e MYSQL_DATABASE=db_paymybuddy \
                                    -e MYSQL_USER="$MYSQL_USER" \
                                    -e MYSQL_PASSWORD="$MYSQL_PASSWORD" \
                                    -e MYSQL_ROOT_PASSWORD="$MYSQL_PASSWORD" \
                                    -v /tmp/create.sql:/docker-entrypoint-initdb.d/create.sql \
                                    -p $DB_PORT:3306 \
                                    mysql:8.0

                                until docker exec paymybuddy-db mysqladmin ping \
                                    -u root -p"$MYSQL_PASSWORD" --silent 2>/dev/null; do
                                    echo "Waiting for MySQL to be ready..."; sleep 3
                                done
                                echo "MySQL is ready!"

                                docker rm -f paymybuddy-app 2>/dev/null || true
                                docker run -d --name paymybuddy-app \
                                    --network paymybuddy-net \
                                    -e SPRING_DATASOURCE_URL=jdbc:mysql://paymybuddy-db:3306/db_paymybuddy \
                                    -e SPRING_DATASOURCE_USERNAME="$MYSQL_USER" \
                                    -e SPRING_DATASOURCE_PASSWORD="$MYSQL_PASSWORD" \
                                    -p $APP_PORT:8080 \
                                    $DOCKERHUB_IMAGE:$BUILD_NUMBER
EOF
                        '''
                    }
                }
            }
        }

        stage('Validation Tests - Production') {
            when { branch 'main' }
            agent {
                docker { image 'curlimages/curl:latest' }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'prod-ec2-host', variable: 'PROD_HOST')
                ]) {
                    sleep(time: 30, unit: 'SECONDS')
                    sh '''
                        curl --retry 10 --retry-delay 5 --retry-connrefused \
                        -f http://$PROD_HOST:$APP_PORT/actuator/health
                    '''
                }
            }
        }
    }

    post {
        success {
            slackSend(
                channel: env.SLACK_CHANNEL,
                color: 'good',
                message: "Pipeline SUCCESS - ${env.JOB_NAME} #${env.BUILD_NUMBER} (${env.BUILD_URL})"
            )
        }
        failure {
            slackSend(
                channel: env.SLACK_CHANNEL,
                color: 'danger',
                message: "Pipeline FAILED - ${env.JOB_NAME} #${env.BUILD_NUMBER} (${env.BUILD_URL})"
            )
        }
        unstable {
            slackSend(
                channel: env.SLACK_CHANNEL,
                color: 'warning',
                message: "Pipeline UNSTABLE - ${env.JOB_NAME} #${env.BUILD_NUMBER}"
            )
        }
    }
}