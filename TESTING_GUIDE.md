# 测试指南 - Architecture A 验证

## 🐛 已修复的问题

### 1.  前端按钮显示问题
**问题**: 房主只显示"开始"和"退出"，没有显示两个架构按钮  
**修复**: 
- 已在 `lobby.js` 中添加两个按钮：`Start (Arch A)` 和 `Start (Arch B)`
- 添加版本号 `?v=2.0` 破坏浏览器缓存
- 添加 tooltip 说明每个架构的特点

### 2.  API 404 错误
**问题**: 请求 `/api/lobby/rooms/1/start` 返回404  
**原因**: 旧的 `/start` 端点已移除
**修复**:
- 新端点：`/api/lobby/rooms/{roomId}/start-architecture-a`
- 新端点：`/api/lobby/rooms/{roomId}/start-architecture-b`
- 前端已更新调用新API

### 3.  数据持久化缺失
**问题**: 游戏结束后没有保存到数据库  
**修复**:
- `GameTickScheduler.finishGame()` 现在会保存完整的游戏结果到 `game_logs` 表
- 结果JSON包含：玩家数据、元数据（赢家、地图、架构类型等）
- 游戏结束后自动重置房间状态

---

## 🧪 完整测试流程

### **步骤1: 清理浏览器缓存**

**重要！** 必须先清理缓存才能看到新代码：

**Chrome/Edge**:
```
Ctrl + Shift + Delete → 清除最近1小时的数据 → 勾选"缓存的图片和文件"
或者
Ctrl + F5 强制刷新
```

**Firefox**:
```
Ctrl + Shift + Delete → 清除缓存
```

### **步骤2: 启动服务器**

```bash
# 停止旧进程（如果在运行）
# 启动新服务器
mvn clean spring-boot:run
```

确认日志中看到：
```
WebSocket configuration loaded
GameTickScheduler started with 25Hz tick rate
EventBus initialized
```

### **步骤3: 双人测试（推荐）**

#### **窗口A - 房主**

1. 访问 `http://localhost:8080/login.html`
2. 登录账号 A（例如：xushikuan）
3. 进入大厅 `http://localhost:8080/lobby.html`
4. 点击"创建房间"
5. 设置：
   - 玩家人数：2
   - 地图：Nebula-01
   - 胜利条件：Score 50
6. 点击"创建"

**验证点**:
-  应该看到 **两个绿色按钮**：
  - `Start (Arch A)` - 服务器权威
  - `Start (Arch B)` - P2P锁步（灰色）
-  鼠标悬停按钮应显示tooltip

#### **窗口B - 队友**

1. 另一个浏览器/隐身窗口登录账号 B（例如：zhaoyuan）
2. 进入大厅
3. 看到房主的房间，点击"加入"
4. 点击"准备"按钮（变成绿色"已准备"）

**验证点**:
-  房主能看到队友加入
-  房主能看到队友准备状态

#### **房主点击 Start (Arch A)**

1. 确认队友已准备
2. 点击 `Start (Arch A)` 按钮
3. 自动跳转到游戏页面

**验证点**:
-  URL包含 `?roomId=1&win=SCORE_50&arch=A`
-  页面顶部显示 `[Architecture A: Server-Authoritative]`
-  3秒倒计时
-  倒计时结束后游戏开始

#### **队友自动进入游戏**

队友页面应该：
-  自动刷新房间状态（游戏已开始）
-  显示"进入游戏"按钮
-  点击后跳转到同一游戏

### **步骤4: 游戏测试**

#### **控制测试**

**窗口A（房主）**:
- 按 `W` - 向上移动
- 按 `S` - 向下移动
- 按 `A` - 向左移动
- 按 `D` - 向右移动
- 按 `J` 或 `Space` - 射击

**验证点**:
-  窗口B能看到窗口A的飞机移动
-  窗口B能看到窗口A发射的子弹
-  移动流畅（25Hz服务器，60FPS渲染）

#### **战斗测试**

1. 窗口A向窗口B射击
2. 子弹命中窗口B

**验证点**:
-  窗口B的HP下降（100 → 90 → 80...）
-  窗口A的分数上升（+50分/击杀）
-  右侧记分板实时更新
-  血条显示正确

#### **游戏结束**

继续游戏直到某人达到50分或HP=0

**验证点**:
-  游戏自动结束
-  显示 "Game Over" 遮罩
-  显示赢家和分数
-  点击 "Return to Lobby" 返回大厅

### **步骤5: 验证数据持久化**

#### **方法1: 查看日志**

服务器日志应该显示：
```
Game 1 finished! Winner: xushikuan, Scores: {xushikuan=50, zhaoyuan=30}
Game log saved for room 1
GameWorld removed for room 1
```

#### **方法2: 查询数据库**

