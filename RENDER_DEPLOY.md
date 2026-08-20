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
| Instance Type | `Starter` (512MB) | 够用，但**必须显式设置 `JAVA_OPTS`**，见下方内存预算 |

端口无需手动配置：应用读取 Render 注入的 `PORT`（`application.yml` 中为 `${PORT:8080}`），
本地和 Docker Compose 下仍回落到 8080。

### Starter 实例的内存预算

Starter 为 512MB / 0.5 CPU。**Dockerfile 里的默认值是 `-Xmx512m`，会把整个实例占满，
不给技能执行的 python3 子进程留任何余量**，因此必须覆盖 `JAVA_OPTS`（`render.yaml` 已配置）：

```
-Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC \
-XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai
```

| 参数 | 作用 |
|---|---|
| `-Xmx256m` | 堆上限。本项目以 I/O 为主，堆需求很低 |
| `-XX:MaxMetaspaceSize=128m` | 限制元空间无上限增长 |
| `-XX:+UseSerialGC` | 0.5 CPU 上比 G1 开销更低、占用更小 |
| `-XX:+ExitOnOutOfMemoryError` | OOM 时直接退出由 Render 重启，避免进程僵死 |

实测（本地 JDK 21，生产为 JDK 8，量级参考）：启动后 JVM 常驻约 182MB，
跑完上传/列表/读取后约 190MB，余下约 320MB 供 python3 子进程与 `pip install` 使用。

内存吃紧时优先注意两处：`pip install` 大体积依赖（如科学计算类 wheel）峰值可能超过 200MB；
技能产出二进制文件时会整份读入堆再做 Base64 编码（`ExecutionService.java:904`），
大文件产出建议控制在几十 MB 以内。Maven 构建跑在 Render 的构建机上，不受实例规格限制。

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
| Size | 1 GB（可扩不可缩，按需调整） |

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

| Key | 是否必填 | 代码默认值 | 说明 |
|---|---|---|---|
| `AI_API_KEY` | ✅ | 空 | OpenAI 兼容接口密钥，设为 Secret |
| `AI_BASE_URL` | | DashScope 兼容端点 | 只填到域名，**不要带 `/chat/completions`** |
| `AI_MODEL` | ✅ | `qwen3.7-max` | 填服务商实际可用的模型名 |
| `AI_TEMPERATURE` | | `0.7` | |
| `AI_MAX_TOKENS` | | `16384` | 不得超过服务端的单次输出上限 |
| `AI_TIMEOUT` | | `300000` | 单位毫秒 |
| `AI_THINK` | | `false` | `true`/`false` 会下发 `think` 字段；其他值（如 `none`）则不下发 |
| `JAVA_OPTS` | | 见 Dockerfile | 需与实例内存匹配 |

### 换用其他 OpenAI 兼容服务商

代码会把 `AI_BASE_URL` 末尾的斜杠去掉后追加 `/chat/completions`
（`AiService.java:80,198`），所以 `AI_BASE_URL` 只填到域名或版本前缀即可。

切换服务商时有两处容易踩坑：

1. **`think` 字段**。这是 Ollama / Qwen3 的非标准扩展，OpenAI 规范里并不存在。
   `AI_THINK` 取 `true` 或 `false` 时都会把该字段放进请求体；请求体校验较严的服务
   会因无法识别的字段直接拒绝整个请求。此时把 `AI_THINK` 设为 `none`（或任意
   非布尔值），该字段就完全不会出现在请求里。
2. **`AI_MAX_TOKENS`**。默认值 16384 是针对通义千问设的，多数服务商的单次输出
   上限低于此值，超出会被服务端拒绝。换服务商时按其文档下调。

`render.yaml` 中已按 DeepSeek 配置好（`AI_BASE_URL=https://api.deepseek.com`、
`AI_THINK=none`、`AI_MAX_TOKENS=8192`）；`AI_MODEL` 与上限的确切取值请以
[DeepSeek 官方文档](https://api-docs.deepseek.com/zh-cn/) 为准。

## 四、已知限制

- **免费实例**：15 分钟无请求即休眠，冷启动需数十秒；且不支持持久化磁盘。
  Starter 及以上不休眠。
- **请求超时**：`AI_TIMEOUT` 默认 5 分钟，而 Render 网关对单个 HTTP 请求有超时限制，
  长耗时的同步调用可能在网关侧被切断。
- **技能执行的临时文件**：使用系统临时目录，实例重启后丢失；技能本体与产物落在存储后端上。
- **Python 依赖**：运行镜像内置 Python 3 与 pip，技能首次执行时按需 `pip install`，
  安装结果不跨重启保留。
