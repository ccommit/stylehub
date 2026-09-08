// =========================================================================
// StyleHub CI/CD 파이프라인
//
// 흐름: Checkout → Test(도커 컨테이너 내부 Gradle) → JAR 빌드
//       → 운영서버로 SCP 전송 → systemd 서비스 재시작
//
// 운영서버는 Docker가 아니라 java -jar 를 systemd(stylehub.service)로 관리하는
// 네이티브 배포 방식이다 (MySQL/Redis도 서버에 네이티브 설치되어 있음).
//
// 사전 준비 (Jenkins Credentials):
//   - stylehub-deploy-ssh   : SSH Username with private key (운영서버 배포 계정)
//   - deploy-host           : Secret text (운영서버 접속 대상, 예: deploy@1.2.3.4) — 공개 저장소에
//                             실제 호스트를 남기지 않기 위해 코드에 하드코딩하지 않고 credential로 분리
//
// 운영서버 사전 준비 (1회):
//   - /etc/systemd/system/stylehub.service 유닛 파일
//   - /home/ubuntu/stylehub/.env (DB/Redis/OAuth/Toss 실제 값)
//   - ubuntu 계정이 stylehub 서비스를 sudo 로 재시작할 수 있어야 함
//
// Jenkins 에이전트 요구사항: docker CLI + 데몬 접근 권한 (Test/Build 단계용)
// =========================================================================

pipeline {
    agent any

    environment {
        DEPLOY_DIR = '/home/ubuntu/stylehub'
        JAR_NAME   = 'stylehub-0.0.1-SNAPSHOT.jar'
    }

    options {
        timestamps()
        disableConcurrentBuilds()          // 배포 충돌 방지
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
    }

    // 변경이 병합되면 별도 조작 없이 파이프라인이 실행되도록 SCM 을 주기적으로 확인한다.
    //
    // 웹훅(GitHub → Jenkins) 대신 폴링(Jenkins → GitHub)을 쓰는 이유:
    // 웹훅은 GitHub 이 Jenkins 로 요청을 보내는 방향이라 Jenkins 가 외부에서 접근 가능해야 한다.
    // 폴링은 반대 방향이라 Jenkins 가 어디서 돌든 동작하고, 설정이 이 파일에 남아
    // 저장소만 봐도 어떻게 실행되는지 알 수 있다.
    //
    // H/5 의 H 는 해시 기반 분산이다. 모든 잡이 정각에 몰려 폴링하지 않도록 Jenkins 가
    // 잡별로 시작 시점을 흩어준다. 주기 5분은 병합 후 반영까지의 지연과 폴링 비용의 절충이며,
    // 폴링은 변경 여부만 확인하므로 변경이 없으면 빌드를 실행하지 않는다.
    triggers {
        pollSCM('H/5 * * * *')
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

        stage('Build Jar') {
            agent {
                docker {
                    image 'eclipse-temurin:17-jdk-jammy'
                    args '-v $HOME/.gradle:/root/.gradle'
                    reuseNode true
                }
            }
            steps {
                sh 'chmod +x gradlew && ./gradlew clean bootJar -x test --no-daemon'
            }
        }

        // 운영 서버로 나가는 단계이므로 어느 브랜치에서 실행됐는지를 확인한다.
        //
        // 폴링 트리거를 붙이면서 실행 주체가 사람에서 Jenkins 로 바뀌었다. 사람이 실행할 때는
        // 어느 브랜치를 배포할지 사람이 골랐지만, 자동 실행에서는 그 판단이 없다.
        // Multibranch 잡이라면 feature 브랜치 푸시가 그대로 운영 배포로 이어질 수 있다.
        //
        // BRANCH_NAME 이 없는 경우(단일 브랜치 Pipeline 잡)는 잡 설정에서 이미 브랜치가
        // 고정되어 있으므로 그대로 진행한다. 이 조건이 없으면 단일 브랜치 잡에서 배포가
        // 아예 실행되지 않는다.
        stage('Deploy') {
            when {
                anyOf {
                    branch 'develop'
                    expression { env.BRANCH_NAME == null }
                }
            }
            steps {
                sshagent(credentials: ['stylehub-deploy-ssh']) {
                    withCredentials([string(credentialsId: 'deploy-host', variable: 'DEPLOY_HOST')]) {
                        sh '''
                            JAR_FILE=$(ls build/libs/*-SNAPSHOT.jar | grep -v plain)
                            scp -o StrictHostKeyChecking=no "$JAR_FILE" $DEPLOY_HOST:$DEPLOY_DIR/$JAR_NAME.new
                            scp -o StrictHostKeyChecking=no scripts/deploy-remote.sh $DEPLOY_HOST:/tmp/deploy-remote.sh
                            ssh -o StrictHostKeyChecking=no $DEPLOY_HOST bash /tmp/deploy-remote.sh
                        '''
                    }
                }
            }
        }
    }

    post {
        success {
            echo "배포 완료 — 빌드 #${env.BUILD_NUMBER}"
        }
        failure {
            echo "파이프라인 실패 — 빌드 #${env.BUILD_NUMBER} 로그 확인"
        }
    }
}