```bash
# 连接SQLite数据库
sqlite3 D:/sqlite_database/code_search.db

# 查询游戏日志
SELECT * FROM game_logs ORDER BY id DESC LIMIT 1;

# 查看结果JSON
SELECT result_json FROM game_logs ORDER BY id DESC LIMIT 1;
```

**期望结果JSON格式**:
```json
{
  "players": [
    {
      "username": "xushikuan",
      "score": 50,
      "hp": 80,
      "alive": true,
      "elapsedMillis": 45000,
      "finished": true
    },
    {
      "username": "zhaoyuan",
      "score": 30,
      "hp": 0,
      "alive": false,
      "elapsedMillis": 45000,
      "finished": true
    }
  ],
  "metadata": {
    "winner": "xushikuan",
    "mapName": "Nebula-01",
    "winMode": "SCORE_50",
    "maxPlayers": 2,
    "architecture": "A",
    "totalFrames": 1125
  }
}
```

### **步骤6: 验证房间重置**

游戏结束后：
-  返回大厅
-  原房间显示"等待中"（非游戏中）
-  可以再次点击"准备"
-  房主可以再次开始游戏

---

## 🔍 常见问题排查

### **Q1: 还是只显示"开始"按钮**

**原因**: 浏览器缓存  
**解决**:
1. 按 `Ctrl + Shift + Delete` 清除缓存
2. 或者按 `Ctrl + F5` 强制刷新
3. 或者使用隐身模式

### **Q2: WebSocket连接失败**

**检查**:
```bash
# 查看服务器日志
# 应该看到：
WebSocket connected: xxx
Player xxx joined game room 1 (Architecture A)
```

**可能原因**:
- 防火墙阻止8080端口
- 服务器未启动
- URL错误（应该是 ws://localhost:8080/ws/game）

### **Q3: 游戏卡顿/延迟高**

**正常延迟**: 
- 本地：20-50ms
- 局域网：50-100ms

**检查**:
```javascript
// 浏览器Console查看
// 应该看到每秒约25次 GAME_STATE 消息
```

**可能原因**:
- CPU占用过高
- 多个游戏房间同时运行
- 网络不稳定

### **Q4: 数据库保存失败**

**检查日志**:
```
Failed to save game log for room 1: ...
```

**可能原因**:
- 数据库路径错误（application.properties）
- 权限不足
- 磁盘空间不足

**修复**:
```bash
# 检查数据库文件是否存在
ls -la D:/sqlite_database/code_search.db

# 检查权限
# Windows: 右键文件 → 属性 → 安全
```

---

## 📊 性能指标

### **正常指标**

| 指标 | 期望值 | 测量方法 |
|------|--------|----------|
| 游戏Tick频率 | 25Hz | 服务器日志：40ms/帧 |
| 渲染帧率 | 60FPS | 浏览器Console: requestAnimationFrame |
| 输入延迟 | <100ms | 按键到屏幕响应时间 |
| 状态同步延迟 | <50ms | WebSocket消息往返时间 |
| 内存占用 | <100MB/房间 | JVM监控 |

### **压力测试**

```bash
# 创建10个房间，每个2人
# 预期：
# - CPU: <50%
# - 内存: <1GB
# - 延迟: <150ms
```

---

## 🎯 测试检查表

### **前端UI**
- [ ] 房主看到两个架构按钮
- [ ] 按钮有正确的颜色和tooltip
- [ ] 游戏页面显示架构标识
- [ ] 倒计时正常显示

### **网络通信**
- [ ] WebSocket连接成功
- [ ] 输入实时发送（20Hz）
- [ ] 状态实时接收（25Hz）
- [ ] 断线重连正常

### **游戏逻辑**
- [ ] 移动流畅
- [ ] 碰撞检测准确
- [ ] 分数计算正确
- [ ] 游戏结束条件正确

### **数据持久化**
- [ ] 游戏结束后保存到数据库
- [ ] result_json格式正确
- [ ] 包含所有玩家数据
- [ ] 包含元数据

### **房间管理**
- [ ] 游戏结束后房间重置
- [ ] 可以再次开始游戏
- [ ] 内存正确清理

---

## 📝 测试报告模板

```
测试时间：2025-11-19
测试人员：___________
测试版本：v2.0

| 测试项 | 结果 | 备注 |
|--------|------|------|
| 前端按钮显示 | /❌ | |
| WebSocket连接 | /❌ | |
| 实时同步 | /❌ | |
| 碰撞检测 | /❌ | |
| 数据保存 | /❌ | |
| 房间重置 | /❌ | |

性能数据：
- 平均延迟：_____ ms
- 帧率：_____ FPS
- 内存占用：_____ MB

问题记录：
1. 
2. 
3. 
```

---

**测试完成后请反馈结果！** 🎉

