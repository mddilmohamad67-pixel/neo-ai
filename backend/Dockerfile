FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=8787 \
    NEO_ENV=production \
    NEO_HOST=0.0.0.0

WORKDIR /app

COPY requirements.txt ./
RUN pip install -r requirements.txt

COPY . .

RUN useradd --create-home --uid 10001 neo \
    && mkdir -p /app/logs && chown -R neo:neo /app
USER neo

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8787/health', timeout=3)"

# --forwarded-allow-ips is a trust boundary: set NEO_FORWARDED_IPS to your
# proxy's address (or "*" only when the container is not directly exposed).
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8787} --proxy-headers --forwarded-allow-ips=${NEO_FORWARDED_IPS:-127.0.0.1}"]
