FROM python:3.12.14-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
RUN apt-get update && apt-get install -y --no-install-recommends iputils-ping ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY mydesk ./mydesk
COPY frontend ./frontend
RUN useradd --uid 10001 --create-home mydesk && mkdir /data && chown mydesk:mydesk /data
USER mydesk
EXPOSE 8787 8443
CMD ["python","-m","mydesk","--host","0.0.0.0","--port","8787","--data","/data","--cert","/data/tls/server.pem","--key","/data/tls/server-key.pem","--tls-port","8443"]
