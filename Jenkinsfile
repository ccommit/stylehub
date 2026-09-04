// =========================================================================
// StyleHub CI/CD 파이프라인
//
// 흐름: Checkout → Test(도커 컨테이너 내부 Gradle) → 이미지 빌드
//       → GHCR push → 운영서버로 SSH 배포
//
// 사전 준비 (Jenkins Credentials):
//   - ghcr-credentials      : Username/Password (GitHub 계정 / write:packages PAT)
//   - stylehub-deploy-ssh   : SSH Username with private key (운영서버 배포 계정)
//   - deploy-host           : Secret text (운영서버 접속 대상, 예: deploy@1.2.3.4) — 공개 저장소에
//                             실제 호스트를 남기지 않기 위해 코드에 하드코딩하지 않고 credential로 분리
//
// Jenkins 에이전트 요구사항: docker CLI + 데몬 접근 권한
// =========================================================================

pipeline {
    agent any

    environment {
        REGISTRY    = 'ghcr.io'
        IMAGE_NAME  = 'ccommit/stylehub'
        IMAGE       = "${REGISTRY}/${IMAGE_NAME}"
        TAG         = "${env.BUILD_NUMBER}"
        DEPLOY_DIR  = '/opt/stylehub'
    }

    options {
        timestamps()
        disableConcurrentBuilds()          // 배포 충돌 방지
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        // 테스트를 도커 컨테이너 안에서 실행 → Jenkins 호스트에 JDK/Gradle 미설치여도 됨
        stage('Test') {
            agent {
                docker {
                    image 'eclipse-temurin:17-jdk-jammy'
                    // 의존성 캐시 재사용 + 테스트가 로컬(Docker Desktop 호스트)의 Redis 에 붙도록 지정
                    // (MySQL 은 테스트 시 H2 로 자동 폴백되어 별도 지정 불필요)
                    args '-v $HOME/.gradle:/root/.gradle -e SPRING_DATA_REDIS_HOST=host.docker.internal'
                    reuseNode true
                }
            }
            steps {
                sh 'chmod +x gradlew && ./gradlew clean test --no-daemon'
            }
            post {
                always {
                    junit testResults: 'build/test-results/test/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Build Image') {
            steps {
                sh 'docker build -t $IMAGE:$TAG -t $IMAGE:latest .'
            }
        }

        stage('Push to GHCR') {
            steps {
                withCredentials([usernamePassword(
                        credentialsId: 'ghcr-credentials',
                        usernameVariable: 'GHCR_USER',
                        passwordVariable: 'GHCR_TOKEN')]) {
                    sh '''
                        echo "$GHCR_TOKEN" | docker login $REGISTRY -u "$GHCR_USER" --password-stdin
                        docker push $IMAGE:$TAG
                        docker push $IMAGE:latest
                        docker logout $REGISTRY
                    '''
                }
            }
        }

        stage('Deploy') {
            steps {
                sshagent(credentials: ['stylehub-deploy-ssh']) {
                    withCredentials([
                            usernamePassword(
                                    credentialsId: 'ghcr-credentials',
                                    usernameVariable: 'GHCR_USER',
                                    passwordVariable: 'GHCR_TOKEN'),
                            string(credentialsId: 'deploy-host', variable: 'DEPLOY_HOST')
                    ]) {
                        // 운영서버에서 방금 push 한 태그를 pull 하고 app 만 교체 기동.
                        // --password-stdin 으로 토큰이 프로세스 인자에 남지 않게 한다.
                        sh '''
                            ssh -o StrictHostKeyChecking=no $DEPLOY_HOST bash -s <<REMOTE
                                set -e
                                echo "$GHCR_TOKEN" | docker login $REGISTRY -u "$GHCR_USER" --password-stdin
                                cd $DEPLOY_DIR
                                export IMAGE_TAG=$TAG
                                docker compose pull app
                                docker compose up -d app
                                docker logout $REGISTRY
                                docker image prune -f
REMOTE
                        '''
                    }
                }
            }
        }
    }

    post {
        success {
            echo "배포 완료: ${IMAGE}:${TAG}"
        }
        failure {
            echo "파이프라인 실패 — 빌드 #${env.BUILD_NUMBER} 로그 확인"
        }
        always {
            // 빌드 호스트에 쌓인 dangling 이미지 정리
            sh 'docker image prune -f || true'
        }
    }
}
