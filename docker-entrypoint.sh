#!/bin/sh
# 컨테이너 진입점.
#
# 존재 이유는 하나다 — **에이전트 jar 이 없으면 -javaagent 를 떼어낸다.**
#
# 왜 필요한가:
#   docker compose 는 이미지가 이미 있으면 build arg 가 바뀌어도 재빌드하지 않는다.
#   그래서 OTel 없이 한 번 띄운 뒤 docker-compose-otel.yml 을 얹으면
#   JAVA_TOOL_OPTIONS 에 -javaagent 는 주입되는데 이미지 안에 jar 은 없는 상태가 된다.
#   이때 JVM 은 다음과 같이 **기동 자체에 실패한다**.
#     Error opening zip file or JAR manifest missing : /otel/opentelemetry-javaagent.jar
#     agent library failed Agent_OnLoad: instrument
#
#   관측성을 켜려다 서비스를 내리는 것은 옵트인 설계의 취지에 반한다.
#   그래서 여기서 방어하고, 대신 이유를 로그로 분명히 알린다.
#
# 해결법을 아는 사람에게는 한 줄이면 되지만(--build 로 재빌드),
# 템플릿 사용자가 그 한 줄을 모른 채 앱이 죽는 상황을 겪게 두지 않는다.

set -e

AGENT_JAR=/otel/opentelemetry-javaagent.jar

case "${JAVA_TOOL_OPTIONS:-}" in
  *-javaagent:${AGENT_JAR}*)
    if [ ! -s "$AGENT_JAR" ]; then
      echo "WARN: -javaagent 가 지정됐으나 ${AGENT_JAR} 이 없습니다. 에이전트 없이 기동합니다." >&2
      echo "WARN: 이미지가 OTEL_ENABLED=false 로 빌드된 상태입니다. 관측성을 쓰려면 재빌드하세요:" >&2
      echo "WARN:   docker compose -f docker-compose-prod.yml -f docker-compose-otel.yml --profile app up -d --build" >&2
      # -javaagent 지정만 제거한다. 다른 JVM 옵션은 보존한다.
      JAVA_TOOL_OPTIONS=$(printf '%s' "$JAVA_TOOL_OPTIONS" | sed "s|-javaagent:${AGENT_JAR}||")
      export JAVA_TOOL_OPTIONS
    fi
    ;;
esac

exec "$@"
