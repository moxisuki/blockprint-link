# BlockPrint Link Protocol

Mod 启动后监听 **WS `18080`** + **UDP 广播 `18081`**。

## 1. 获取 Token

- **进游戏聊天**直接显示 token
- **配置文件** `config/blockprintlink-bridge.toml` → `token = "xxx"`
- 改配置后游戏内 `/blockprint-reload` 生效
- **Token 可以是任意字符串**（不限制长度和格式）

## 2. WebSocket

```
ws://<host>:18080/ws?token=<token>
```

标准 RFC 6455，文本帧 = JSON 控制消息，二进制帧 = 文件内容。

### 2.1 握手

```
GET /ws?token=okk2 HTTP/1.1
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: <base64 16 random bytes>
Sec-WebSocket-Version: 13

→ 101 Switching Protocols
  Upgrade: websocket
  Connection: Upgrade
  Sec-WebSocket-Accept: <sha1 base64>
```

- Token 错误 → `401 AUTH_FAILED`
- 缺少 WebSocket 头 → `400`

### 2.2 消息类型

| Type | 方向 | 说明 |
|---|---|---|
| `list` | C→S | 请求蓝图列表 |
| `list/response` | S→C | 列表响应 |
| `list/changed` | S→C | 文件变更推送（WatchService 触发） |
| `download` | C→S | 请求下载文件（可选 `source` 字段指定来源） |
| `download/start` | S→C | 下载头（文件名 / 大小 / SHA256） |
| `upload` | C→S | 上传头（文件名 / 大小 / SHA256） |
| `upload/result` | S→C | 上传结果 |
| `error` | S→C | 错误 |

### 2.3 请求 / 响应示例

**拉列表**：
```json
→ {"type":"list","requestId":"r1"}

← {
  "type": "list/response",
  "requestId": "r1",
  "ok": true,
  "mcVersion": "1.21.1",
  "loader": "neoforge",
  "loaderVersion": "21.1.233",
  "folderName": "1.21.1-NeoForge_21.1.233",
  "entries": [
    {
      "fileName": "house.litematic",
      "format": "litematic",
      "name": "Beach House",
      "width": 32, "height": 64, "depth": 32,
      "blocks": 1234,
      "author": "Steve",
      "description": "A beach house",
      "minecraftDataVersion": 3463,
      "version": 6,
      "regions": 1,
      "source": "schematics"
    },
    {
      "fileName": "igloo.nbt",
      "format": "structure",
      "name": "igloo",
      "width": 8, "height": 5, "depth": 8,
      "blocks": 47,
      "author": "",
      "description": "",
      "minecraftDataVersion": null,
      "version": null,
      "regions": 1,
      "source": "saves/New World"
    }
  ]
}
```

**下载文件**：
```json
→ {"type":"download","requestId":"r2","fileName":"house.litematic"}
← {"type":"download/start","requestId":"r2","fileName":"house.litematic","size":8064,"sha256":"<64 hex>"}

→ {"type":"download","requestId":"r3","fileName":"igloo.nbt","source":"saves/New World"}
← {"type":"download/start","requestId":"r3","fileName":"igloo.nbt","size":2048,"sha256":"<64 hex>","source":"saves/New World"}
← [binary frame: 文件字节]
```

**上传文件**：
```json
→ {"type":"upload","fileName":"new.litematic","size":8064,"sha256":"<64 hex>","overwrite":false}
→ [binary frame: 文件字节]
← {"type":"upload/result","fileName":"new.litematic","ok":true}
```

**文件变更推送**（服务端主动发）：
```json
← {
  "type": "list/changed",
  "mcVersion": "1.21.1",
  "loader": "neoforge",
  "loaderVersion": "21.1.233",
  "folderName": "1.21.1-NeoForge_21.1.233",
  "entries": [...]
}
```

### 2.4 携带信息 (list/response, list/changed)

