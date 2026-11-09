"""실행 중인 로컬 브로커에서 정상 수신과 세션별 재접속 결과를 검증한다."""
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
LOGS = ROOT / ".local-study" / "mqtt-results"
LOGS.mkdir(parents=True, exist_ok=True)
ENV = dict(os.environ, LAB_TRANSPORT="mqtt", SERVER_PORT="0")
ENV.setdefault("MQTT_BROKER_URI", "tcp://127.0.0.1:18883")
RUN_ID = str(time.time_ns())
COLLECTOR = ROOT / "tcp-collector/build/libs/tcp-collector-0.1.0-SNAPSHOT.jar"
SIMULATOR = ROOT / "device-simulator/build/libs/device-simulator-0.1.0-SNAPSHOT.jar"


def wait_for(path, expected, process):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if expected in path.read_text():
            return
        if process.poll() is not None:
            raise AssertionError(f"수집 앱이 종료됐습니다. 로그={path}")
        time.sleep(0.1)
    raise AssertionError(f"예상 로그가 없습니다. 내용={expected}, 로그={path}")


def stop(process):
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def publish(env, message_id):
    path = LOGS / f"{message_id}-publish.log"
    with path.open("w") as output:
        subprocess.run(
            ["java", "-jar", str(SIMULATOR)],
            cwd=ROOT,
            env=dict(env, MQTT_CLIENT_ID=f"device-{RUN_ID}", MQTT_MESSAGE_ID=message_id),
            stdout=output, stderr=subprocess.STDOUT, check=True, timeout=20,
        )


def scenario(clean):
    name = "clean" if clean else "persistent"
    env = dict(ENV, MQTT_CLIENT_ID=f"collector-{name}-{RUN_ID}",
               MQTT_TOPIC=f"lab/check/{RUN_ID}/{name}",
               MQTT_CLEAN_SESSION=str(clean).lower())
    first = LOGS / f"{name}-before.log"
    with first.open("w") as output:
        process = subprocess.Popen(["java", "-jar", str(COLLECTOR)], cwd=ROOT,
                                   env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            wait_for(first, "MQTT 구독을 시작했습니다.", process)
            publish(env, name + "-online")
            wait_for(first, f"메시지 ID={name}-online,", process)
        finally:
            stop(process)

    for number in range(1, 4):
        publish(env, f"{name}-offline-{number}")

    resumed = LOGS / f"{name}-after.log"
    with resumed.open("w") as output:
        process = subprocess.Popen(["java", "-jar", str(COLLECTOR)], cwd=ROOT,
                                   env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            wait_for(resumed, "MQTT 구독을 시작했습니다.", process)
            publish(env, name + "-resumed")
            wait_for(resumed, f"메시지 ID={name}-resumed,", process)
            if clean:
                time.sleep(2)
                assert "-offline-" not in resumed.read_text(), "새 세션에 이전 메시지가 도착했습니다."
            else:
                for number in range(1, 4):
                    wait_for(resumed, f"메시지 ID={name}-offline-{number},", process)
        finally:
            stop(process)
    print(f"검증 완료. 세션 초기화={clean}, 로그={resumed}")


if __name__ == "__main__":
    scenario(False)
    scenario(True)
