# BlockPrint Link Protocol

Mod 启动后监听 **WS `18080`** + **UDP 广播 `18081`**。

## 1. 获取 Token

- **进游戏聊天**直接显示 token
- **配置文件** `config/blockprintlink-bridge.toml` → `token = "xxx"`
- 改配置后游戏内 `/blockprint-reload` 生效
- **Token 可以是任意字符串**(不限制长度和格式)

## 2. WebSocket

```
ws://<host>:18080/ws?token=<token>
```

标准 RFC 6455,文本帧 = JSON 控制消息,二进制帧 = 文件内容(分片 body)。

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

### 2.2 消息类型(协议 v2)

所有控制消息(text 帧)都是 JSON object,**`requestId` 是可选但强烈建议填** —— 服务端在响应里 echo 回去,客户端用它把异步响应路由到正确的回调。

| Type | 方向 | 说明 |
|---|---|---|
| `list` | C→S | 请求蓝图列表(响应 `list/response`) |
| `list/response` | S→C | 列表响应 |
| `list/changed` | S→C | 文件变更推送(WatchService 触发) |
| `download` | C→S | 请求下载文件(可选 `source` 字段指定来源) |
| `download/ready` | S→C | 下载头(文件名 / 大小 / SHA256) |
| `download/done` | S→C | 下载完成(ok / bytes / sha256) |
| `upload/init` | C→S | 上传初始化(必填 `requestId` / `fileName` / `size`) |
| `upload/ready` | S→C | 服务端就绪,可以发 binary chunks |
| `upload/commit` | C→S | 上传完成(binary 已发完) |
| `upload/result` | S→C | 上传结果(ok / error) |
| `upload/done` | S→C | 上传成功,带服务端计算的 SHA256 |
| `error` | S→C | 错误 |

#### 上传(两阶段)

```
1. C → S  [text]  {"type":"upload/init",  "requestId":"t1", "fileName":"foo.schem",
                                          "size":8064, "sha256":"<optional client SHA>",
                                          "overwrite":"<true|false, default false>"}
2. S → C  [text]  {"type":"upload/ready", "requestId":"t1", "fileName":"foo.schem"}
3. C → S  [binary] chunk1   ← 任意大小,任意数量,按序发送
4. C → S  [binary] chunk2
5. C → S  [text]  {"type":"upload/commit","requestId":"t1", "fileName":"foo.schem"}
6. S → C  [text]  {"type":"upload/result","requestId":"t1", "fileName":"foo.schem", "ok":true}
7. S → C  [text]  {"type":"upload/done", "requestId":"t1", "fileName":"foo.schem", "sha256":"<64 hex>"}
```

- **`requestId` 必填** —— echo 回包,客户端用来路由回调
- **第 2 步必须等** —— 收到 `upload/ready` 后才开始发 binary(否则会被丢)
- **`sha256` 可选** —— 客户端可以省(让服务端算),也可以提供(服务端会校验,不一致返回 `SHA_MISMATCH`)
- **服务端独立算 SHA** —— 即便客户端没传 SHA,服务端也会在 `upload/done` 里回传最终 SHA,客户端可用来校验
- **错误响应** —— `upload/result` 在以下情况出现:
  - `BAD_FILENAME` / `FILE_TOO_LARGE` / `FILE_EXISTS` / `BUSY` / `LENGTH_MISMATCH` / `SHA_MISMATCH` / `NO_ACTIVE_UPLOAD` / `IO_ERROR`

#### 下载(两阶段)

```
1. C → S  [text]  {"type":"download",     "requestId":"t2", "fileName":"foo.schem",
                                          "source":"<schematics|saves/<world>|worldedit, default schematics>"}
2. S → C  [text]  {"type":"download/ready","requestId":"t2", "fileName":"foo.schem",
                                          "size":8064, "sha256":"<64 hex>",
                                          "source":"<echo of source>"}
3. S → C  [binary] chunk1   ← 固定 64 KiB chunks
4. S → C  [binary] chunk2
5. S → C  [text]  {"type":"download/done","requestId":"t2", "fileName":"foo.schem",
                                          "ok":true, "bytes":8064, "sha256":"<64 hex>"}
```

- **下载不分大小** —— 任意大小,服务端切成 64 KiB 二进制帧按序发送
- **客户端按 `requestId` 路由** —— 收到 `download/ready` 后开始接收 binary 帧累积,直到 `download/done` 出现或累积字节数 = `size` 字段
- **错误** —— `error` 消息(响应前发现 FILE_NOT_FOUND / FILE_TOO_LARGE / IO_ERROR)

