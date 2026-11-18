# PaceApp

PaceApp 是个人自用的 Android 客户端，用于直接连接服务器上的 Hermes Agent 进行实时对话、工具执行状态查看与授权确认，后续可扩展个人速记、数据库/Markdown 同步与动态卡片展示。

线上 API 地址：

```text
https://pace.zengdeming.cn
```

## 第一版功能

- 个人单用户登录（关闭公开注册，由服务器 `.env` 配置账号密码与签名密钥，支持长期会话 Token）
- 会话管理：查看历史会话、新建、重命名、删除对话
- 实时 SSE 流式对话：逐字输出回复、展示工具调用进度、支持中途停止与高风险命令弹窗授权
- 应用内自动检查更新，下载已签名 APK 并覆盖安装

## 本地开发与校验

### 后端（Go）

```powershell
cd D:\MyWorkSpace\Codex\PaceApp\backend
go test -race ./...
```

### 客户端（Android）

1. 复制 `local.properties.example` 为 `local.properties`，填入本机 `sdk.dir`。
2. 运行单元测试、Lint 与 Debug APK 打包：

```powershell
cd D:\MyWorkSpace\Codex\PaceApp
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Debug APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`。

## 部署与更新文档

服务器部署、Hermes `api_server` 开启方法、`pace.zengdeming.cn` 网关配置、GitHub Actions Secrets 与更新/回滚步骤见 [ARCHITECTURE_AND_UPDATE.md](ARCHITECTURE_AND_UPDATE.md)。
