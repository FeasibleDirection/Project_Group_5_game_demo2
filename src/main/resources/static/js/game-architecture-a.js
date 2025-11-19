// game-architecture-a.js
// Architecture A: 客户端只发送输入，服务器权威控制游戏状态

const CANVAS_WIDTH = 480;
const CANVAS_HEIGHT = 640;
const FPS = 60;

let ws = null;
let roomId = null;
let username = null;
let token = null;
let winMode = null;

// 游戏状态（从服务器接收）
let gameState = {
    phase: 'WAITING',
    frame: 0,
    countdownMs: 0,
    elapsedMs: 0,
    players: [],
    bullets: [],
    asteroids: []  // 石头数据
};

// 输入状态（本地）
let keys = {
    w: false,
    a: false,
    s: false,
    d: false,
    j: false,
    ' ': false  // Space也可以射击
};

let canvas, ctx;
let lastSendTime = 0;
const INPUT_SEND_INTERVAL = 50; // 每50ms发送一次输入（20Hz）

// 初始化
document.addEventListener('DOMContentLoaded', async () => {
    // 1. 从URL获取参数
    const params = new URLSearchParams(window.location.search);
    roomId = parseInt(params.get('roomId'));
    winMode = params.get('win') || 'SCORE_50';
    const arch = params.get('arch') || 'A';
    
    console.log('[INIT]', 'roomId:', roomId, 'winMode:', winMode, 'arch:', arch);
    
    // 从localStorage获取用户信息（使用session.js中定义的键名）
    username = localStorage.getItem('game_demo_username');
    token = localStorage.getItem('game_demo_token');
    
    console.log('[INIT]', 'username:', username, 'token:', token ? '存在' : '不存在');
    
    if (!roomId || !username || !token) {
        alert('参数错误，返回大厅');
        window.location.href = '/lobby.html';
        return;
    }
    
    if (arch !== 'A') {
        alert('当前只支持 Architecture A');
        window.location.href = '/lobby.html';
        return;
    }
    
    // 2. 初始化UI
    document.getElementById('lblRoom').textContent = `Room ${roomId}`;
    document.getElementById('lblUser').textContent = username;
    document.getElementById('lblArchitecture').textContent = '[Architecture A: Server-Authoritative]';
    
    // 3. 初始化Canvas
    canvas = document.getElementById('gameCanvas');
    ctx = canvas.getContext('2d');
    
    // 4. 连接WebSocket
    connectWebSocket();
    
    // 5. 绑定输入
    setupInput();
    
    // 6. 启动渲染循环（60 FPS）
    setInterval(renderLoop, 1000 / FPS);
    
    // 7. 启动输入发送循环（20 Hz）
    setInterval(sendInput, INPUT_SEND_INTERVAL);
    
    // 8. 返回大厅按钮
    document.getElementById('btnLeave').addEventListener('click', leaveGame);
    document.getElementById('btnBackLobby').addEventListener('click', leaveGame);
});

