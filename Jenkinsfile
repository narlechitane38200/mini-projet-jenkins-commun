pipeline {
    agent none

    environment {
        DOCKERHUB_CREDENTIALS = credentials('dockerhub-credentials')
        DOCKERHUB_IMAGE       = "narlechitane38200/paymybuddy"
        SONAR_TOKEN           = credentials('sonarcloud-token')
        SONAR_ORG             = "JENKINS_SONAR_MINIPROJET"
        SONAR_PROJECT_KEY     = "jenkins-sonar-miniprojet"
        SLACK_CHANNEL         = "#ci-cd"
        APP_PORT              = "8080"
        DB_PORT               = "3306"
    }

    stages {

        // ─────────────────────────────────────────────
        // ÉTAPE 1 — Tests automatisés
        // ─────────────────────────────────────────────
        stage('Tests') {
            agent {
                docker {
                    image 'maven:3.9-eclipse-temurin-17'
                    args  '-v $HOME/.m2:/root/.m2'
                }
            }
            steps {
                dir('PayMyBuddy') {
                    sh 'mvn test'
                }
            }
            post {
                always {
                    junit 'PayMyBuddy/target/surefire-reports/*.xml'
                }
            }
        }

        // ─────────────────────────────────────────────
        // ÉTAPE 2 — Qualité du code (SonarCloud)
        // ─────────────────────────────────────────────
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
                          -Dsonar.login=${SONAR_TOKEN}
                    """
                }
            }
        }

        // ─────────────────────────────────────────────
        // ÉTAPE 3 — Build image Docker + Push DockerHub
        // ─────────────────────────────────────────────
        stage('Build & Push Docker Image') {
            agent {
                docker {
                    image 'docker:24-dind'
                    args  '--privileged -v /var/run/docker.sock:/var/run/docker.sock'
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

        // ─────────────────────────────────────────────
        // ÉTAPE 4 — Déploiement Staging  (main only)
        // ─────────────────────────────────────────────
        stage('Deploy - Staging') {
            when { branch 'main' }
            agent { label 'built-in' }
            steps {
                // withCredentials charge les secrets uniquement
                // dans le scope du bloc — masqués dans les logs Jenkins
                withCredentials([
                    usernamePassword(
                        credentialsId: 'mysql-credentials',
                        usernameVariable: 'MYSQL_USER',
                        passwordVariable: 'MYSQL_PASSWORD'
                    ),
                    string(
                        credentialsId: 'staging-ec2-host',
                        variable: 'STAGING_HOST'
                    )
                ]) {
                    sshagent(credentials: ['ec2-ssh-key']) {

                        sh """
                            scp -o StrictHostKeyChecking=no \
                                PayMyBuddy/initdb/create.sql \
                                ubuntu@${STAGING_HOST}:/tmp/create.sql
                        """

                        sh """
                            ssh -o StrictHostKeyChecking=no ubuntu@${STAGING_HOST} \
                                MYSQL_USER="${MYSQL_USER}" \
                                MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
                                DOCKER_IMAGE="${DOCKERHUB_IMAGE}:${BUILD_NUMBER}" \
                                APP_PORT="${APP_PORT}" \
                                DB_PORT="${DB_PORT}" \
                                'bash -s' << 'ENDSSH'
                                    set -e

                                    docker network inspect paymybuddy-net >/dev/null 2>&1 || \
                                        docker network create paymybuddy-net

                                    # ── Base MySQL ───────────────────────
                                    docker rm -f paymybuddy-db 2>/dev/null || true
                                    docker run -d --name paymybuddy-db \
                                        --network paymybuddy-net \
                                        -e MYSQL_DATABASE=db_paymybuddy \
                                        -e MYSQL_USER="${MYSQL_USER}" \
                                        -e MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
                                        -e MYSQL_ROOT_PASSWORD="${MYSQL_PASSWORD}" \
                                        -p ${DB_PORT}:3306 \
                                        mysql:8.0

                                    echo "Waiting for MySQL..."
                                    sleep 25

                                    docker exec -i paymybuddy-db \
                                        mysql -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
                                        db_paymybuddy < /tmp/create.sql

                                    # ── Application Spring Boot ──────────
                                    docker rm -f paymybuddy-app 2>/dev/null || true
                                    docker run -d --name paymybuddy-app \
                                        --network paymybuddy-net \
                                        -e SPRING_DATASOURCE_URL=jdbc:mysql://paymybuddy-db:3306/db_paymybuddy \
                                        -e SPRING_DATASOURCE_USERNAME="${MYSQL_USER}" \
                                        -e SPRING_DATASOURCE_PASSWORD="${MYSQL_PASSWORD}" \
                                        -p ${APP_PORT}:8080 \
                                        ${DOCKER_IMAGE}
                        """
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // ÉTAPE 5 — Tests de validation Staging
        // ─────────────────────────────────────────────
        stage('Validation Tests - Staging') {
            when { branch 'main' }
            agent {
                docker { image 'curlimages/curl:latest' }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'staging-ec2-host', variable: 'STAGING_HOST')
                ]) {
                    sleep(time: 30, unit: 'SECONDS')
                    sh """
                        curl --retry 10 --retry-delay 5 --retry-connrefused \
                             -f http://${STAGING_HOST}:${APP_PORT}/actuator/health || \
                             (echo "Staging health check FAILED" && exit 1)
                    """
                }
            }
        }

        // ─────────────────────────────────────────────
        // ÉTAPE 6 — Déploiement Production  (main only)
        // ─────────────────────────────────────────────
        stage('Deploy - Production') {
            when { branch 'main' }
            agent { label 'built-in' }
            input {
                message "Déployer en Production ?"
                ok "Go Production 🚀"
            }
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'mysql-credentials',
                        usernameVariable: 'MYSQL_USER',
                        passwordVariable: 'MYSQL_PASSWORD'
                    ),
                    string(
                        credentialsId: 'prod-ec2-host',
                        variable: 'PROD_HOST'
                    )
                ]) {
                    sshagent(credentials: ['ec2-ssh-key']) {

                        sh """
                            scp -o StrictHostKeyChecking=no \
                                PayMyBuddy/initdb/create.sql \
                                ubuntu@${PROD_HOST}:/tmp/create.sql
                        """

                        sh """
                            ssh -o StrictHostKeyChecking=no ubuntu@${PROD_HOST} \
                                MYSQL_USER="${MYSQL_USER}" \
                                MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
                                DOCKER_IMAGE="${DOCKERHUB_IMAGE}:${BUILD_NUMBER}" \
                                APP_PORT="${APP_PORT}" \
                                DB_PORT="${DB_PORT}" \
                                'bash -s' << 'ENDSSH'
                                    set -e

                                    docker network inspect paymybuddy-net >/dev/null 2>&1 || \
                                        docker network create paymybuddy-net

                                    docker rm -f paymybuddy-db 2>/dev/null || true
                                    docker run -d --name paymybuddy-db \
                                        --network paymybuddy-net \
                                        -e MYSQL_DATABASE=db_paymybuddy \
                                        -e MYSQL_USER="${MYSQL_USER}" \
                                        -e MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
                                        -e MYSQL_ROOT_PASSWORD="${MYSQL_PASSWORD}" \
                                        -p ${DB_PORT}:3306 \
                                        mysql:8.0

                                    echo "Waiting for MySQL..."
                                    sleep 25

                                    docker exec -i paymybuddy-db \
                                        mysql -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
                                        db_paymybuddy < /tmp/create.sql

                                    docker rm -f paymybuddy-app 2>/dev/null || true
                                    docker run -d --name paymybuddy-app \
                                        --network paymybuddy-net \
                                        -e SPRING_DATASOURCE_URL=jdbc:mysql://paymybuddy-db:3306/db_paymybuddy \
                                        -e SPRING_DATASOURCE_USERNAME="${MYSQL_USER}" \
                                        -e SPRING_DATASOURCE_PASSWORD="${MYSQL_PASSWORD}" \
                                        -p ${APP_PORT}:8080 \
                                        ${DOCKER_IMAGE}
                        """
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // ÉTAPE 7 — Tests de validation Production
        // ─────────────────────────────────────────────
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
                    sh """
                        curl --retry 10 --retry-delay 5 --retry-connrefused \
                             -f http://${PROD_HOST}:${APP_PORT}/actuator/health || \
                             (echo "Production health check FAILED" && exit 1)
                    """
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // Notifications Slack
    // ─────────────────────────────────────────────
    post {
        success {
            slackSend(
                channel: env.SLACK_CHANNEL,
                color  : 'good',
                message: "✅ *Pipeline SUCCESS* — `${env.JOB_NAME}` #${env.BUILD_NUMBER}\nBranch: `${env.BRANCH_NAME}` | ${env.BUILD_URL}"
            )
        }
        failure {
            slackSend(
                channel: env.SLACK_CHANNEL,
                color  : 'danger',
                message: "❌ *Pipeline FAILED* — `${env.JOB_NAME}` #${env.BUILD_NUMBER}\nBranch: `${env.BRANCH_NAME}` | ${env.BUILD_URL}"
            )
        }
        unstable {
            slackSend(
                channel: env.SLACK_CHANNEL,
                color  : 'warning',
                message: "⚠️ *Pipeline UNSTABLE* — `${env.JOB_NAME}` #${env.BUILD_NUMBER}\nBranch: `${env.BRANCH_NAME}` | ${env.BUILD_URL}"
            )
        }
    }
}
```

---