#### 列表

```
1. C → S  [text]  {"type":"list", "requestId":"r1"}
2. S → C  [text]  {"type":"list/response", "requestId":"r1", "ok":true,
                                       "mcVersion":"1.21.1", "loader":"neoforge",
                                       "loaderVersion":"21.1.233",
                                       "folderName":"1.21.1-NeoForge_21.1.233",
                                       "entries":[ ... ]}
```

#### 文件变更推送(服务端主动)

```
S → C  [text]  {"type":"list/changed", "mcVersion":"...", "loader":"...",
                                    "loaderVersion":"...", "folderName":"...",
                                    "entries":[ ... ]}
```

触发:`schematics/` / `config/worldedit/schematics/` / `saves/*/structures/` 任意文件变更,延迟约 100-500 ms(由 OS WatchService 决定)。

### 2.3 上传路由表

服务端按扩展名自动决定落盘位置,客户端无需指定路径:

| 文件名后缀 | 目标目录 | 备注 |
|---|---|---|
| `.schem` / `.schematic` (Sponge 格式) | `<gameDir>/config/worldedit/schematics/` | 仅当 WorldEdit 已加载;目录不存在则自动创建;创建失败回退到 `schematics/` |
| `.litematic` / `.nbt` / `.json` / 无后缀 | `<gameDir>/schematics/` | 默认目标 |

**白名单扩展名**:服务端只接受 `.litematic` / `.schematic` / `.schem` / `.nbt` / `.json`,其他后缀返回 `BAD_FILENAME`。

### 2.4 完整请求 / 响应示例

**拉列表**:
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
      "format": "Litematica",
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
      "format": "Structure",
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

**下载文件**:
```json
→ {"type":"download","requestId":"r2","fileName":"house.litematic"}
← {"type":"download/ready","requestId":"r2","fileName":"house.litematic","size":8064,"sha256":"<64 hex>"}

→ {"type":"download","requestId":"r3","fileName":"igloo.nbt","source":"saves/New World"}
← {"type":"download/ready","requestId":"r3","fileName":"igloo.nbt","size":2048,"sha256":"<64 hex>","source":"saves/New World"}
← [binary frame: 文件字节 (64 KiB chunks)]
← {"type":"download/done","requestId":"r3","fileName":"igloo.nbt","ok":true,"bytes":2048,"sha256":"<64 hex>"}
```

**上传文件**:
```json
→ {"type":"upload/init","requestId":"t1","fileName":"new.schem","size":8064,"overwrite":false}
← {"type":"upload/ready","requestId":"t1","fileName":"new.schem"}
→ [binary frame: 8064 字节(任意分块)]
→ {"type":"upload/commit","requestId":"t1","fileName":"new.schem"}
← {"type":"upload/result","requestId":"t1","fileName":"new.schem","ok":true}
← {"type":"upload/done","requestId":"t1","fileName":"new.schem","sha256":"<64 hex>"}
```

**上传失败**:
```json
→ {"type":"upload/init","requestId":"t2","fileName":"new.schem","size":8064}
← {"type":"upload/result","requestId":"t2","fileName":"new.schem","ok":false,"error":"FILE_EXISTS"}
```

**文件变更推送**(服务端主动):
```json
← {
  "type": "list/changed",
  "mcVersion": "1.21.1",
  "loader": "neoforge",
  "loaderVersion": "21.1.233",
  "folderName": "1.21.1-NeoForge_21.1.233",
  "entries": [ ... ]
}
```

### 2.5 元数据字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `mcVersion` | string | MC 版本 |
| `loader` | string | `neoforge` / `forge` / `fabric` / `vanilla` |
| `loaderVersion` | string | 加载器版本 |
| `folderName` | string | schematics 父目录名(即版本文件夹) |

### 2.6 SchematicEntry 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `fileName` | string | 文件名 |
| `format` | string | Canonical `SchematicFormat` enum name: `Litematica` / `Sponge` / `Structure` / `BuildingHelper` / `PartialNbt` / `Unknown` |
| `name` | string | 文件内元数据名称 |
| `width/height/depth` | int | 尺寸(主区域) |
| `blocks` | int | 非空气方块数 |
| `author` | string | 作者(可为空) |
| `description` | string | 描述(可为空) |
| `minecraftDataVersion` | int\|null | MC 数据版本(`.litematic` 有,Sponge `.schematic` 无) |
| `version` | int\|null | 文件格式版本(`.litematic` 有) |
| `regions` | int | 区域数 |
| `source` | string | 来源标识 —— `"schematics"`(根目录)、`"saves/<世界名>"`(存档结构导出)、`"worldedit"`(WorldEdit 的 `config/worldedit/schematics/`,仅当 WorldEdit 加载时存在) |