/**
 * 连接WebSocket
 */
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/game`;
    ws = new WebSocket(wsUrl);
    
    ws.onopen = () => {
        console.log('WebSocket connected (Architecture A)');
        
        // 发送JOIN_GAME消息
        ws.send(JSON.stringify({
            type: 'JOIN_GAME',
            roomId: roomId,
            username: username,
            token: token
        }));
    };
    
    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        handleServerMessage(msg);
    };
    
    ws.onerror = (error) => {
        console.error('WebSocket error:', error);
        alert('连接失败');
    };
    
    ws.onclose = () => {
        console.log('WebSocket closed');
    };
}

/**
 * 处理服务器消息
 */
function handleServerMessage(msg) {
    switch (msg.type) {
        case 'CONNECTED':
            console.log('Server acknowledged connection');
            break;
            
        case 'JOINED':
            console.log('Successfully joined game:', msg);
            break;
            
        case 'NOT_IN_ROOM':
            console.error('[NOT_IN_ROOM]', msg.message);
            console.log('[DEBUG]', '可能原因：GameWorld未创建或玩家未在房间中');
            console.log('[DEBUG]', '请先在大厅点击"Start (Arch A)"按钮开始游戏');
            alert(msg.message + '\n\n请先在大厅点击"Start (Arch A)"按钮');
            window.location.href = '/lobby.html';
            break;
            
        case 'GAME_STATE':
            // 更新游戏状态（服务器权威）
            console.log('[GAME_STATE]', 'phase:', msg.phase, 'players:', msg.players?.length, 'bullets:', msg.bullets?.length, 'asteroids:', msg.asteroids?.length);
            gameState.phase = msg.phase;
            gameState.frame = msg.frame;
            gameState.countdownMs = msg.countdownMs || 0;
            gameState.elapsedMs = msg.elapsedMs || 0;
            gameState.players = msg.players || [];
            gameState.bullets = msg.bullets || [];
            gameState.asteroids = msg.asteroids || [];  // 接收石头数据
            
            // 更新UI
            updateUI();
            break;
            
        case 'ERROR':
            console.error('Server error:', msg.message);
            alert('错误: ' + msg.message);
            break;
    }
}

/**
 * 设置输入监听
 */
function setupInput() {
    document.addEventListener('keydown', (e) => {
        const key = e.key.toLowerCase();
        if (key in keys) {
            keys[key] = true;
            e.preventDefault();
        }
    });
    
    document.addEventListener('keyup', (e) => {
        const key = e.key.toLowerCase();
        if (key in keys) {
            keys[key] = false;
            e.preventDefault();
        }
    });
}

/**
 * 发送输入到服务器（Architecture A核心）
 * 客户端只发送输入，不计算位置
 */
function sendInput() {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (gameState.phase !== 'IN_PROGRESS') return;
    
    const now = performance.now();
    if (now - lastSendTime < INPUT_SEND_INTERVAL) return;
    lastSendTime = now;
    
    ws.send(JSON.stringify({
        type: 'PLAYER_INPUT',
        moveUp: keys.w,
        moveDown: keys.s,
        moveLeft: keys.a,
        moveRight: keys.d,
        fire: keys.j || keys[' ']
    }));
}

/**
 * 渲染循环（60FPS）
 * Architecture A: 只渲染服务器状态，不做本地计算
 */
function renderLoop() {
    // 清空画布
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
    
    switch (gameState.phase) {
        case 'WAITING':
            // 等待玩家
            ctx.fillStyle = '#fff';
            ctx.font = '32px Arial';
            ctx.textAlign = 'center';
            ctx.fillText('Waiting for players...', CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2);
            break;
            
        case 'COUNTDOWN':
            // 倒计时
            const seconds = Math.ceil(gameState.countdownMs / 1000);
            ctx.fillStyle = '#fff';
            ctx.font = '72px Arial';
            ctx.textAlign = 'center';
            ctx.fillText(seconds, CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2);
            
            ctx.font = '24px Arial';
            ctx.fillText('Get Ready!', CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2 + 50);
            break;
            
        case 'IN_PROGRESS':
            // 渲染游戏
            renderGame();
            break;
            
        case 'FINISHED':
            // 游戏结束
            renderGame(); // 先画最后一帧
            
            ctx.fillStyle = 'rgba(0, 0, 0, 0.7)';
            ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
            
            ctx.fillStyle = '#fff';
            ctx.font = '48px Arial';
            ctx.textAlign = 'center';
            ctx.fillText('Game Over!', CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2);
            
            // 显示胜者
            const winner = gameState.players.reduce((max, p) => 
                p.score > (max?.score || 0) ? p : max, null);
            if (winner) {
                ctx.font = '24px Arial';
                ctx.fillText(`Winner: ${winner.username}`, CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2 + 50);
            }
            
            // 显示overlay
            document.getElementById('overlay').classList.remove('hidden');
            document.getElementById('overTitle').textContent = 'Game Over';
            document.getElementById('overSummary').textContent = 
                winner ? `Winner: ${winner.username} (${winner.score} pts)` : '';
            break;
    }
}

/**
 * 渲染游戏实体
 */
function renderGame() {
    // 渲染玩家
    gameState.players.forEach(player => {
        if (!player.alive) return;
        
        // 自己是绿色，队友是蓝色，敌人是红色
        let color = '#00f'; // 默认蓝色
        if (player.username === username) {
            color = '#0f0'; // 自己是绿色
        }
        
        // 绘制飞机（简单矩形）
        ctx.fillStyle = color;
        ctx.fillRect(player.x - 16, player.y - 16, 32, 32);
        
        // 显示用户名
        ctx.fillStyle = '#fff';
        ctx.font = '12px Arial';
        ctx.textAlign = 'center';
        ctx.fillText(player.username, player.x, player.y - 25);
        
        // 血条
        const barWidth = 32;
        const barHeight = 4;
        ctx.fillStyle = '#f00';
        ctx.fillRect(player.x - barWidth/2, player.y - 20, barWidth, barHeight);
        ctx.fillStyle = '#0f0';
        ctx.fillRect(player.x - barWidth/2, player.y - 20, barWidth * (player.hp / 100), barHeight);
    });
    
    // 渲染子弹
    ctx.fillStyle = '#ff0';
    gameState.bullets.forEach(bullet => {
        ctx.beginPath();
        ctx.arc(bullet.x, bullet.y, 4, 0, Math.PI * 2);
        ctx.fill();
    });
    
    // 渲染石头（障碍物）
    gameState.asteroids.forEach(asteroid => {
        // 根据血量和大小选择颜色
        let color = asteroid.isBig ? '#a67c52' : '#c48a4b';
        if (asteroid.hp < (asteroid.isBig ? 2 : 1)) {
            color = '#8a6239'; // 受伤后变暗
        }
        
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(asteroid.x, asteroid.y, asteroid.radius, 0, Math.PI * 2);
        ctx.fill();
        
        // 石头边框（增加立体感）
        ctx.strokeStyle = '#6a4a2a';
        ctx.lineWidth = 2;
        ctx.stroke();
    });
}

/**
 * 更新UI（HP、分数等）
 */
function updateUI() {
    // 找到自己的玩家数据
    const myPlayer = gameState.players.find(p => p.username === username);
    if (myPlayer) {
        document.getElementById('hpText').textContent = myPlayer.hp;
        document.getElementById('scoreText').textContent = myPlayer.score;
    }
    
    // 更新时间
    if (gameState.elapsedMs) {
        const seconds = Math.floor(gameState.elapsedMs / 1000);
        const minutes = Math.floor(seconds / 60);
        const secs = seconds % 60;
        document.getElementById('timeText').textContent = 
            `${minutes}:${secs.toString().padStart(2, '0')}`;
    }
    
    // 更新记分板
    const scoreList = document.getElementById('scoreList');
    scoreList.innerHTML = '';
    
    // 按分数排序
    const sortedPlayers = [...gameState.players].sort((a, b) => b.score - a.score);
    
    sortedPlayers.forEach(player => {
        const li = document.createElement('li');
        li.textContent = `${player.username}: ${player.score} (HP: ${player.hp})`;
        if (player.username === username) {
            li.style.fontWeight = 'bold';
            li.style.color = '#0f0';
        }
        if (!player.alive) {
            li.style.color = '#888';
            li.style.textDecoration = 'line-through';
        }
        scoreList.appendChild(li);
    });
}

/**
 * 离开游戏
 */
function leaveGame() {
    if (ws) {
        ws.send(JSON.stringify({ type: 'LEAVE_GAME' }));
        ws.close();
    }
    window.location.href = '/lobby.html';
}

