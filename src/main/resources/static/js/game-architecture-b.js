// game-architecture-b.js
// Architecture B: P2P Lockstep with Host (via server relay)

const CANVAS_WIDTH = 480;
const CANVAS_HEIGHT = 640;
const FPS = 60;

// 网络
let ws = null;
let roomId = null;
let username = null;
let token = null;
let winMode = null;

// 角色 & Host 信息
let isHost = false;
let hostUsername = null;

// 公共的渲染状态（所有客户端一致）
let gameState = {
    phase: 'WAITING',
    frame: 0,
    elapsedMs: 0,
    players: [],
    bullets: [],
    asteroids: []
};

// Host 专用的“真实世界状态”（只在 Host 浏览器里维护）
let simState = null;  // { startTime, players{username:{...}}, bullets[], asteroids[], frame }

// 键盘输入
let keys = {
    w: false,
    a: false,
    s: false,
    d: false,
    j: false,
    ' ': false
};

// 其它玩家输入（Host 使用）
let remoteInputBuffer = {}; // username -> { up,down,left,right,fire }

let canvas, ctx;
let lastInputSendTime = 0;
const INPUT_SEND_INTERVAL = 50; // 20Hz
let lastSimTime = 0;

// 初始化
document.addEventListener('DOMContentLoaded', () => {
    const params = new URLSearchParams(window.location.search);
    roomId = parseInt(params.get('roomId'));
    winMode = params.get('win') || 'SCORE_50';
    const arch = params.get('arch') || 'B';

    username = localStorage.getItem('game_demo_username');
    token = localStorage.getItem('game_demo_token');

    if (!roomId || !username || !token) {
        alert('参数错误，返回大厅');
        window.location.href = '/lobby.html';
        return;
    }

    if (arch !== 'B') {
        alert('当前脚本仅适用于 Architecture B');
        window.location.href = '/lobby.html';
        return;
    }

    document.getElementById('lblRoom').textContent = `Room ${roomId}`;
    document.getElementById('lblUser').textContent = username;
    document.getElementById('lblArchitecture').textContent = '[Architecture B: P2P Host Lockstep]';

    canvas = document.getElementById('gameCanvas');
    ctx = canvas.getContext('2d');

    setupInput();
    setupButtons();
    connectWebSocketB();

    setInterval(renderLoop, 1000 / FPS);
    setInterval(sendInputLoop, INPUT_SEND_INTERVAL);
    setInterval(simulationLoopHost, 50); // Host 每 50ms 推进一次世界
});

// --- WebSocket 连接（/ws/game-b） ---

