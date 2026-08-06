# AI代码自动评审 (Base on OpenAI)

## CI 流水线设计（main-maven-jar.yml）

`.github/workflows/main-maven-jar.yml` 是项目的核心 CI 流水线：**任意分支发生 push 或 pull_request 时，自动构建 SDK 的 Fat Jar 并直接运行，完成代码评审**。

### 执行流程

| 步骤 | 做什么 | 关键配置 |
|------|--------|----------|
| 1. 检出代码 | 拉取仓库代码 | `fetch-depth: 2`，只取最近两个提交，评审仅需对比 `HEAD~1` 与 `HEAD` 的差异，无需完整历史 |
| 2. 准备环境 | 安装 JDK | Temurin JDK 21，与 pom 中 `maven.compiler.source=21` 对齐 |
| 3. 构建 | `mvn clean install` | 多模块构建；shade 插件产出含依赖、含 `Main-Class` 清单的 Fat Jar，并安装到本地仓库 |
| 4. 取出构件 | `mvn dependency:copy` | 从本地仓库复制 SDK jar 到 `./libs/`，`-Dmdep.stripVersion=true` 去掉文件名中的版本号 |
| 5. 运行评审 | `java -jar ./libs/openai-code-review-sdk.jar` | 直接执行 Fat Jar，进程退出码决定流水线成败 |

### 核心设计逻辑

1. **评审器即独立可运行的 Jar**：评审逻辑打包为带 `Main-Class` 的 Fat Jar（shade 插件合并依赖与 `META-INF/services`），不依赖 CI 容器内临时编译执行，可移植到任何有 JDK 的环境。
2. **构建产物与运行解耦**：先 `install` 进本地仓库，再用 `dependency:copy` 取出运行，模拟“SDK 作为独立交付物被下游消费”的真实场景，保证运行的就是最终发布的构件。
3. **最小化检出深度**：`fetch-depth: 2` 只获取评审所需的增量 diff 上下文，加快 CI 检出速度。
4. **事件驱动、全分支覆盖**：push 与 PR 均触发，评审覆盖所有分支的每次变更。

### 常见坑位（已规避）

- `dependency:copy` 默认保留版本号，文件名是 `openai-code-review-sdk-1.0.jar`，需加 `-Dmdep.stripVersion=true` 才能与 `java -jar` 路径对上；
- JDK 版本必须与 pom 的 `maven.compiler.source`（21）一致，否则编译失败；
- shade 打包必须配置 `ManifestResourceTransformer` 写入 `Main-Class`，否则 `java -jar` 报 `no main manifest attribute`。