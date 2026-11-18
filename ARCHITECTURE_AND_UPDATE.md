# PaceApp 架构、服务器配置与更新方法

## 当前架构

```text
PaceApp (Android)
    │ HTTPS 443
    ▼
pace.zengdeming.cn -> zengdeming-gateway -> paceapp-api:8080 (Go, 128 MB)
                                                 │
                                                 │ http://host.docker.internal:8642
                                                 ▼
                                    宿主机 hermes-gateway (api_server)
```

服务器目录：`/home/dutu/workspace/pace-app`

- `paceapp-api` 仅绑定宿主机 `127.0.0.1:8081`，对外由 `zengdeming-gateway` 通过外部网络 `zengdeming-edge` 的别名 `paceapp-api` 转发。
- 通过 `extra_hosts: ["host.docker.internal:host-gateway"]` 访问宿主机运行的 `hermes-gateway` 内置 `api_server`。
- `.env`、`certs/`、`apks/` 仅保存在服务器，不提交到 GitHub。

---

## 首次部署步骤

### 1. 在服务器开启 Hermes `api_server`

先查看当前 Docker 网桥宿主机网关 IP（通常为 `172.17.0.1` 或 `zengdeming-edge` 网关 IP）：

```bash
docker network inspect zengdeming-edge --format '{{(index .IPAM.Config 0).Gateway}}'
```

生成强随机密钥并写入 `/home/dutu/.hermes/.env`（注意不要绑定到 `0.0.0.0`，只绑定到上面查到的网关 IP，或本机回环 + 防火墙放行容器网段）：

```bash
openssl rand -hex 32
```

在 `/home/dutu/.hermes/.env` 追加（示例网关 IP 为 `172.17.0.1` 或 `zengdeming-edge` 网关 IP）：

```env
API_SERVER_KEY=<上一步生成的64位十六进制密钥>
API_SERVER_HOST=<zengdeming-edge网关IP>
API_SERVER_PORT=8642
```

重启并检查 Hermes：

```bash
systemctl --user restart hermes-gateway
systemctl --user status hermes-gateway --no-pager
curl -sS http://<zengdeming-edge网关IP>:8642/health
```

### 2. 准备服务器目录与 `.env`

```bash
mkdir -p /home/dutu/workspace/pace-app/{apks,certs}
```

上传 `compose.yaml` 与 `.env.example` 为 `.env` 并填写：
- `AUTH_USERNAME`：你的个人登录账号
- `AUTH_PASSWORD`（或 `AUTH_PASSWORD_SHA256`）：登录密码（或 SHA-256 十六进制摘要）
- `AUTH_SIGNING_SECRET`：`openssl rand -hex 32` 生成
- `HERMES_BASE_URL`：`http://host.docker.internal:8642`
- `HERMES_API_KEY`：与 `/home/dutu/.hermes/.env` 中的 `API_SERVER_KEY` 保持一致

> 注意：GitHub Package `ghcr.io/dutu007/paceapp-api` 首次发布后需在 GitHub Packages 页面将其设为 **Public**（或在服务器执行 `docker login ghcr.io`）。

启动后端：

```bash
cd /home/dutu/workspace/pace-app
docker compose pull api
docker compose up -d --force-recreate api
curl http://127.0.0.1:8081/api/v1/health
```

### 3. 配置 `pace.zengdeming.cn` 域名与网关

1. DNS 解析添加 `pace` 的 A 记录指向服务器 IP。
2. 将 `pace.zengdeming.cn.pem` 和 `pace.zengdeming.cn.key` 放入 `/home/dutu/workspace/pace-app/certs/`。
3. 在 `/home/dutu/workspace/my-websites/compose.yaml` 的 `gateway.volumes` 中新增一行挂载：
   ```yaml
   - /home/dutu/workspace/pace-app/certs:/etc/nginx/ssl-pace:ro
   ```
4. 将本仓库 [deploy/nginx-pace.conf](deploy/nginx-pace.conf) 中的 `server` 块合并进 `/home/dutu/workspace/my-websites/gateway/default.conf`。
5. 重建并验证网关：
   ```bash
   cd /home/dutu/workspace/my-websites
   docker compose up -d --force-recreate gateway
   curl https://pace.zengdeming.cn/api/v1/health
   ```

---

## 配置 GitHub Actions 自动构建与发布

### 1. 生成 Android 签名密钥库（本地执行，切勿提交到仓库）

```powershell
mkdir D:\MyWorkSpace\Codex\PaceApp\keystore
keytool -genkeypair -v -keystore D:\MyWorkSpace\Codex\PaceApp\keystore\paceapp-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias paceapp
[Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\MyWorkSpace\Codex\PaceApp\keystore\paceapp-release.jks")) | Set-Clipboard
```

### 2. 在 GitHub 仓库配置 Actions Secrets

路径：`Settings → Secrets and variables → Actions`

| Secret | 说明 |
| --- | --- |
| `PACEAPP_KEYSTORE_BASE64` | release 密钥库的 base64 |
| `PACEAPP_STORE_PASSWORD` | 密钥库口令 |
| `PACEAPP_KEY_ALIAS` | 密钥别名（`paceapp`） |
| `PACEAPP_KEY_PASSWORD` | 密钥口令 |
| `SERVER_HOST` | 服务器地址（如 `zengdeming.cn`） |
| `SERVER_USER` | SSH 用户（`dutu`） |
| `SERVER_SSH_KEY` | 部署用 SSH 私钥 |

---

## 日常更新方法

### 更新后端 API

推送包含 `backend/**` 或 `compose.yaml` 的提交到 `main`，待 `Build and publish backend image` 成功后在服务器执行：

```bash
cd /home/dutu/workspace/pace-app
docker compose pull api
docker compose up -d --force-recreate api
```

### 更新 Android App

推送包含 `app/**` 的提交到 `main` 后，GitHub Actions 会自动编译签名 APK（版本号 `1.0.<run_number>`）并上传 `paceapp.apk` 与 `latest.json` 到服务器 `/home/dutu/workspace/pace-app/apks/`。打开手机上的 Pace App 即可自动检测并下载安装更新。

### 回滚 API

将服务器 `.env` 中的 `IMAGE_TAG` 改为目标提交 SHA，再执行 `docker compose pull api && docker compose up -d --force-recreate api`。
