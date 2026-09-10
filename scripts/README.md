# scripts/ — 打包与启停脚本使用说明

本目录提供格物平台的打包脚本与统一启停控制脚本。桌面端（Electron）的跨平台打包脚本见 [gewu-desktop/](../gewu-desktop/)。

## 一、平台打包：`package.sh`

打包后端（Spring Boot Maven 多模块）与前端（Next.js），产物统一汇总到项目根 `dist/`：

```bash
./scripts/package.sh                # 全量打包（后端 + 前端），跳过测试
./scripts/package.sh backend        # 仅后端
./scripts/package.sh web            # 仅前端
./scripts/package.sh all --with-tests --archive --clean
```

| 选项 | 说明 |
|------|------|
| `--with-tests` | 执行后端单元测试（默认 `-DskipTests`） |
| `--archive` | 生成 `dist/gewu-platform-<版本>-<git>.tar.gz` |
| `--clean` | 先清理 `dist/`、Maven `target/`、Next.js `.next/` |

产物结构：

```
dist/
├── backend/                # 三个可运行 Spring Boot jar
│   ├── gewu-gateway-1.0.0-SNAPSHOT.jar
│   ├── gewu-interface-1.0.0-SNAPSHOT.jar
│   └── gewu-sandbox-1.0.0-SNAPSHOT.jar
├── web/                    # Next.js 运行时（.next + node_modules + 配置）
├── manifest.txt            # 版本 / Git SHA / 构建时间
└── gewu-platform-*.tar.gz  # （--archive 时生成）
```

工具链要求：JDK 21、Maven 3.8+、Node.js 18+、pnpm（未安装时脚本会尝试 `corepack enable pnpm` 自动启用）。脚本从 `PATH` 查找，也可用环境变量 `JAVA_HOME`、`MAVEN_HOME`、`NODE_HOME` 指定。

前端依赖与构建统一使用 pnpm：源工程按 `pnpm-lock.yaml --frozen-lockfile` 安装；`dist/web` 部署产物通过 `pnpm install --prod` 从本地 store 硬链接安装生产依赖（无需整份拷贝 node_modules）。

> Linux 服务器部署（Docker/K8s）请使用 `deploy/scripts/deploy.sh`，本脚本是本机/裸机部署的打包入口。

## 二、统一启停：`gewu-ctl.sh`

管理 4 个本地服务：`gateway`(8080)、`interface`(8081)、`sandbox`(8082)、`web`(5001)，PID 与日志分别落在 `run/`、`logs/`：

```bash
./scripts/gewu-ctl.sh start                # 按依赖顺序启动全部（interface→sandbox→gateway→web）
./scripts/gewu-ctl.sh start interface      # 只启动单个服务
./scripts/gewu-ctl.sh stop                 # 全部停止（逆序）
./scripts/gewu-ctl.sh restart gateway
./scripts/gewu-ctl.sh status               # 服务状态总表（PID/端口/运行状态）
./scripts/gewu-ctl.sh logs interface -f    # 跟踪日志（默认 web，最近 100 行）
```

中间件（MySQL/PGVector/MinIO/Dragonfly/SearXNG）通过 docker-compose 管理：

```bash
./scripts/gewu-ctl.sh deps up      # 启动全部中间件
./scripts/gewu-ctl.sh deps ps      # 查看容器状态
./scripts/gewu-ctl.sh deps down
```

行为说明：

- **启动等待**：后端服务最多等 90s、web 等 60s 端口就绪，未就绪会提示日志位置。
- **端口冲突保护**：端口被占用但无 PID 记录时不会误启动，也不会误杀外部进程。
- **优雅停止**：先 SIGTERM 最多等 30s，再强杀（含 pnpm/next 子进程树）。
- **环境变量**：`JAVA_OPTS`（默认 `-Xms256m -Xmx1024m`）、`SPRING_PROFILES_ACTIVE`、按服务覆盖如 `INTERFACE_JAVA_OPTS`。

典型完整流程：

```bash
./scripts/gewu-ctl.sh deps up      # 1. 中间件
./scripts/package.sh               # 2. 打包
./scripts/gewu-ctl.sh start        # 3. 启动四个服务
./scripts/gewu-ctl.sh status       # 4. 确认
```

## 三、桌面端跨平台打包：`gewu-desktop/`

Electron 壳工程（渲染层直接加载 Web 端，可用 `GEWU_WEB_URL` 指向任意已部署地址），基于 electron-builder：

```bash
# macOS / Linux
cd gewu-desktop
./build.sh              # 默认打当前平台包
./build.sh --win        # Linux/macOS 上交叉打 Windows 包（NSIS 内置，无需 wine）
./build.sh --mac        # 仅限 macOS 上执行（dmg 依赖系统工具链）
./build.sh --all --arch x64

# Windows（PowerShell 5.1+）
.\build.ps1             # 默认 win x64
.\build.ps1 -Arch all
.\build.ps1 -Targets win,linux -Dir
```

各平台产物（输出到 `gewu-desktop/release/`）：

| 平台 | 产物 | 架构 |
|------|------|------|
| macOS | `.dmg` + `.zip` | x64、arm64 |
| Windows | NSIS `.exe` 安装包 + portable `.exe` | x64 |
| Linux | `.AppImage` / `.deb` / `.rpm` / `.tar.gz` | x64（AppImage/tar.gz 含 arm64） |

硬性限制：macOS 包必须在 macOS 上构建；Windows 与 Linux 包可交叉构建。客户端运行前需保证 Web 端可达（默认 `http://127.0.0.1:5001`，未连通时展示内置错误页与重试按钮）。

> 国内网络提示：首次 `npm install` 需下载 Electron 二进制，建议配置镜像，否则可能长时间卡住：
>
> ```bash
> export ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
> export ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/
> ```
>
> 若 npm 启用了 allow-scripts 安全机制，electron 的 postinstall 会被拦截，需 `npm approve-scripts electron` 后 `npm rebuild electron`。
