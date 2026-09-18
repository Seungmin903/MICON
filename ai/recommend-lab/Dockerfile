FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 LAB_HOST=0.0.0.0
WORKDIR /opt/micon
COPY ai/recommend-lab/requirements.txt /tmp/lab-requirements.txt
RUN pip install --no-cache-dir -r /tmp/lab-requirements.txt && useradd --create-home --uid 10001 lab
COPY ai/recommend-server/timbre_recommender ./ai/recommend-server/timbre_recommender
COPY ai/recommend-server/pitch_profile.py ./ai/recommend-server/pitch_profile.py
COPY ai/recommend-lab/app.py ai/recommend-lab/engine.py ai/recommend-lab/repository.py ai/recommend-lab/adapters.py ./ai/recommend-lab/
COPY ai/recommend-lab/static ./ai/recommend-lab/static
USER lab
EXPOSE 8790
CMD ["python", "ai/recommend-lab/app.py"]

