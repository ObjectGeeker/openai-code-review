# AI代码自动评审 (Base on OpenAI)

一个以 **独立可运行 Fat Jar** 形式交付的 AI 代码评审组件：在 GitHub Actions 中被触发后，自动提取最新一次提交的变更（diff），调用阿里云百炼大模型进行评审，并把评审意见以 Markdown 日志的形式提交到专用的日志仓库，形成可追溯的评审记录。

## 核心评审流程

```
push / PR 触发流水线
        │
        ▼
┌───────────────────┐    git log + git diff     ┌───────────────────┐
│ 1. 提取变更        │ ────────────────────────▶ │ 最新提交的 diff 文本 │
└───────────────────┘                           └─────────┬─────────┘
                                                          │
┌───────────────────┐    chat/completions       ┌─────────▼─────────┐
│ 3. 提交评审日志    │ ◀──────────────────────── │ 2. 大模型评审      │
│ 克隆日志仓库 →     │        评审意见文本        │ 百炼 OpenAI 兼容接口│
│ 写 md → push      │                           └───────────────────┘
└───────────────────┘
```

1. **提取变更**：先 `git log -1 --pretty=format:%H` 取最新提交 hash，再 `git diff <hash>^ <hash>` 得到本次变更内容（浅克隆场景下语义稳定）；
2. **大模型评审**：将 diff 作为 user 消息，连同「代码评审师」system 提示词，POST 到百炼 OpenAI 兼容模式的 `chat/completions` 接口；
3. **提交评审日志**：克隆日志仓库 → 按 `yyyy-MM-dd` 建目录 → 写入 `{project}-{branch}-{author}{uuid}.md` → add/commit/push，并打印 GitHub 页面上的日志预览链接。

## 架构设计

### Maven 模块结构

```
openai-code-review（父 pom，packaging=pom，继承 spring-boot-starter-parent 仅用于依赖版本管理）
├── openai-code-review-sdk    # 评审 SDK，唯一交付物，打包为可执行 Fat Jar
└── openai-code-review-test   # 本地联调用的 Spring Boot 测试模块
```

### SDK 内部结构

```
com.object.ai.middleware.sdk
├── OpenAiCodeReview                      # 程序入口（Main-Class）
│     ├── 从环境变量读取全部配置（requireEnv 快速失败）
│     └── 编排：diff → 评审 → 提交日志
└── infrastructure
      ├── git/GitCommand                  # Git 操作封装
      │     ├── diff()                    # ProcessBuilder 执行 git 命令提取变更
      │     └── commitAndPush()           # JGit 克隆日志仓库、写文件、提交并推送
      └── openai/util/AiChatUtil          # 百炼 OpenAI 兼容接口调用工具
            ├── chat(...)                 # 4 个重载：单轮/多轮、默认/自定义模型
            └── 解析 choices[0].message.content
```

### 技术选型

| 关注点 | 选型 | 理由 |
|--------|------|------|
| 打包 | maven-shade-plugin Fat Jar | 含依赖与 `Main-Class`，任何有 JDK 的环境可直接运行；`ServicesResourceTransformer` 合并 SPI 资源保证 JGit 正常 |
| Git 远端操作 | JGit | 纯 Java 实现，clone/commit/push 不依赖容器内 git 配置 |
| Git 本地 diff | ProcessBuilder 调 git CLI | diff 输出大、格式严格，CLI 比 JGit diff API 更简单可靠 |
| HTTP | Hutool `HttpRequest` | 复用现有依赖，零新增 |
| JSON | Gson | 手工构建请求体、解析响应，避免引入重量级客户端 |
| 日志 | SLF4J + slf4j-simple | 注意必须引入实现（provider），否则所有日志被 NOP 静默吞掉 |

### 关键设计决策

1. **评审器即独立可运行的 Jar**：不依赖 CI 容器内临时编译执行，构建产物即交付物，可移植到任何 CI 平台；
2. **构建与运行解耦**：CI 中先 `mvn install` 再 `dependency:copy` 取出运行，模拟「SDK 作为独立交付物被下游消费」的真实场景；
3. **配置全部走环境变量**：程序不接收命令行参数，所有配置（含密钥）通过 `System.getenv` 读取，由 CI 的 `env:` 块从 Secrets 注入，密钥不出现在进程命令行中；
4. **评审记录外置到独立日志仓库**：与业务仓库分离，日志仓库可用 GitHub Pages 直接托管预览，多个接入项目可共用一个日志仓库（以 `{project}` 前缀区分）；
5. **最小化检出深度**：`fetch-depth: 2` 只取评审所需的增量 diff 上下文。

## 环境变量契约

程序启动时读取以下环境变量，任一缺失立即报错退出（`环境变量 XXX 未配置`）：