### 2.7 错误码

| Code | 触发场景 |
|---|---|
| `AUTH_FAILED` | Token 错误(握手时 401) |
| `BAD_JSON` | JSON 解析失败 |
| `BAD_REQUEST` | 缺少必要字段 |
| `BAD_FILENAME` | 文件名非法(后缀不在白名单 / 含 `..` / `\\` 等) |
| `FILE_NOT_FOUND` | 下载的文件不存在 |
| `FILE_TOO_LARGE` | 超过 100 MB |
| `FILE_EXISTS` | 上传时文件已存在且 `overwrite:false` |
| `BUSY` | 同一连接已有上传在进行中(单连接单 in-flight upload) |
| `NO_ACTIVE_UPLOAD` | 收到 `upload/commit` 但 init 未发生 / requestId 不匹配 |
| `LENGTH_MISMATCH` | 收到的 binary 字节数 ≠ init 时的 `size` |
| `SHA_MISMATCH` | 客户端 init 时提供 SHA,但与服务端独立算的 SHA 不一致 |
| `IO_ERROR` | 磁盘读写失败 |
| `UNKNOWN_TYPE` | 未知消息类型 |

> 注意:`UNEXPECTED_BINARY` 错误已移除 —— 协议 v2 下 binary 帧只在 `upload/ready` 之后才被接受,其他时间收到仅 warn + drop,不上报客户端(避免打断上传流程)。

### 2.8 限制

- 单文件 ≤ 100 MB
- 上传并发:**每连接最多一个** in-flight upload(并发需开多 WS 连接)
- 下载并发:无限制(每条 download 消息独立)
- 连接数:无上限
- 二进制帧大小:客户端任意(无 fragmentation 依赖),服务端下载 chunks 固定 64 KiB

### 2.9 WorldEdit 集成

