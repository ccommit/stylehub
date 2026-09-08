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
//
// 실패 알림 사전 준비:
//   - Jenkins 관리 > 시스템 설정 > E-mail Notification 에 SMTP 서버 구성
//   - 구성되지 않아도 파이프라인은 그대로 동작한다 (알림 전송 실패는 콘솔에만 남김)
// =========================================================================

pipeline {
    agent any

    environment {
        DEPLOY_DIR = '/home/ubuntu/stylehub'
        JAR_NAME   = 'stylehub-0.0.1-SNAPSHOT.jar'
        // 파이프라인 실패 알림 수신 주소. 커밋 작성자 정보로 이미 공개된 주소라 코드에 둔다.
        ALERT_EMAIL = 'try3982@kakao.com'
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

        stage('Deploy') {
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

    // 실패를 콘솔 로그로만 남기면 사람이 Jenkins 화면을 열어보기 전까지 알 수 없다.
    //
    // 배포 실패 시 이전 버전으로 롤백되지만(scripts/deploy-remote.sh), 롤백은 복구이고
    // 알림은 인지라 서로 다른 문제를 푼다. 롤백되면 서비스는 살아나지만 새 버전은
    // 반영되지 않은 상태로 남는데, 이를 모르면 배포됐다고 생각하는 것과 실제 상태가 어긋난다.
    // 롤백 자체가 실패해 서비스가 내려간 경우도 알림 없이는 로그에만 남는다.
    //
    post {
        success {
            echo "배포 완료 — 빌드 #${env.BUILD_NUMBER}"
        }
        failure {
            echo "파이프라인 실패 — 빌드 #${env.BUILD_NUMBER} 로그 확인"
            script {
                try {
                    mail(
                        to: ALERT_EMAIL,
                        subject: "[StyleHub] 파이프라인 실패 — 빌드 #${env.BUILD_NUMBER}",
                        body: """\
빌드 #${env.BUILD_NUMBER} 이(가) 실패했습니다.

브랜치: ${env.GIT_BRANCH ?: '확인 필요'}
커밋:   ${env.GIT_COMMIT ?: '확인 필요'}
로그:   ${env.BUILD_URL}console

Deploy 단계에서 실패한 경우 운영 서버는 이전 버전으로 롤백된 상태입니다.
새 버전은 반영되지 않았으므로 원인을 확인한 뒤 다시 배포해야 합니다.
롤백까지 실패했다면 서비스가 내려가 있으므로 즉시 확인이 필요합니다.
"""
                    )
                } catch (Exception e) {
                    // 알림 전송 실패가 빌드 결과를 덮어쓰지 않도록 삼킨다.
                    // SMTP 가 구성되지 않은 환경에서도 파이프라인 자체는 그대로 동작해야 하고,
                    // 알림이 못 나갔다는 사실은 콘솔에 남는다.
                    echo "실패 알림 전송 실패: ${e.message}"
                }
            }
        }
    }
}