| 环境变量 | 说明 | 是否敏感 |
|----------|------|----------|
| `MAAS_WORKSPACE_ID` | 百炼工作空间 ID（拼接接口域名） | 否 |
| `DASHSCOPE_API_KEY` | 百炼接口密钥（Bearer Token） | **是** |
| `GITHUB_REVIEW_LOG_URI` | 日志仓库 HTTPS 地址，如 `https://github.com/xxx/openai-code-review-log.git` | 否 |
| `GITHUB_TOKEN` | 具有日志仓库写权限的 PAT | **是** |
| `CODE_REVIEW_AUTHOR` | 评审日志提交人名称 | 否 |
| `CODE_REVIEW_EMAIL` | 评审日志提交人邮箱 | 否 |
| `CODE_REVIEW_PROJECT` | 项目标识，用于日志文件命名 | 否 |

## CI 流水线设计（main-maven-jar.yml）

`.github/workflows/main-maven-jar.yml` 是项目的核心 CI 流水线：**任意分支发生 push 或 pull_request 时，自动构建 SDK 的 Fat Jar 并直接运行，完成代码评审**。

### 执行流程

| 步骤 | 做什么 | 关键配置 |
|------|--------|----------|
| 1. 检出代码 | 拉取仓库代码 | `fetch-depth: 2`，只取最近两个提交，评审仅需对比最新提交与其父提交的差异 |
| 2. 准备环境 | 安装 JDK | Temurin JDK 21，与 pom 中 `maven.compiler.source=21` 对齐 |
| 3. 构建 | `mvn clean install` | 多模块构建；shade 插件产出含依赖、含 `Main-Class` 清单的 Fat Jar，并安装到本地仓库 |
| 4. 取出构件 | `mvn dependency:copy` | 从本地仓库复制 SDK jar 到 `./libs/`，`-Dmdep.stripVersion=true` 去掉文件名中的版本号 |
| 5. 运行评审 | `java -jar ./libs/openai-code-review-sdk.jar` | 7 个环境变量经 `env:` 块从 GitHub Secrets 注入，进程退出码决定流水线成败 |

### 常见坑位（已规避）

- `dependency:copy` 默认保留版本号，文件名是 `openai-code-review-sdk-1.0.jar`，需加 `-Dmdep.stripVersion=true` 才能与 `java -jar` 路径对上；
- JDK 版本必须与 pom 的 `maven.compiler.source`（21）一致，否则编译失败；
- shade 打包必须配置 `ManifestResourceTransformer` 写入 `Main-Class`，否则 `java -jar` 报 `no main manifest attribute`；
- JGit 是建造者模式，`clone/add/commit/push` 链式调用后必须 `.call()` 才会真正执行，漏写会静默不生效；
- CI 容器无全局 git 身份，commit 必须显式 `setAuthor/setCommitter`，否则抛 `PersonIdentException`；
- 引入 `slf4j-api` 后必须同时引入实现（如 `slf4j-simple`），否则日志全部被 NOP 吞掉且不报错；
- URL 模板用 `{占位符}` 风格时不能用 `String.format`（只认 `%s`），需用 `replace` 替换；
- 密钥一律经 GitHub Secrets + `env:` 块注入，禁止硬编码在 yml 或源码中；来自 fork 的 PR 默认拿不到 Secrets。

## 下游项目接入指南

SDK 发布到仓库（或 GitHub Release）后，接入方**无需改动业务代码、无需引入 pom 依赖**，只需在仓库中添加一份 workflow：

```yaml
name: AI Code Review
on: [push, pull_request]

jobs:
  code-review:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 2
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      - name: Run Code Review
        run: java -jar ./libs/openai-code-review-sdk.jar   # 按实际获取 jar 的方式调整
        env:
          MAAS_WORKSPACE_ID: ${{ secrets.MAAS_WORKSPACE_ID }}
          DASHSCOPE_API_KEY: ${{ secrets.DASHSCOPE_API_KEY }}
          # GITHUB_REVIEW_LOG_URI 如 https://github.com/xxx/openai-code-review-log.git
          GITHUB_REVIEW_LOG_URI: ${{ secrets.CODE_REVIEW_LOG_URI }}
          # GITHUB_TOKEN 为具有日志仓库写权限的 PAT（https://github.com/settings/tokens）
          GITHUB_TOKEN: ${{ secrets.CODE_TOKEN }}
          CODE_REVIEW_AUTHOR: ${{ secrets.AUTHOR }}
          CODE_REVIEW_EMAIL: ${{ secrets.EMAIL }}
          CODE_REVIEW_PROJECT: ${{ secrets.PROJECT }}
```

接入步骤：

1. 在接入仓库 Settings → Secrets and variables → Actions 中创建上表 7 个 secret；
2. 添加上述 workflow（jar 的获取方式取决于发布形态：`dependency:copy` 拉 Maven 坐标，或 `curl` 从 GitHub Release 下载）；
3. 推送任意提交即可触发评审，评审日志将出现在日志仓库的 `yyyy-MM-dd` 目录下。

本地调试方式相同，用 `export` 注入同名环境变量后直接运行 jar 即可。
