FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
RUN useradd -m app && chown -R app /srv
USER app
EXPOSE 8080
# 로그는 개수·시간만 남기고 요청 본문은 남기지 않습니다(access log도 끔: 경로만 남지만 불필요).
CMD ["sh","-c","uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080} --no-access-log"]
