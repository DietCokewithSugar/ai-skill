# 部署到 Render

本项目是 Spring Boot 2.7 + JDK 8 单体服务（前端静态页打包进 jar），执行技能时依赖运行时的
**Python 3 / pip**（镜像已内置）。存储后端可二选一，**默认推荐本地磁盘方案，不依赖任何外部服务器**。

## 一、创建服务

推荐用 Blueprint：Render Dashboard → **New → Blueprint** → 选择本仓库，Render 会读取根目录的
`render.yaml`，只需补填标记为 `sync: false` 的变量。

若手动创建（**New → Web Service**），按下表填写：

| 字段 | 值 | 说明 |
|---|---|---|
| Language / Runtime | `Docker` | 项目锁定 JDK 8，Render 原生 Java runtime 只提供 JDK 17/21 |
| Branch | `main` | |
| Root Directory | 留空 | 项目在仓库根目录 |
| Dockerfile Path | `./Dockerfile.backend` | Render 默认填 `./Dockerfile`，本仓库没有该文件 |
| Docker Build Context Directory | `.` | |
| Docker Command | 留空 | 使用 Dockerfile 的 `ENTRYPOINT` |
| Health Check Path | `/` | 根路径由 `static/index.html` 提供 |
| Instance Type | `Standard` (2GB) 起 | 512MB 实例上 JVM + pip 安装依赖会 OOM |

端口无需手动配置：应用读取 Render 注入的 `PORT`（`application.yml` 中为 `${PORT:8080}`），
本地和 Docker Compose 下仍回落到 8080。

## 二、存储后端二选一

由 `STORAGE_TYPE` 环境变量切换，两种实现共用同一套 `StorageService` 接口，路径语义完全一致。

### 方案 A：本地磁盘（推荐，Render 上完全自包含）

```
STORAGE_TYPE      = local
STORAGE_LOCAL_DIR = /var/data/skills
```

同时在服务上挂载一块持久化磁盘（`render.yaml` 已配置）：

| 字段 | 值 |
|---|---|
| Name | `skill-data` |
| Mount Path | `/var/data` |
| Size | 5 GB（按需调整） |

**代价与限制：**

- **持久化磁盘仅付费实例可用。** 不挂盘也能跑，但容器文件系统是临时的 ——
  每次重新部署或实例重启，全部技能数据都会丢失。
- 挂盘后该服务**无法多实例扩容**，且部署时会有短暂停机（磁盘只能挂在一个实例上）。
- 磁盘容量可以扩，但**不能缩**。

### 方案 B：外部 FTP（保留既有部署方式）

```
STORAGE_TYPE = ftp
FTP_HOST     = FTP 服务器公网地址
FTP_PORT     = 21
FTP_USERNAME = ...
FTP_PASSWORD = ...        ← Secret
FTP_BASE_DIR = /skills
```

不设 `STORAGE_TYPE` 时默认就是 `ftp`，因此已有的 Docker Compose 部署无需任何改动。

选此方案时注意：`FtpStorageService.init()` 带 `@PostConstruct`，**连不上 FTP 会直接中断 Spring 启动**，
在 Render 上表现为部署反复失败。上线前须确认：

1. **被动模式地址**：vsftpd 的 `PASV_ADDRESS` 必须是服务器的**公网 IP**。
   仓库 `docker-compose.yml` 里是内网地址，仅适用于同一局域网访问。
2. **防火墙 / 云安全组**：放行 `21` 端口与完整的被动端口段。
   当前 `PASV_MIN_PORT` 与 `PASV_MAX_PORT` 相同，意味着**同时只能有一个数据连接**，
   建议放宽为一段区间（例如 `20000-20020`）并同步放行。
3. **存储根目录**：用 FTP 客户端以相同账号登录，确认 `FTP_BASE_DIR` 是登录后真实可见的路径
   （vsftpd 若开启了 chroot，容器内的绝对路径与客户端看到的路径并不一致）。
4. **安全**：公网 FTP 为明文传输，账号密码和文件内容均可被中间人截获。
   请使用强密码，并尽量将来源 IP 限制为 Render 的出口地址。

## 三、其余环境变量

| Key | 是否必填 | 默认值 | 说明 |
|---|---|---|---|
| `AI_API_KEY` | ✅ | 空 | OpenAI 兼容接口密钥，设为 Secret |
| `AI_BASE_URL` | | DashScope 兼容端点 | 任意 OpenAI 兼容端点 |
| `AI_MODEL` | ✅ | `qwen3.7-max` | 填账号实际可用的模型名 |
| `AI_TEMPERATURE` | | `0.7` | |
| `AI_MAX_TOKENS` | | `16384` | |
| `AI_TIMEOUT` | | `300000` | 单位毫秒 |
| `AI_THINK` | | `false` | 推理模型是否启用思考模式 |
| `JAVA_OPTS` | | 见 Dockerfile | 需与实例内存匹配 |

## 四、已知限制

- **免费实例**：15 分钟无请求即休眠，冷启动需数十秒；且不支持持久化磁盘。
- **请求超时**：`AI_TIMEOUT` 默认 5 分钟，而 Render 网关对单个 HTTP 请求有超时限制，
  长耗时的同步调用可能在网关侧被切断。
- **技能执行的临时文件**：使用系统临时目录，实例重启后丢失；技能本体与产物落在存储后端上。
- **Python 依赖**：运行镜像内置 Python 3 与 pip，技能首次执行时按需 `pip install`，
  安装结果不跨重启保留。
