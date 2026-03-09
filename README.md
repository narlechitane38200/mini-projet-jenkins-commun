# 🚀 Pipeline CI/CD Jenkins — PayMyBuddy

## 📋 Description du projet

Ce projet met en œuvre une pipeline d'intégration continue (CI) et de déploiement continu (CD) avec Jenkins pour l'application Spring Boot **PayMyBuddy**. La pipeline automatise les tests, l'analyse de qualité du code, la construction de l'image Docker, le déploiement sur les environnements de staging et de production, ainsi que les notifications Slack.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        JENKINS PIPELINE                      │
├──────────┬──────────┬──────────┬────────────┬───────────────┤
│  Tests   │ SonarCloud│  Docker  │  Staging   │  Production   │
│  Maven   │  Quality  │  Build   │  Deploy    │  Deploy       │
│          │  Analysis │  & Push  │  & Valid.  │  & Valid.     │
└──────────┴──────────┴──────────┴────────────┴───────────────┘
                                                        │
                                               Notification Slack
```

```
Infrastructure AWS :
┌─────────────────┐     SSH/SCP      ┌──────────────────────┐
│  Jenkins Server │ ──────────────►  │   EC2 Staging        │
│  (built-in)     │                  │   paymybuddy-db      │
└─────────────────┘                  │   paymybuddy-app     │
                                     └──────────────────────┘
                    SSH/SCP          ┌──────────────────────┐
                   ──────────────►   │   EC2 Production     │
                                     │   paymybuddy-db      │
                                     │   paymybuddy-app     │
                                     └──────────────────────┘
```

---

## 🔄 Étapes de la Pipeline

| # | Stage | Agent Docker | Branches |
|---|-------|-------------|----------|
| 1 | Tests | `maven:3.9-eclipse-temurin-17` | Toutes |
| 2 | Code Quality - SonarCloud | `maven:3.9-eclipse-temurin-17` | Toutes |
| 3 | Build & Push Docker Image | `docker:24` | Toutes |
| 4 | Deploy - Staging | `built-in` | Toutes |
| 5 | Validation Tests - Staging | `curlimages/curl:latest` | Toutes |
| 6 | Deploy - Production | `built-in` | `main` uniquement |
| 7 | Validation Tests - Production | `curlimages/curl:latest` | `main` uniquement |

### Modèle Gitflow

```
origin/main    ──► Tests ──► SonarCloud ──► Docker ──► Staging ──► [input] ──► Production
autres branches ──► Tests ──► SonarCloud ──► Docker ──► Staging
```
> Le déploiement en Production nécessite une **validation manuelle** via l'interface Jenkins (`input`).

---

## ⚙️ Variables d'environnement

| Variable | Description |
|----------|-------------|
| `DOCKERHUB_IMAGE` | Image DockerHub : `narlechitane38200/paymybuddy` |
| `SONAR_ORG` | Organisation SonarCloud |
| `SONAR_PROJECT_KEY` | Clé du projet SonarCloud |
| `SLACK_CHANNEL` | Canal Slack pour les notifications |
| `APP_PORT` | Port de l'application (`8080`) |
| `DB_PORT` | Port MySQL (`3306`) |
| `STAGING_HOST` | DNS de l'EC2 Staging |
| `PROD_HOST` | DNS de l'EC2 Production |

---

## 🔐 Credentials Jenkins requis

| Credential ID | Type | Utilisation |
|---------------|------|-------------|
| `dockerhub-credentials` | Username/Password | Push image DockerHub |
| `sonarcloud-token` | Secret String | Analyse SonarCloud |
| `mysql-credentials` | Username/Password | Déploiement base de données |
| `ec2-ssh-key` | SSH Private Key | Connexion SSH aux EC2 |

---

## 🛠️ Prérequis

### Jenkins
- Jenkins avec les plugins suivants :
  - **Docker Pipeline**
  - **SSH Agent**
  - **SonarQube Scanner**
  - **Slack Notification**
  - **JUnit**

### Serveurs EC2 (Staging & Production)
- Docker installé
- Utilisateur `ubuntu` avec accès sudo
- Port `22` ouvert pour le serveur Jenkins
- Port `8080` ouvert pour les tests de validation

### SonarCloud
- Organisation et projet configurés sur [sonarcloud.io](https://sonarcloud.io)
- Token d'accès généré

### DockerHub
- Compte avec repository `narlechitane38200/paymybuddy`

---

## 📦 Structure du projet

```
PayMyBuddy/
├── src/
│   ├── main/java/
│   └── test/java/
├── initdb/
│   └── create.sql          # Script d'initialisation de la base de données
├── Dockerfile
├── pom.xml
└── Jenkinsfile
```

---

## 🚢 Stratégie de déploiement

Le déploiement repose sur une approche **script via SCP** :

1. Jenkins génère un script `deploy.sh` localement avec `writeFile` (les variables sont interpolées côté Jenkins)
2. Le script SQL `create.sql` et le script de déploiement sont copiés sur l'EC2 via `scp -B`
3. Le script est exécuté sur l'EC2 via SSH

```
Jenkins (local)                    EC2 (distant)
──────────────                    ──────────────
writeFile deploy.sh
      │
      ▼
