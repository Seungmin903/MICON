# MICON 추천 서버 (FastAPI, 포트 8788). 저장소의 ai/ 디렉터리를 build context 로 사용한다.
#   docker build -f recommend-server/Dockerfile -t micon-recommendation .
# 이미지에 들어가는 것: 코드, 카탈로그 인덱스(recommender-assets/catalog-index, Git LFS), 모델 가중치 116 MB(빌드 시 다운로드).
# 들어가지 않는 것: 원본 mp3·분리 보컬·사용자 녹음(요청 body 로만 들어와 jobs/ 에 잠시 머문다).
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HUB_DISABLE_TELEMETRY=1 \
    NUMBA_CACHE_DIR=/tmp/numba-cache
WORKDIR /app

# 1) torch 3종을 CPU 빌드로 먼저 깐다(버전은 requirements.txt 와 같아야 한다 — 그래야 2)에서 다시 안 받는다).
#    기본 PyPI 의 리눅스 torch 는 CUDA 포함 2.5 GB 라 쓰지 않는다.
COPY recommend-server/requirements.txt .
RUN pip install torch==2.7.1 torchaudio==2.7.1 torchvision==0.22.1 --index-url https://download.pytorch.org/whl/cpu \
 && grep -vE '^(demucs|pytest)' requirements.txt > /tmp/requirements.server.txt \
 && pip install -r /tmp/requirements.server.txt

# 2) 서버 코드·카탈로그 인덱스와 RANGE worker. ai/.dockerignore 가 로컬 산출물을 뺀다.
COPY recommend-server/ .
COPY vocal-scope/model-server /vocal-scope/model-server

# 3) 가중치 다운로드(SHA-256 검증) + 컨테이너 경로로 workspace/recommender/config.json 생성.
#    시작 시가 아니라 빌드 시에 해 두면 배포 순간에 Hugging Face 가 느리거나 죽어도 컨테이너는 바로 뜬다.
RUN python setup_recommender.py

# 4) 비-root 실행과 영속 작업 디렉터리. 서비스 토큰은 entrypoint 가 Docker secret 에서 읽는다.
RUN groupadd --gid 10001 micon \
 && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin micon \
 && mkdir -p /app/workspace/analysis /app/workspace/recommender/jobs \
 && chown -R 10001:10001 /app/workspace \
 && chmod 0555 /app/docker-entrypoint.sh

VOLUME ["/app/workspace/analysis", "/app/workspace/recommender/jobs"]

EXPOSE 8788
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8788/health', timeout=4)" || exit 1
USER 10001:10001
ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8788"]
