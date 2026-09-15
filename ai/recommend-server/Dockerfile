# MICON 추천 서버 (FastAPI, 포트 8788). CPU 전용 — EC2 에 GPU 가 없다.
#   docker build -t micon-recommend .
#   docker run -d --name micon-recommend -p 8788:8788 micon-recommend
# 이미지에 들어가는 것: 코드, 카탈로그 인덱스(recommender-assets/catalog-index, Git LFS), 모델 가중치 116 MB(빌드 시 다운로드).
# 들어가지 않는 것: 원본 mp3·분리 보컬·사용자 녹음(요청 body 로만 들어와 jobs/ 에 잠시 머문다).
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 HF_HUB_DISABLE_TELEMETRY=1
WORKDIR /app

# 1) torch 3종을 CPU 빌드로 먼저 깐다(버전은 requirements.txt 와 같아야 한다 — 그래야 2)에서 다시 안 받는다).
#    기본 PyPI 의 리눅스 torch 는 CUDA 포함 2.5 GB 라 쓰지 않는다.
COPY requirements.txt .
RUN pip install torch==2.13.0 torchaudio==2.11.0 torchvision==0.28.0 --index-url https://download.pytorch.org/whl/cpu \
 && grep -vE '^(demucs|pytest)' requirements.txt > /tmp/requirements.server.txt \
 && pip install -r /tmp/requirements.server.txt

# 2) 코드 + 카탈로그 인덱스. .dockerignore 가 .venv·models·workspace·tests 를 뺀다.
COPY . .

# 3) 가중치 다운로드(SHA-256 검증) + 컨테이너 경로로 workspace/recommender/config.json 생성.
#    시작 시가 아니라 빌드 시에 해 두면 배포 순간에 Hugging Face 가 느리거나 죽어도 컨테이너는 바로 뜬다.
RUN python setup_recommender.py

# 4) BE 음성 분석 작업의 원음·job.json. 소프트 1h / 강제 24h 뒤 삭제된다. 재시작에도 진행 상태를 남기려면 볼륨을 붙인다.
#    운영 환경변수: AI_SERVICE_TOKEN, MICON_BE_CALLBACK_URL, RECOMMEND_CONCURRENCY(기본 2), RECOMMEND_AUDIO_TTL_SECONDS(3600).
VOLUME ["/app/workspace/analysis"]

EXPOSE 8788
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8788/health', timeout=4)" || exit 1
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8788"]
