# BlockPrint Link 跨版本构建要点

## 版本矩阵

| MC | NeoForge | Forge | Fabric |
|----|----------|-------|--------|
| 1.20.1 | — | ✅ JDK 17 | — |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.4 | ✅ | — | ✅ |
| 1.21.8 | ✅ | — | ✅ |
| 26.1.2 | ✅ JDK 26 | — | ✅ JDK 26 |

## 架构

```
common/              ← 共享代码（不 import MC 类，或通过反射/QrData 隔离）
neoforge-1_21_1/     ← 平台专属（Screen、事件注册、按键绑定）
forge-1_20_1/        ← ForgeGradle 6.x + reobfJar (SRG remap)
fabric-1_26_1_2/     ← 独立 Gradle 9.4.1 + JDK 26
```

**原则**：`common/` 不依赖 MC 版本。每个 `*-<version>/` 子项目自己配版本号。

## 添加新版本

1. 复制最近的子项目 → 改目录名
2. `settings.gradle` 加 include
3. 子项目 `gradle.properties` 改 `minecraft_version` / `neoforge_version` 等
4. 子项目 `build.gradle` 改 NGD/Loom 版本（如需）
5. 如有 API 变更 → 改源码 + 处理 `import` 差异

## 26.1.2 特殊处理

- **NeoForge**：NDG 2.0.141 + `java { toolchain { languageVersion = 26 } }`。Gradle 8.14 用 JDK 21 跑，NGD spawn 的 NeoForm 进程走 toolchain JDK 26
- **Fabric**：独立构建（`fabric-1_26_1_2/`），Gradle 9.4.1 + JDK 26。Loom 1.16-SNAPSHOT，`splitEnvironmentSourceSets()`，Fabric 源码放 `src/client/java/`
- **Common**：`QrHudRenderer.java` + `UiHooks.java` 排除编译（用 `GuiGraphics` → 26.x 改名 `GuiGraphicsExtractor`）
- **I18n**：`QrData` 通过反射调 `net.minecraft.client.resources.language.I18n.get()`，避免 common 编译依赖 client-only 类

## 1.20.1 Forge 特殊处理

- ForgeGradle 6.x via buildscript（不是 NGD）
- `reobfJar` 把 Mojang 名转 SRG（`consumeClick`→`m_90859_`）
- 编译 classpath 混入 NGD 的 MC 类导致 KeyMapping 签名不匹配 → **classpath filter** 剔除 common 下的 NGD jar
- Java 17 字节码：`compilerArgs '-source' '17' '-target' '17'`
- 反射代码（`sendMessage`）在 SRG 运行时失效 → ClientSetup 直调编译 API

## 构建命令速查

```bash
# NeoForge
./gradlew :neoforge-1_21_1:build

# Forge (需 reobfJar)
./gradlew :forge-1_20_1:reobfJar

# Fabric 26.1.2 独立构建
cd fabric-1_26_1_2
JAVA_HOME=<jdk26> ./gradlew jar
```

## 配置

`config/blockprintlink-bridge.toml`：

```toml
[bridge]
token = ""
showChatMessages = true
wsPort = 18080
discoveryPort = 18081
```

## 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| `NoSuchMethodError: KeyMapping.consumeClick()` | SRG 重映射缺失 | 跑 `reobfJar` |
| `Unsupported class file major version 70` | JDK 26 跑 Gradle 8.14 Groovy | Gradle 用 JDK 21，toolchain 用 JDK 26 |
| `GuiGraphicsExtractor not found` | Fabric `splitEnvironmentSourceSets` | 源码放 `src/client/java/` |
| 代理导致 CI 下载失败 | 项目 `gradle.properties` 有代理 | 代理移至 `~/.gradle/` |
| `Failed to find official mojang mappings` | Loom 拉不到 mappings | 清 `.gradle/caches/fabric-loom` 重试 |