scp create.sql ──────────────────► /tmp/create.sql
scp deploy.sh  ──────────────────► /tmp/deploy.sh
ssh execute    ──────────────────► chmod +x && ./deploy.sh
                                          │
                                          ▼
                                   docker network create
                                   docker run mysql:8.0
                                   docker run paymybuddy-app
```

### Réseau Docker

Les deux conteneurs sont déployés sur le réseau `paymybuddy-net` pour permettre la communication par hostname :

```
paymybuddy-net
├── paymybuddy-db   (mysql:8.0)
└── paymybuddy-app  (narlechitane38200/paymybuddy)
```

L'application se connecte à la base via : `jdbc:mysql://paymybuddy-db:3306/db_paymybuddy`

---

## ✅ Tests de validation

Les tests de validation utilisent `curl` avec retry automatique :

```bash
curl --retry 10 --retry-delay 5 --retry-connrefused \
    -f http://$HOST:$APP_PORT/actuator/health
```

L'endpoint `/actuator/health` de Spring Boot doit retourner un HTTP 200 pour valider le déploiement.

---

## 🔔 Notifications Slack

Une notification est envoyée en fin de pipeline selon le statut :

| Statut | Couleur | Message |
|--------|---------|---------|
| ✅ SUCCESS | Vert | Pipeline SUCCESS avec lien vers le build |
| ❌ FAILURE | Rouge | Pipeline FAILED avec lien vers le build |
| ⚠️ UNSTABLE | Orange | Pipeline UNSTABLE |

---

## 🗄️ Base de données

### Initialisation

Le fichier `create.sql` est monté dans `/docker-entrypoint-initdb.d/` du conteneur MySQL. Il est exécuté automatiquement au premier démarrage.

> ⚠️ **Important** : Le fichier `create.sql` doit utiliser `CREATE DATABASE IF NOT EXISTS` pour éviter les conflits avec la variable `MYSQL_DATABASE` de Docker.

```sql
CREATE DATABASE IF NOT EXISTS db_paymybuddy;
USE db_paymybuddy;
-- ...
```

### Réinitialisation

À chaque déploiement, le conteneur est recréé avec `docker rm -fv` pour supprimer le volume anonyme et forcer la réapplication du `create.sql`.

---

## 🚀 Lancement de la pipeline

1. Pousser du code sur n'importe quelle branche → déclenche Tests + SonarCloud + Docker Build
2. Pousser sur `main` → déclenche le pipeline complet incluant Staging et Production
3. Le déploiement en Production nécessite une **validation manuelle** via l'interface Jenkins

---

## 👤 Auteur

Radouane — Mini Projet Jenkins
