# ⚠️ 重要：清理浏览器缓存

## 为什么需要清理缓存？

你的浏览器可能缓存了旧版本的 JavaScript 文件，导致：
- ❌ 看不到新的"A开始"和"B开始"按钮
- ❌ 请求旧的API端点（`/start`）返回404
- ❌ WebSocket连接失败

## 🚀 快速清理方法

### **方法1: 硬刷新（推荐）**

直接在页面按：

**Windows/Linux**:
```
Ctrl + F5
或
Ctrl + Shift + R
```

**Mac**:
```
Cmd + Shift + R
```

### **方法2: 清除缓存（彻底）**

#### **Chrome / Edge**

1. 按 `Ctrl + Shift + Delete`（Mac: `Cmd + Shift + Delete`）
2. 选择"时间范围"：**最近1小时**
3. 勾选：
   -  Cookie和其他网站数据
   -  缓存的图片和文件
4. 点击"清除数据"

#### **Firefox**

1. 按 `Ctrl + Shift + Delete`
2. 选择"要清除的时间范围"：**最近1小时**
3. 勾选：
   -  Cookie
   -  缓存
4. 点击"立即清除"

### **方法3: 使用隐身模式（测试用）**

**Chrome/Edge**:
```
Ctrl + Shift + N
```

**Firefox**:
```
Ctrl + Shift + P
```

然后访问 `http://localhost:8080/lobby.html`

---

##  验证缓存已清除

清除后，检查以下几点：

### **1. 查看网络请求**

按 `F12` 打开开发者工具 → Network标签

刷新页面，应该看到：
```
lobby.js?v=2.0      200 OK    [新版本]
game-architecture-a.js?v=2.0  200 OK
```

如果看到的是：
```
lobby.js    304 Not Modified  [旧缓存]
```
说明缓存没清除干净，重复上面的步骤。

### **2. 检查按钮**

打开大厅，创建房间后应该看到：

```
┌─────────────────────────┐
│  桌子 #0                │
│  房主：xushikuan        │
│  2/2 人                 │
│                         │
│ [Start (Arch A)]      │
│ [Start (Arch B)]  🔒    │
│ [退出]                  │
└─────────────────────────┘
```

### **3. 检查API请求**

点击"Start (Arch A)"后，F12 → Network应该看到：

```
POST /api/lobby/rooms/1/start-architecture-a   200 OK
```

而不是：
```
POST /api/lobby/rooms/1/start  ❌ 404 Not Found
```

---

## 🐛 如果还是有问题

### **完全清理步骤**

1. **关闭所有浏览器窗口**
2. **删除浏览器数据**:
   
   **Chrome数据位置**:
   ```
   Windows: C:\Users\[用户名]\AppData\Local\Google\Chrome\User Data\Default\Cache
   ```
   
3. **重启浏览器**
4. **访问** `http://localhost:8080/lobby.html`
5. **按 Ctrl+F5 强制刷新**

### **使用不同浏览器测试**

如果Chrome有问题，试试：
- Edge
- Firefox
- Chrome隐身模式

### **检查服务器日志**

确保服务器正在运行并加载了新代码：

```bash
# 停止旧服务器
Ctrl + C

# 清理并重新编译
mvn clean package

# 启动新服务器
mvn spring-boot:run
```

日志应该显示：
```
WebSocketConfig loaded
GameTickScheduler initialized with 25Hz
GameRoomManager ready
EventBus subscriptions: 5
```

---

## 📱 移动端测试

如果在手机上测试：

1. **Android Chrome**: 
   - 设置 → 隐私和安全 → 清除浏览数据
   
2. **iOS Safari**: 
   - 设置 → Safari → 清除历史记录与网站数据

---

## ⏰ 预计清理时间

- 硬刷新：**1秒**
- 清除缓存：**10秒**
- 完全清理：**30秒**

---

## 🎯 成功标志

清理成功后，你应该能：

 看到两个架构选择按钮  
 点击"Start (Arch A)"正常跳转  
 游戏页面显示 `[Architecture A: Server-Authoritative]`  
 控制台没有404错误  
 WebSocket连接成功  

---

**准备好了？ → [查看测试指南](TESTING_GUIDE.md)** 🚀