当 [WorldEdit](https://enginehub.org/worldedit/) 加载时,bridge 自动监听其默认 schematic 目录:

```
<gameDir>/config/worldedit/schematics/
```

- **检测方式**:运行时 classpath 探测 `com.sk89q.worldedit.WorldEdit`,不读取版本号 —— 任何兼容版本都启用。
- **不引入依赖**:WorldEdit **不**作为 build.gradle 依赖,仅在运行时按需读取。
- **父目录联动**:同时监听 `config/worldedit/` 父目录;WorldEdit 在首次保存时创建 `/schematics/` 子目录,会被现有的 chain-watch 自动接管。
- **来源标识**:被这些目录收录的文件在 `source` 字段标记为 `"worldedit"`。
- **上传路由**:WorldEdit 加载时,`.schem` / `.schematic` 上传自动落到该目录(见 §2.3)。

### 2.10 客户端实现要求

- 所有 send 消息**必须**带 `requestId`(响应方 echo,客户端用来路由回调)
- 上传流程的 4 个 ack 期待点:
  1. `upload/init` → 等 `upload/ready`
  2. 收到 `upload/ready` 后才能开始发 binary chunks
  3. binary 发完发 `upload/commit`
  4. 等 `upload/result` + 可选 `upload/done`(done 只为 log SHA / emit progress=total)
- 下载流程的 3 个 ack 期待点:
  1. `download` → 等 `download/ready`(带 `size` 和 `sha256`)
  2. 累积 binary chunks(服务端 64 KiB 固定切片)
  3. `download/done` 或累计字节数 == `download/ready.size` 任一触发即完成
- 多下载并发靠 `requestId` 路由,客户端不做并发限流(协议允许多 in-flight download)
- 客户端必须按 `requestId` 而不是"最近一个文件名"路由响应(防回包错位)
- 客户端可选择提供 `sha256`(让服务端预校验),不提供也行(服务端独立算并在 done 回传)
- 孤儿 binary 帧(无 in-flight upload / 无 RECEIVING download)→ 静默丢弃 + warn log

### 2.11 错误码对账表

| 错误码 | 触发场景 | 客户端处理 |
|---|---|---|
| `AUTH_FAILED` | Token 错误(HTTP 401) | 弹"Token 错误"提示;disconnect |
| `BAD_JSON` | 收到 JSON 解析失败 | warn log,忽略 |
| `BAD_REQUEST` | 缺少必填字段 | warn log,忽略 |
| `BAD_FILENAME` | 文件名非法(后缀不在白名单 / 含 `..` / `\\` 等) | UI 失败,显示 `BAD_FILENAME` |
| `FILE_NOT_FOUND` | 下载的文件不存在 | UI 失败,显示 `FILE_NOT_FOUND` |
| `FILE_TOO_LARGE` | 超过 100 MB | UI 失败,提示用户 |
| `FILE_EXISTS` | 上传时文件已存在且 `overwrite:false` | UI 失败,提示用户(可重试 `overwrite=true`) |
| `BUSY` | 服务端单连接已有 in-flight upload | UI 失败;本地已做单任务排他,基本遇不到 |
| `NO_ACTIVE_UPLOAD` | commit 时 init 未发生 / requestId 不匹配 | warn log,reset state |
| `LENGTH_MISMATCH` | 二进制字节数 ≠ init 时的 `size` | UI 失败,reset state |
| `SHA_MISMATCH` | 客户端 init 时提供的 SHA 与服务端不一致 | UI 失败,reset state;可让用户重试不带 SHA |
| `IO_ERROR` | 磁盘读写失败 | UI 失败,显示 IO 错误 |
| `UNKNOWN_TYPE` | 未知消息类型 | warn log |
| `BUSY_LOCAL` (客户端本地) | UI 已有 in-flight transfer | UI 按钮 disabled;不发请求;TransferProgressBar 已可见,用户能感知 |

### 2.12 Building Gadgets 2 集成

当 [Building Gadgets 2](https://github.com/Direwolf20-MC/BuildingGadgets2) 加载时,bridge 在每次成功上传后向当前在线玩家推送一条聊天提示:

```
§a[BlockPrint] §f收到建筑小帮手模板: §e<fileName> §7→ §f<absolutePath>
```

- **检测方式**:运行时 classpath 探测 `com.direwolf20.buildinggadgets2.BuildingGadgets2`,不读取版本号;FMLLoadCompleteEvent 时再次确认。
- **不引入依赖**:BG2 **不**作为 build.gradle 依赖。
- **不强制路由**:与 WorldEdit 不同,BG2 上传**不**改变落盘目录 —— 仍按现有 §2.3 表路由(`.nbt` / `.json` → `schematics/`)。仅在聊天里通知玩家。
- **为什么这样设计**:BG2 自己的 Template Manager 从 `schematics/` 之外的固定位置读模板;在未与 BG2 作者协商前,不擅自动其目录。后续如需自动 routing,扩展 `ModDetection` + `resolveUploadTarget` 即可。

## 3. UDP 广播发现

Mod **每 2 秒**向 `255.255.255.255:18081` 广播 JSON。

```json
{
  "type": "blockprintlink/discovery",
  "version": "0.2.0",
  "wsPort": 18080,
  "tokenHint": "okk2"
}
```

- `tokenHint` 是 token 前 4 字符(token 不足 4 字符时显示全部)
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

**wscat (WebSocket 测试)**:
```bash
wscat -c "ws://192.168.1.42:18080/ws?token=okk2"
> {"type":"list","requestId":"r1"}
```

## 4. 游戏内命令

| 命令 | 说明 |
|---|---|
| `/blockprint-reload` | 重新加载 TOML 配置,token / 端口立即生效 |
| 按 **F7** | 切换 QR 码 overlay 显示/隐藏 |

## 5. QR 码

- 按 F7 显示在屏幕右上角(72×72 像素 + 文字)
- 编码内容:`blockprintcat://<IP>:<Port>/ws?token=<Token>`
- Android 端注册 `blockprintcat://` scheme 即可扫码直连

## 6. 协议变更日志

### v2(2026-06-24)

**破坏性变更**:
- 上传协议由单帧 `upload` + 立即发 binary 改为**两阶段**:`upload/init` → 等 `upload/ready` → 发 binary chunks → `upload/commit` → `upload/result` (+ `upload/done`)
- 下载协议由 `download` + `download/start` + binary 改为**两阶段**:`download` → `download/ready` + binary chunks + `download/done`
- 所有控制消息新增 `requestId` 字段(请求方生成,响应方 echo)
- `SHA_MISMATCH` 仅在客户端 init 时**主动提供** SHA 才校验
- `UNEXPECTED_BINARY` 错误移除(孤儿 binary 帧只 warn + drop)

**为什么改**:
- 之前 32 KB 以上的 `.json` 上传失败(WebSocket fragmentation + 服务端未处理 continuation frame + 客户端 vs 服务端 SHA 算字节不同步)
- 之前 binary 帧承担双重含义(WS fragmentation body + 单次 upload payload),容易 race
- 新协议将"开始接收"和"实际字节流"通过 `ready/done` 文本帧显式分割
