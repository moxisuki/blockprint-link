# BlockPrint Link

> 从任意设备浏览、下载、上传 Minecraft 蓝图

BlockPrint Link 是一个**客户端模组**,在玩家的 Minecraft 客户端实例(非专用服务器)上暴露本地
**WebSocket 网桥** + **UDP 发现信标**。配合移动端 App **[BlockPrint Cat](https://github.com/moxisuki/blockprint-cat)**
,你可以在手机上浏览本地蓝图库、预览文件,并把新的蓝图直接推送回游戏世界 ——
不必守在电脑前。

![screenshot](https://raw.githubusercontent.com/moxisuki/blockprint-link/master/docs/screenshot.png)

- **GitHub 仓库**:<https://github.com/moxisuki/blockprint-link>
- **配套 App**:<https://github.com/moxisuki/blockprint-cat>

---

## ✨ 功能特性

- **蓝图库浏览** — 列出主机上所有 Litematica 蓝图、原版结构导出、WorldEdit 蓝图
- **双向传输** — 下载到手机,或上传 `.litematic` / `.schematic` 文件回游戏
- **UDP 自动发现** — 模组在 `18081` 端口广播自身存在,同网段手机 App 自动找到,**无需手动输入 IP**
- **二维码配对** — 游戏内按 **F7** 弹出二维码,手机扫码即连,适合无路由器 / 跨网段场景
- **可热重载配置** — token、端口、聊天通知都在 `config/blockprintlink-bridge.toml`,游戏内 `/blockprint-reload` 即可生效
- **可选整合** — 自动检测 WorldEdit(其 schematic 目录)与 Building Gadgets 2(上传后聊天中点一下复制模板 JSON)
- **实时文件监听** — 蓝图增删时列表自动推送更新,无需手动刷新
- **SHA-256 端到端校验** — 每次传输都校验哈希,杜绝损坏文件

---

## 📦 蓝图来源

| 来源 | 路径 | 启用条件 |
|---|---|---|
| Litematica 蓝图 | `<gameDir>/schematics/` | 始终 |
| 原版结构导出 | `<gameDir>/saves/*/generated/minecraft/structures/` | 始终 |
| WorldEdit 蓝图 | `<gameDir>/config/worldedit/schematics/` | WorldEdit 在 classpath 时(自动探测) |

WorldEdit 整合**不**作为 Gradle 依赖,运行时按需读取默认目录,classpath 探测即生效。

---

## 🚀 快速开始

1. 把 jar 拖进 `mods/` 文件夹
2. 启动 Minecraft,模组自动启动:
   - WebSocket 服务器:`18080`
   - UDP 发现信标:`18081`
3. 手机打开 **BlockPrint Cat** App(同 Wi-Fi),自动发现
4. 如果自动发现失败,游戏内按 **F7** 显示二维码,手机扫码连接

---

## ⚙️ 配置文件

`config/blockprintlink-bridge.toml`

```toml
[bridge]
token = ""               # 留空自动生成;持久化到 blockprintlink-token.txt
showChatMessages = true  # 进游戏是否在聊天显示状态
wsPort = 18080           # WebSocket 端口
discoveryPort = 18081    # UDP 端口
```

- 进游戏时聊天会显示 token;token 可以是任意非空字符串
- 持久化的 token 存在 `<gameDir>/blockprintlink-token.txt`,重启后保留
- 修改后游戏内 `/blockprint-reload` 即生效

---

## 📋 环境要求

- **NeoForge / Forge**:Kotlin for Forge(`[5,)`)—— 模组自身已声明依赖
- **Fabric**:Fabric Loader `>=0.16.9` + Fabric API(版本见 `gradle/libs.versions.toml`)
- **Java**:21(Forge 1.20.1 例外,用 17)

---

## 🧩 协议规范(给 App 开发者)

网桥在 `18080` 端口提供基于 WebSocket 的轻量 JSON 协议,二进制帧用于文件分片。
完整消息类型、错误码、客户端实现要求见:

- **[docs/bridge-protocol.md](bridge-protocol.md)**

关键消息:
- `list` —— 请求蓝图列表
- `download` / `upload` —— 两阶段文件传输,带 SHA-256 校验
- `list/changed` —— 文件变更服务端主动推送
- `error` —— 结构化错误响应(码:`AUTH_FAILED`、`FILE_TOO_LARGE`、`SHA_MISMATCH` …)
