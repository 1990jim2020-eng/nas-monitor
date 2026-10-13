FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    API_PORT=8765 \
    HEALTH_PORT=8766 \
    BACKEND_ROOT=/app/data

WORKDIR /app

COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt \
    && groupadd --gid 10001 nas-monitor \
    && useradd --uid 10001 --gid nas-monitor --no-create-home --home-dir /app nas-monitor

# Copy source only; never copy deployment secrets or runtime state.
COPY --chown=nas-monitor:nas-monitor *.py ./
COPY --chown=nas-monitor:nas-monitor collectors/ ./collectors/
COPY --chown=nas-monitor:nas-monitor web/ ./web/
RUN mkdir -p /app/data /app/data/shadow \
    && chown -R nas-monitor:nas-monitor /app

USER nas-monitor:nas-monitor
EXPOSE 8765
CMD ["python3", "api_server.py"]