| 字段 | 类型 | 说明 |
|---|---|---|
| `mcVersion` | string | MC 版本 |
| `loader` | string | `neoforge` / `fabric` / `vanilla` |
| `loaderVersion` | string | 加载器版本 |
| `folderName` | string | schematics 父目录名（即版本文件夹） |

### 2.5 文件入口 (SchematicEntry)

| 字段 | 类型 | 说明 |
|---|---|---|
| `fileName` | string | 文件名 |
| `format` | string | `litematic` / `schematic` |
| `name` | string | 文件内元数据名称 |
| `width/height/depth` | int | 尺寸（主区域） |
| `blocks` | int | 非空气方块数 |
| `author` | string | 作者（可为空） |
| `description` | string | 描述（可为空） |
| `minecraftDataVersion` | int\|null | MC 数据版本（.litematic 有、Sponge .schematic 无） |
| `version` | int\|null | 文件格式版本（.litematic 有） |
| `regions` | int | 区域数 |
| `source` | string | 来源标识 — `"schematics"`（根目录）或 `"saves/<世界名>"`（存档结构导出）|

### 2.6 错误码

| Code | 触发场景 |
|---|---|
| `AUTH_FAILED` | Token 错误 |
| `BAD_JSON` | JSON 解析失败 |
| `BAD_REQUEST` | 缺少必要字段 |
| `BAD_FILENAME` | 文件名非法（含 `..` / `\\` 等） |
| `FILE_NOT_FOUND` | 文件不存在 |
| `FILE_TOO_LARGE` | 超过 100 MB |
| `FILE_EXISTS` | 上传时文件已存在且 `overwrite:false` |
| `SHA_MISMATCH` | 上传字节 SHA256 与声明不匹配 |
| `IO_ERROR` | 磁盘读写失败 |
| `UNKNOWN_TYPE` | 未知消息类型 |
| `UNEXPECTED_BINARY` | 未等待上传时收到二进制帧 |

### 2.7 限制

- 单文件 ≤ 100 MB
- 上传并发：每连接最多一个进行中
- 连接数：无上限

## 3. UDP 广播发现

Mod **每 2 秒**向 `255.255.255.255:18081` 广播 JSON。

```json
{
  "type": "blockprintlink/discovery",
  "version": "0.1.0",
  "wsPort": 18080,
  "tokenHint": "okk2"
}
```

- `tokenHint` 是 token 前 4 字符（token 不足 4 字符时显示全部）
- `wsPort` 是 WebSocket 端口

### 3.1 Android / 外部客户端监听示例

**Java**:
```java
DatagramSocket socket = new DatagramSocket(18081);
byte[] buf = new byte[1024];
DatagramPacket packet = new DatagramPacket(buf, buf.length);
while (true) {
    socket.receive(packet);
    String json = new String(packet.getData(), 0, packet.getLength());
    // 过滤 type == "blockprintlink/discovery"
    // 取 wsPort + tokenHint → 提示用户输入完整 token → 连接 WS
}
```

**Python**:
```python
import socket, json
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('0.0.0.0', 18081))
while True:
    data, addr = s.recvfrom(1024)
    msg = json.loads(data)
    if msg.get('type') == 'blockprintlink/discovery':
        print(f"Found bridge at {addr[0]}:{msg['wsPort']}, hint={msg['tokenHint']}")
```

**wscat (WebSocket 测试)**：
```bash
wscat -c "ws://192.168.1.42:18080/ws?token=okk2"
> {"type":"list","requestId":"r1"}
```

## 4. 游戏内命令

| 命令 | 说明 |
|---|---|
| `/blockprint-reload` | 重新加载 TOML 配置，token / 端口立即生效 |
| 按 **F7** | 切换 QR 码 overlay 显示/隐藏 |

## 5. QR 码

- 按 F7 显示在屏幕右上角（72×72 像素 + 文字）
- 编码内容：`blockprintcat://<IP>:<Port>/ws?token=<Token>`
- Android 端注册 `blockprintcat://` scheme 即可扫码直连
