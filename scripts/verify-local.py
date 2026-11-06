#!/usr/bin/env python3
"""Run the built JAR over verified TLS, exercise authentication and sync locally."""
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    directory = ROOT / "backend/build" / ("local-verification-" + uuid.uuid4().hex)
    directory.mkdir(parents=True)
    environment = os.environ.copy()
    for name in ("POSTGRES_PASSWORD", "DB_RUNTIME_PASSWORD", "DB_MIGRATOR_PASSWORD", "DB_INVITE_PASSWORD", "TLS_KEY_STORE_PASSWORD"):
        environment[name] = secrets.token_urlsafe(32)
    key = directory / "jwt.key"
    key.write_bytes(secrets.token_bytes(32))
    key.chmod(0o600)
    keystore = directory / "tls.p12"
    subprocess.run(["keytool", "-genkeypair", "-alias", "finance", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "2", "-storetype", "PKCS12", "-keystore", str(keystore),
                    "-storepass:env", "TLS_KEY_STORE_PASSWORD", "-dname", "CN=localhost",
                    "-ext", "SAN=ip:127.0.0.1,dns:localhost"], env=environment, capture_output=True, check=True)
    certificate = subprocess.check_output(["keytool", "-exportcert", "-rfc", "-alias", "finance", "-keystore", str(keystore),
                                           "-storepass:env", "TLS_KEY_STORE_PASSWORD"], env=environment).decode()
    (directory / "tls-ca.pem").write_text(certificate)
    tls = ssl.create_default_context(cadata=certificate)
    container = None
    application = None
    mail_container = None
    try:
        container = subprocess.check_output(["docker", "run", "--rm", "-d", "-p", "127.0.0.1::5432",
                    "-e", "POSTGRES_USER=finance_bootstrap", "-e", "POSTGRES_DB=finance",
                    *[part for name in ("POSTGRES_PASSWORD", "DB_RUNTIME_PASSWORD", "DB_MIGRATOR_PASSWORD", "DB_INVITE_PASSWORD") for part in ("-e", name)],
                    "--mount", f"type=bind,source={ROOT}/infra/postgres,target=/docker-entrypoint-initdb.d,readonly",
                    "postgres:18.3"], env=environment, text=True).strip()
        for _ in range(150):
            logs = subprocess.run(["docker", "logs", container], capture_output=True, text=True)
            if "PostgreSQL init process complete" in logs.stdout:
                break
            time.sleep(0.2)
        else:
            raise RuntimeError("Database initialization timeout")
        mail_container=subprocess.check_output(["docker","run","--rm","-d","-p","127.0.0.1::1025","-p","127.0.0.1::8025","-e","MP_DISABLE_VERSION_CHECK=true","ghcr.io/axllent/mailpit:v1.31.3@sha256:ed9b00c609e77e99c79b93f1178255ebc271868920f2c69a8d166bd5634ed10d"],text=True).strip()
        mail_inspect=json.loads(subprocess.check_output(["docker","inspect",mail_container],text=True))[0]
        mail_ports=mail_inspect["NetworkSettings"]["Ports"]
        mail_api="http://127.0.0.1:"+mail_ports["8025/tcp"][0]["HostPort"]
        environment.update(SMTP_HOST="127.0.0.1",SMTP_PORT=mail_ports["1025/tcp"][0]["HostPort"],SMTP_USERNAME="local",SMTP_PASSWORD="local",MAIL_FROM="no-reply@finance.local",MAIL_PUBLIC_URL="https://localhost")
        inspect = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
        port = inspect["NetworkSettings"]["Ports"]["5432/tcp"][0]["HostPort"]
        environment.update(DB_URL=f"jdbc:postgresql://127.0.0.1:{port}/finance", DB_RUNTIME_USER="finance_runtime",
                           DB_MIGRATOR_USER="finance_migrator", JWT_KEY_FILE=str(key), TLS_KEY_STORE=str(keystore),
                           TRUSTED_PROXY_REGEX=r"127\.0\.0\.1")
        jar = ROOT / "backend/build/libs/finance-tracker-backend-0.1.0-SNAPSHOT.jar"
        logfile = directory / "backend.log"
        with logfile.open("w") as output:
            application = subprocess.Popen(["java", "-jar", str(jar), "--spring.profiles.active=prod", "--server.port=0",
                                             "--app.auth.requests-per-minute=10000", "--spring.mail.properties.mail.smtp.auth=false",
                                             "--spring.mail.properties.mail.smtp.starttls.enable=false", "--spring.mail.properties.mail.smtp.starttls.required=false"],
                                            env=environment, stdout=output, stderr=subprocess.STDOUT)
            for _ in range(150):
                match = re.search(r"Tomcat started on port (\d+) \(https\)", logfile.read_text())
                if match:
                    break
                if application.poll() is not None:
                    raise RuntimeError("TLS application startup failed; inspect local verification log")
                time.sleep(0.2)
            else:
                raise RuntimeError("Application startup timeout")
            base = f"https://127.0.0.1:{match[1]}"

            epoch=None

            def request(method, path, body=None, access=None, expected=200):
                headers = {"Content-Type": "application/json"} if body is not None else {}
                if path.startswith("/api/v1/sync/") and epoch:
                    headers["X-Recovery-Epoch"]=epoch
                if access:
                    headers["Authorization"] = "Bearer " + access
                req = urllib.request.Request(base + path, method=method, headers=headers,
                                             data=None if body is None else json.dumps(body).encode())
                try:
                    with urllib.request.urlopen(req, context=tls, timeout=15) as response:
                        status, payload = response.status, response.read()
                except urllib.error.HTTPError as error:
                    status, payload = error.code, error.read()
                assert status == expected, (method, path, status, expected)
                return json.loads(payload) if payload else None

            request("GET", "/actuator/health")
            try:
                with urllib.request.urlopen(base.replace("https:", "http:") + "/actuator/health", timeout=5) as response:
                    raise AssertionError("Plain HTTP unexpectedly accepted")
            except (urllib.error.URLError, http.client.RemoteDisconnected):
                pass

            administrator = environment.copy()
            administrator.update(INVITE_DB_URL=environment["DB_URL"], INVITE_DB_USER="finance_invite_admin",
                                 INVITE_DB_PASSWORD=environment["DB_INVITE_PASSWORD"])

            def mail_token(email,purpose):
                for _ in range(100):
                    with urllib.request.urlopen(mail_api+"/api/v1/messages",timeout=5) as response:
                        messages=json.load(response)["messages"]
                    for message in messages:
                        if purpose not in message["Subject"] or not any(to["Address"].lower()==email.lower() for to in message["To"]):
                            continue
                        with urllib.request.urlopen(mail_api+"/api/v1/message/"+message["ID"],timeout=5) as response:
                            text=json.load(response)["Text"]
                        return re.search(r"#token=([A-Za-z0-9_-]{43})",text)[1]
                    time.sleep(0.05)
                raise RuntimeError("Local SMTP message not delivered")

            def bootstrap(token):
                nonlocal epoch
                status=request("GET","/api/v1/sync/status",access=token)
                epoch=status["epoch"]
                if not status["requiresFullResync"]:
                    return
                session=request("POST","/api/v1/sync/recovery/start",access=token)
                path="/api/v1/sync/recovery/"+session["recoveryId"]
                cursor=0
                while True:
                    page=request("GET",path+"/pull?cursor="+str(cursor),access=token)
                    cursor=page["nextCursor"]
                    if not page["hasMore"]:
                        break
                request("POST",path+"/ack",dict(lastCursor=cursor,localCopyPreserved=True,pendingOperationsQuarantined=True),token,204)

            def register():
                invitation = subprocess.check_output(["java", "-jar", str(jar), "create-invite"],env=administrator,text=True).strip()
                email,password,device=str(uuid.uuid4())+"@example.test",secrets.token_urlsafe(24),str(uuid.uuid4())
                request("POST","/api/v1/auth/register",dict(inviteCode=invitation,email=email,password=password,baseCurrencyCode="RUB",deviceId=device),expected=201)
                request("POST","/api/v1/auth/login",dict(email=email,password=password,deviceId=device),expected=401)
                token=mail_token(email,"VERIFY_EMAIL")
                request("POST","/api/v1/auth/email/verify",dict(token=token),expected=204)
                request("POST","/api/v1/auth/email/verify",dict(token=token),expected=400)
                tokens=request("POST","/api/v1/auth/login",dict(email=email,password=password,deviceId=device))
                bootstrap(tokens["accessToken"])
                return email,password,tokens

            email, password, first = register()
            _, _, stranger = register()
            second = request("POST", "/api/v1/auth/login", dict(email=email, password=password, deviceId=str(uuid.uuid4())))
            access, second_access = first["accessToken"], second["accessToken"]
            bootstrap(second_access)
            category, expense = str(uuid.uuid4()), str(uuid.uuid4())

            def operation(kind, entity, action, data=None, version=None):
                return dict(epoch=epoch,operationId=str(uuid.uuid4()), entityType=kind, entityId=entity,
                            action=action, expectedVersion=version, data=data)

            def push(op, token=access):
                return request("POST", "/api/v1/sync/push", {"operations": [op]}, token)["results"][0]

            assert push(operation("CATEGORY", category, "CREATE", {"name": "Travel"}))["status"] == "APPLIED"
            command = operation("EXPENSE", expense, "CREATE", dict(categoryId=category, amountMinor=12550, occurredOn="2026-10-31"))
            assert push(command)["status"] == "APPLIED"
            assert push(command)["status"] == "ALREADY_APPLIED"
            assert push(operation("EXPENSE", expense, "UPDATE", {"note": "Device two"}, 1), second_access)["status"] == "APPLIED"
            assert push(operation("EXPENSE", expense, "UPDATE", {"amountMinor": 1}, 1))["status"] == "CONFLICT"
            assert push(operation("EXPENSE", expense, "DELETE", version=2))["status"] == "APPLIED"
            cursor, seen = 0, []
            while True:
                page = request("GET", f"/api/v1/sync/pull?cursor={cursor}&limit=1", access=second_access)
                seen.extend(page["changes"])
                cursor = page["nextCursor"]
                if not page["hasMore"]:
                    break
            assert len(seen) == 7 and seen[-1]["operation"] == "DELETE" and seen[-1]["snapshot"] is None
            assert len(request("GET", "/api/v1/sync/pull", access=stranger["accessToken"])["changes"]) == 3
            request("POST", "/api/v1/auth/logout", access=second_access, expected=204)
            request("GET", "/api/v1/me", access=second_access, expected=401)
            profile = request("GET", "/api/v1/me", access=access)
            user_id = str(uuid.UUID(profile["id"]))
            plan_sql = ("BEGIN; SET LOCAL app.current_user_id='" + user_id + "'; "
                        "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT * FROM app.sync_changes "
                        "WHERE user_id='" + user_id + "'::uuid AND seq>0 ORDER BY seq LIMIT 201; ROLLBACK;")
            plan = subprocess.check_output(["docker", "exec", container, "psql", "-U", "finance_runtime", "-d", "finance",
                                            "-qAt", "-v", "ON_ERROR_STOP=1", "-c", plan_sql], text=True)
            (directory / "pull-plan.json").write_text(json.dumps(json.loads(plan), indent=2) + "\n")

            request("GET", "/api/v1/me", access=access)
            request("POST","/api/v1/auth/password/forgot",dict(email=email),expected=202)
            reset=mail_token(email,"RESET_PASSWORD")
            new_password=secrets.token_urlsafe(24)
            request("POST","/api/v1/auth/password/reset",dict(token=reset,password=new_password),expected=204)
            request("GET","/api/v1/me",access=access,expected=401)
            request("POST","/api/v1/auth/refresh",dict(refreshToken=first["refreshToken"]),expected=401)
            fresh=request("POST","/api/v1/auth/login",dict(email=email,password=new_password,deviceId=str(uuid.uuid4())))
            recovery_env=environment.copy()
            recovery_env.update(RECOVERY_DB_URL=environment["DB_URL"],RECOVERY_DB_USER="finance_migrator",RECOVERY_DB_PASSWORD=environment["DB_MIGRATOR_PASSWORD"])
            prepared=json.loads(subprocess.check_output(["java","-jar",str(jar),"recovery-prepare"],env=recovery_env,text=True))
            request("GET","/api/v1/me",access=fresh["accessToken"],expected=503)
            subprocess.run(["java","-jar",str(jar),"recovery-activate","--epoch",prepared["epoch"],"--reconciled"],env=recovery_env,check=True,capture_output=True)
            request("GET","/api/v1/me",access=fresh["accessToken"],expected=401)
            fresh=request("POST","/api/v1/auth/login",dict(email=email,password=new_password,deviceId=str(uuid.uuid4())))
            request("GET","/api/v1/sync/pull",access=fresh["accessToken"],expected=409)
            bootstrap(fresh["accessToken"])
            request("POST","/api/v1/sync/push",{"operations":[command]},fresh["accessToken"],409)
            report = dict(emailVerification=True,passwordResetRevokesSessions=True,recoveryEpochAndFullReplay=True,
                          tlsVerified=True, plainHttpRejected=True, twoDevicesSync=True, accountIsolation=True,
                          idempotency=True, conflicts=True, tombstones=True, logout=True)
            (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n")
            print("Local JAR/TLS/sync verification passed:", directory.relative_to(ROOT) / "report.json")
    finally:
        if application is not None:
            application.terminate()
            try:
                application.wait(timeout=15)
            except subprocess.TimeoutExpired:
                application.kill()
                application.wait()
        if mail_container:
            subprocess.run(["docker","stop",mail_container],check=True,capture_output=True)
        if container:
            subprocess.run(["docker", "stop", container], check=True, capture_output=True)


if __name__ == "__main__":
    main()
