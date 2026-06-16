# BlockPrint Link

Minecraft 模组，WebSocket 服务器 + UDP 广播，供 [BlockPrint Cat](https://github.com/moxisuki/blockprint-cat) 浏览蓝图。

![screenshot](docs/screenshot.png)

## 支持版本

| MC | NeoForge | Forge | Fabric |
|----|----------|-------|--------|
| 1.20.1 | — | ✅ | — |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.4 | ✅ | — | ✅ |
| 1.21.8 | ✅ | — | ✅ |
| 26.1.2 | ✅ | — | ✅ |

## 构建

```bash
./gradlew :neoforge-1_21_1:build
./gradlew :forge-1_20_1:build
./gradlew :fabric-1_21_1:build
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

F7 打开 QR 码弹窗，`/blockprint-reload` 重载配置。

## 许可

MIT