function connectWebSocketB() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/game-b`;
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log('[ArchB] WebSocket connected');

        ws.send(JSON.stringify({
            type: 'JOIN_GAME_B',
            roomId,
            username,
            token
        }));
    };

    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        handleServerMessageB(msg);
    };

    ws.onerror = (err) => {
        console.error('[ArchB] WebSocket error', err);
        alert('Architecture B 连接失败');
    };

    ws.onclose = () => {
        console.log('[ArchB] WebSocket closed (B)');
    };
}

function handleServerMessageB(msg) {
    switch (msg.type) {
        case 'CONNECTED':
            console.log('[ArchB] CONNECTED', msg);
            break;

        case 'JOINED_B':
            console.log('[ArchB] JOINED_B', msg);
            isHost = !!msg.isHost;
            if (isHost) {
                hostUsername = username;
                initHostSimState();
            }
            break;

        case 'ROOM_STATE_B':
            hostUsername = msg.hostUsername;
            console.log('[ArchB] ROOM_STATE_B host=', hostUsername, 'players=', msg.players);
            break;

        case 'HOST_CHANGED_B':
            hostUsername = msg.hostUsername;
            console.log('[ArchB] HOST_CHANGED_B new host=', hostUsername);
            // 如果自己变成 host（极端情况），可以在这里 initHostSimState()
            if (hostUsername === username && !isHost) {
                isHost = true;
                initHostSimState();
            }
            break;

        case 'LOCKSTEP_INPUT':
            // Host 使用：收集其它玩家输入
            if (isHost) {
                const from = msg.from;
                if (!from) return;
                remoteInputBuffer[from] = {
                    up: !!msg.moveUp,
                    down: !!msg.moveDown,
                    left: !!msg.moveLeft,
                    right: !!msg.moveRight,
                    fire: !!msg.fire
                };
            }
            break;

        case 'GAME_STATE':
            // 所有客户端（包括 host 自己）都用这个来渲染
            // Host 也可以直接用它覆盖 gameState，以保证和 peers 一致
            gameState.phase = msg.phase || 'IN_PROGRESS';
            gameState.frame = msg.frame || 0;
            gameState.elapsedMs = msg.elapsedMs || 0;
            gameState.players = msg.players || [];
            gameState.bullets = msg.bullets || [];
            gameState.asteroids = msg.asteroids || [];
            break;

        case 'ERROR':
            console.error('[ArchB] ERROR', msg.message);
            alert('Arch B Error: ' + msg.message);
            // 🔥 错误返回大厅，带上标记防止无限跳转
            if (msg.message && msg.message.includes('Not in room')) {
                window.location.href = '/lobby.html?fromGameError=1';
            }
            break;

        case 'NOT_IN_ROOM':
            console.error('[ArchB] NOT_IN_ROOM', msg.message);
            alert((msg.message || 'Not in room') + '\n\n请先在大厅点击 "Start" 按钮');
            // 🔥 错误返回大厅，带上标记防止无限跳转
            window.location.href = '/lobby.html?fromGameError=1';
            break;
    }
}

// --- Host：初始化本地物理世界 ---

function initHostSimState() {
    console.log('[ArchB] I am HOST, init sim world');

    simState = {
        startTime: performance.now(),
        players: {},
        bullets: [],
        asteroids: [],
        frame: 0,
        lastAsteroidSpawnMs: 0
    };

    // 先把自己加进去，坐标和 A 架构类似
    simState.players[username] = {
        username,
        x: CANVAS_WIDTH / 2,
        y: CANVAS_HEIGHT - 80,
        hp: 100,
        score: 0,
        alive: true,
        lastFireTime: 0
    };
}

// --- 输入 & 按钮 ---

function setupInput() {
    document.addEventListener('keydown', (e) => {
        const k = e.key.toLowerCase();
        if (k in keys) {
            keys[k] = true;
            e.preventDefault();
        }
    });

    document.addEventListener('keyup', (e) => {
        const k = e.key.toLowerCase();
        if (k in keys) {
            keys[k] = false;
            e.preventDefault();
        }
    });
}

function setupButtons() {
    document.getElementById('btnLeave').addEventListener('click', () => leaveGameB());
    document.getElementById('btnBackLobby').addEventListener('click', () => leaveGameB());
}

function leaveGameB() {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'LEAVE_GAME' }));
        ws.close();
    }
    // 🔥 主动退出，带上标记防止无限跳转
    window.location.href = '/lobby.html?fromGameExit=1';
}

// --- 客户端输入上报（所有玩家都发 LOCKSTEP_INPUT） ---

function sendInputLoop() {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (!hostUsername) return; // host 未选出来之前不发

    const now = performance.now();
    if (now - lastInputSendTime < INPUT_SEND_INTERVAL) return;
    lastInputSendTime = now;

    const payload = {
        type: 'LOCKSTEP_INPUT',
        moveUp: keys.w,
        moveDown: keys.s,
        moveLeft: keys.a,
        moveRight: keys.d,
        fire: keys.j || keys[' ']
    };
    ws.send(JSON.stringify(payload));
}

// --- Host：模拟物理 & 广播 GAME_STATE ---

function simulationLoopHost() {
    if (!isHost) return;
    if (!simState) return;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;

    const now = performance.now();
    const deltaMs = lastSimTime === 0 ? 50 : (now - lastSimTime);
    lastSimTime = now;
    const deltaSeconds = deltaMs / 1000;

    // 1) 把自己的输入写入 remoteInputBuffer
    remoteInputBuffer[username] = {
        up: keys.w,
        down: keys.s,
        left: keys.a,
        right: keys.d,
        fire: keys.j || keys[' ']
    };

    // 2) 根据 remoteInputBuffer 更新所有玩家
    const PLAYER_SPEED = 200;
    const MIN_FIRE_INTERVAL_MS = 200;

    Object.entries(remoteInputBuffer).forEach(([uname, input]) => {
        if (!simState.players[uname]) {
            // 新玩家进来，创建实体
            simState.players[uname] = {
                username: uname,
                x: CANVAS_WIDTH / 2,
                y: CANVAS_HEIGHT - 80,
                hp: 100,
                score: 0,
                alive: true,
                lastFireTime: 0
            };
        }
        const p = simState.players[uname];
        if (!p.alive) return;

        // 运动
        let vx = 0, vy = 0;
        if (input.up) vy -= 1;
        if (input.down) vy += 1;
        if (input.left) vx -= 1;
        if (input.right) vx += 1;

        const mag = Math.hypot(vx, vy);
        if (mag > 0) {
            vx = vx / mag * PLAYER_SPEED;
            vy = vy / mag * PLAYER_SPEED;
        }

        p.x += vx * deltaSeconds;
        p.y += vy * deltaSeconds;

        // 边界
        p.x = Math.max(16, Math.min(CANVAS_WIDTH - 16, p.x));
        p.y = Math.max(16, Math.min(CANVAS_HEIGHT - 16, p.y));

        // 射击
        const nowMs = performance.now();
        if (input.fire && nowMs - p.lastFireTime >= MIN_FIRE_INTERVAL_MS) {
            simState.bullets.push({
                owner: uname,
                x: p.x,
                y: p.y - 20,
                vx: 0,
                vy: -400,
                damage: 1
            });
            p.lastFireTime = nowMs;
        }
    });

    // 3) 更新子弹
    simState.bullets = simState.bullets.filter(b => {
        b.x += b.vx * deltaSeconds;
        b.y += b.vy * deltaSeconds;
        return b.y > -10 && b.y < CANVAS_HEIGHT + 10;
    });

    // 4) 生成石头（最多 20 个）
    simState.lastAsteroidSpawnMs += deltaMs;
    const ASTEROID_INTERVAL_MS = 800;
    const ASTEROID_MAX = 20;
    if (simState.lastAsteroidSpawnMs >= ASTEROID_INTERVAL_MS &&
        simState.asteroids.length < ASTEROID_MAX) {

        simState.lastAsteroidSpawnMs = 0;
        const x = 30 + Math.random() * (CANVAS_WIDTH - 60);
        const isBig = Math.random() < 0.4;

        simState.asteroids.push({
            x,
            y: -20,
            vy: 100 + Math.random() * 80,
            radius: isBig ? 20 : 12,
            hp: isBig ? 3 : 1,
            isBig
        });
    }

    // 5) 更新石头位置
    simState.asteroids = simState.asteroids.filter(a => {
        a.y += a.vy * deltaSeconds;
        return a.y < CANVAS_HEIGHT + 30;
    });

    // 6) 碰撞检测：子弹 vs 石头 / 石头 vs 玩家
    // 子弹 vs 石头
    simState.bullets = simState.bullets.filter(b => {
        let hit = false;
        simState.asteroids.forEach(a => {
            if (hit) return;
            const dx = b.x - a.x;
            const dy = b.y - a.y;
            const dist = Math.hypot(dx, dy);
            if (dist < a.radius + 4) {
                a.hp -= b.damage;
                hit = true;
                if (a.hp <= 0) {
                    // 找枪手加分
                    const p = simState.players[b.owner];
                    if (p) {
                        p.score += a.isBig ? 10 : 5;
                    }
                    a._destroyed = true;
                }
            }
        });
        simState.asteroids = simState.asteroids.filter(a => !a._destroyed);
        return !hit;
    });

    // 石头 vs 玩家
    simState.asteroids.forEach(a => {
        Object.values(simState.players).forEach(p => {
            if (!p.alive) return;
            const dx = a.x - p.x;
            const dy = a.y - p.y;
            const dist = Math.hypot(dx, dy);
            const collisionDist = a.radius + 18;
            if (dist < collisionDist) {
                p.hp -= 6; // 碰一下掉 6 血（和后端版本类似）
                if (p.hp <= 0) {
                    p.hp = 0;
                    p.alive = false;
                }
                a._hitPlayer = true;
            }
        });
    });
    simState.asteroids = simState.asteroids.filter(a => !a._hitPlayer);

    // 7) 递增帧号
    simState.frame += 1;
    const elapsedMs = performance.now() - simState.startTime;

    // 8) 构造 GAME_STATE 并通过 WebSocket 广播（由服务器中转）
    const playersArr = Object.values(simState.players).map(p => ({
        username: p.username,
        x: p.x,
        y: p.y,
        hp: p.hp,
        score: p.score,
        alive: p.alive
    }));

    const bulletsArr = simState.bullets.map(b => ({
        owner: b.owner,
        x: b.x,
        y: b.y
    }));

    const asteroidsArr = simState.asteroids.map(a => ({
        x: a.x,
        y: a.y,
        radius: a.radius,
        hp: a.hp,
        isBig: a.isBig
    }));

    const msg = {
        type: 'GAME_STATE',
        phase: 'IN_PROGRESS',
        frame: simState.frame,
        elapsedMs,
        players: playersArr,
        bullets: bulletsArr,
        asteroids: asteroidsArr
    };

    ws.send(JSON.stringify(msg));
}

// --- 渲染（所有客户端共用） ---

function renderLoop() {
    if (!ctx) return;

    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

    if (gameState.phase === 'WAITING') {
        ctx.fillStyle = '#fff';
        ctx.font = '32px Arial';
        ctx.textAlign = 'center';
        ctx.fillText('Waiting (Arch B)...', CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2);
        return;
    }

    // 渲染玩家
    gameState.players.forEach((p, idx) => {
        if (!p.alive) return;

        const colors = ['#ff4d4f', '#fa8c16', '#fadb14', '#52c41a'];
        const color = colors[idx % colors.length];

        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.moveTo(p.x, p.y - 18);
        ctx.lineTo(p.x - 14, p.y + 10);
        ctx.lineTo(p.x + 14, p.y + 10);
        ctx.closePath();
        ctx.fill();

        // 小飞机 label
        ctx.fillStyle = '#fff';
        ctx.font = '12px Arial';
        ctx.textAlign = 'center';
        ctx.fillText('✈ ' + p.username, p.x, p.y - 26);

        // 血条
        const barWidth = 32;
        const barHeight = 4;
        ctx.fillStyle = '#f00';
        ctx.fillRect(p.x - barWidth / 2, p.y - 22, barWidth, barHeight);
        ctx.fillStyle = '#0f0';
        const hpRatio = Math.max(0, Math.min(1, p.hp / 100));
        ctx.fillRect(p.x - barWidth / 2, p.y - 22, barWidth * hpRatio, barHeight);
    });

    // 子弹
    ctx.fillStyle = '#ff0';
    gameState.bullets.forEach(b => {
        ctx.beginPath();
        ctx.arc(b.x, b.y, 4, 0, Math.PI * 2);
        ctx.fill();
    });

    // 石头
    gameState.asteroids.forEach(a => {
        let color = a.isBig ? '#a67c52' : '#c48a4b';
        if (a.hp <= 1) color = '#8a6239';
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(a.x, a.y, a.radius || 16, 0, Math.PI * 2);
        ctx.fill();
        ctx.strokeStyle = '#6a4a2a';
        ctx.lineWidth = 2;
        ctx.stroke();
    });

    updateUIB();
}

function updateUIB() {
    // 自己的数据
    const me = gameState.players.find(p => p.username === username);
    if (me) {
        document.getElementById('hpText').textContent = me.hp;
        document.getElementById('scoreText').textContent = me.score;
    }

    // 时间
    const seconds = Math.floor((gameState.elapsedMs || 0) / 1000);
    const minutes = Math.floor(seconds / 60);
    const secs = seconds % 60;
    document.getElementById('timeText').textContent =
        `${minutes}:${secs.toString().padStart(2, '0')}`;

    // 记分板
    const scoreList = document.getElementById('scoreList');
    scoreList.innerHTML = '';
    const sorted = [...gameState.players].sort((a, b) => b.score - a.score);
    sorted.forEach(p => {
        const li = document.createElement('li');
        li.textContent = `${p.username}: ${p.score} (HP: ${p.hp})`;
        if (p.username === username) {
            li.style.fontWeight = 'bold';
        }
        if (!p.alive) {
            li.style.color = '#888';
            li.style.textDecoration = 'line-through';
        }
        scoreList.appendChild(li);
    });
}
