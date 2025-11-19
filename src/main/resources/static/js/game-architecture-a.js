// game-architecture-a.js
// Architecture A: 客户端只发送输入，服务器权威控制游戏状态

// --- 本地特效状态 ---
let lastHp = null;           // 用来检测自己是否刚刚掉血
let hitFlashEndTime = 0;     // 自己飞机闪烁的结束时间戳
let explosionEffects = [];   // 刚刚爆掉石头的爆炸特效 [{x, y, endTime}]

const CANVAS_WIDTH = 480;
const CANVAS_HEIGHT = 640;
const FPS = 60;

// 固定玩家配色：player1 红, player2 橙, player3 黄, player4 绿
const PLAYER_COLORS = [
    '#ff4d4f', // red
    '#fa8c16', // orange
    '#fadb14', // yellow
    '#52c41a'  // green
];

let ws = null;
let roomId = null;
let username = null;
let token = null;
let winMode = null;

// 游戏状态（完全由服务器推送）
let gameState = {
    phase: 'WAITING',
    frame: 0,
    countdownMs: 0,
    elapsedMs: 0,
    players: [],
    bullets: [],
    asteroids: []
};

// 输入状态
let keys = {
    w: false,
    a: false,
    s: false,
    d: false,
    j: false,
    ' ': false
};

let canvas, ctx;
let lastSendTime = 0;
const INPUT_SEND_INTERVAL = 50; // 20Hz

// ============ 帮助函数：根据 username 得到固定颜色 ============
function getPlayerColor(u) {
    const players = gameState.players || [];
    const sorted = [...players].map(p => p.username).sort();
    const idx = sorted.indexOf(u);
    if (idx === -1) return '#00bfff'; // fallback 蓝色
    return PLAYER_COLORS[Math.min(idx, PLAYER_COLORS.length - 1)];
}

// ================== 启动入口 ==================
(function initGameArchA() {
    console.log('[INIT] game-architecture-a.js loaded');

    const params = new URLSearchParams(window.location.search);
    roomId = parseInt(params.get('roomId'));
    winMode = params.get('win') || 'SCORE_50';
    const arch = params.get('arch') || 'A';

    console.log('[INIT]', 'roomId:', roomId, 'winMode:', winMode, 'arch:', arch);

    username = localStorage.getItem('game_demo_username');
    token = localStorage.getItem('game_demo_token');

    console.log('[INIT]', 'username:', username, 'token exists:', !!token);

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

    const lblRoom = document.getElementById('lblRoom');
    const lblUser = document.getElementById('lblUser');
    const lblArch = document.getElementById('lblArchitecture');

    if (!lblRoom || !lblUser || !lblArch) {
        console.error('[INIT] game.html 元素没找到，检查 id 是否变化');
    } else {
        lblRoom.textContent = `Room ${roomId}`;
        lblUser.textContent = username;
        lblArch.textContent = '[Architecture A: Server-Authoritative]';
    }

    canvas = document.getElementById('gameCanvas');
    if (!canvas) {
        console.error('[INIT] canvas #gameCanvas not found');
        return;
    }
    ctx = canvas.getContext('2d');

    connectWebSocket();
    setupInput();

    setInterval(renderLoop, 1000 / FPS);
    setInterval(sendInput, INPUT_SEND_INTERVAL);

    const btnLeave = document.getElementById('btnLeave');
    const btnBackLobby = document.getElementById('btnBackLobby');
    btnLeave && btnLeave.addEventListener('click', leaveGame);
    btnBackLobby && btnBackLobby.addEventListener('click', leaveGame);
})();

// ================== WebSocket 相关 ==================
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/game`;
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log('WebSocket connected (Architecture A)');

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

function handleServerMessage(msg) {
    switch (msg.type) {
        case 'CONNECTED':
            console.log('Server acknowledged connection');
            break;

        case 'JOINED':
            console.log('Successfully joined game:', msg);
            break;

        case 'GAME_STATE': {
            console.log(
                '[GAME_STATE]',
                'phase:', msg.phase,
                'players:', msg.players?.length,
                'bullets:', msg.bullets?.length,
                'asteroids:', msg.asteroids?.length
            );

            const oldAsteroidsById = new Map(
                (gameState.asteroids || []).map(a => [a.id, a])
            );

            gameState.phase = msg.phase || gameState.phase;
            gameState.frame = msg.frame ?? gameState.frame;
            gameState.countdownMs = msg.countdownMs || 0;
            gameState.elapsedMs = msg.elapsedMs || 0;
            gameState.players = msg.players || [];
            gameState.bullets = msg.bullets || [];
            gameState.asteroids = msg.asteroids || [];

            const newIds = new Set(gameState.asteroids.map(a => a.id));
            oldAsteroidsById.forEach((asteroid, id) => {
                if (!newIds.has(id)) {
                    explosionEffects.push({
                        x: asteroid.x,
                        y: asteroid.y,
                        endTime: performance.now() + 300
                    });
                }
            });

            updateUI();
            break;
        }

        case 'ERROR':
            console.error('Server error:', msg.message);
            alert('错误: ' + msg.message);
            // 如果是在 JOIN 阶段就失败，也回 lobby 并禁止再自动跳
            if (msg.message && msg.message.includes('Not in room')) {
                window.location.href = '/lobby.html?fromGameError=1';
            }
            break;
        case 'NOT_IN_ROOM':
            console.error('[NOT_IN_ROOM]', msg.message);
            alert(msg.message + '\n\n请先在大厅点击 "Start (Arch A)" 按钮');
            // 加一个 fromGameError=1，告诉 lobby：这次是失败返回
            window.location.href = '/lobby.html?fromGameError=1';
            break;

        default:
            console.log('[WS] unknown message:', msg);
    }
}

// ================== 输入 & 发送 ==================
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

// ================== 渲染 ==================
function renderLoop() {
    if (!ctx) return;

    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

    switch (gameState.phase) {
        case 'WAITING':
            ctx.fillStyle = '#fff';
            ctx.font = '32px Arial';
            ctx.textAlign = 'center';
            ctx.fillText('Waiting for players...', CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2);
            break;

        case 'COUNTDOWN': {
            const seconds = Math.ceil(gameState.countdownMs / 1000);
            ctx.fillStyle = '#fff';
            ctx.font = '72px Arial';
            ctx.textAlign = 'center';
            ctx.fillText(seconds, CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2);
            ctx.font = '24px Arial';
            ctx.fillText('Get Ready!', CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2 + 50);
            break;
        }

        case 'IN_PROGRESS':
            renderGame();
            break;

        case 'FINISHED':
            renderGame();

            ctx.fillStyle = 'rgba(0, 0, 0, 0.7)';
            ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

            ctx.fillStyle = '#fff';
            ctx.font = '48px Arial';
            ctx.textAlign = 'center';
            ctx.fillText('Game Over!', CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2);

            const winner = gameState.players.reduce(
                (max, p) => (p.score > (max?.score || 0) ? p : max),
                null
            );
            if (winner) {
                ctx.font = '24px Arial';
                ctx.fillText(`Winner: ${winner.username}`, CANVAS_WIDTH / 2, CANVAS_HEIGHT / 2 + 50);
            }

            const overlay = document.getElementById('overlay');
            if (overlay) {
                overlay.classList.remove('hidden');
                const overTitle = document.getElementById('overTitle');
                const overSummary = document.getElementById('overSummary');
                if (overTitle) overTitle.textContent = 'Game Over';
                if (overSummary && winner) {
                    overSummary.textContent = `Winner: ${winner.username} (${winner.score} pts)`;
                }
            }
            break;
    }
}

function renderGame() {
    const now = performance.now();

    // ---- 玩家 ----
    gameState.players.forEach(player => {
        if (!player.alive) return;

        const isMe = player.username === username;

        // 自己被撞后闪烁
        if (isMe && now < hitFlashEndTime && (gameState.frame % 2 === 0)) {
            return;
        }

        const color = getPlayerColor(player.username);

        // 机体
        ctx.fillStyle = color;
        ctx.fillRect(player.x - 16, player.y - 16, 32, 32);

        // 名字 + 小飞机图标
        ctx.fillStyle = '#fff';
        ctx.font = '12px Arial';
        ctx.textAlign = 'center';
        ctx.fillText(`✈ ${player.username}`, player.x, player.y - 25);

        // HP 条
        const barWidth = 32;
        const barHeight = 4;
        ctx.fillStyle = '#f00';
        ctx.fillRect(player.x - barWidth / 2, player.y - 20, barWidth, barHeight);
        ctx.fillStyle = '#0f0';
        ctx.fillRect(
            player.x - barWidth / 2,
            player.y - 20,
            barWidth * (player.hp / 100),
            barHeight
        );
    });

    // ---- 爆炸特效 ----
    explosionEffects = explosionEffects.filter(e => e.endTime > now);
    explosionEffects.forEach(e => {
        const t = 1 - (e.endTime - now) / 300;
        const r = 10 + 20 * t;
        ctx.save();
        ctx.globalAlpha = 1 - t;
        ctx.strokeStyle = '#ff0';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(e.x, e.y, r, 0, Math.PI * 2);
        ctx.stroke();
        ctx.restore();
    });

    // ---- 子弹 ----
    ctx.fillStyle = '#ff0';
    gameState.bullets.forEach(bullet => {
        ctx.beginPath();
        ctx.arc(bullet.x, bullet.y, 4, 0, Math.PI * 2);
        ctx.fill();
    });

    // ---- 石头 ----
    gameState.asteroids.forEach(asteroid => {
        let color = asteroid.isBig ? '#a67c52' : '#c48a4b';
        if (asteroid.hp < (asteroid.isBig ? 2 : 1)) {
            color = '#8a6239';
        }

        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(asteroid.x, asteroid.y, asteroid.radius, 0, Math.PI * 2);
        ctx.fill();

        ctx.strokeStyle = '#6a4a2a';
        ctx.lineWidth = 2;
        ctx.stroke();
    });
}

// ================== UI / 记分板 ==================
function updateUI() {
    const my = gameState.players.find(p => p.username === username);
    if (my) {
        if (lastHp != null && my.hp < lastHp) {
            hitFlashEndTime = performance.now() + 1000;
        }
        lastHp = my.hp;

        const hpText = document.getElementById('hpText');
        const scoreText = document.getElementById('scoreText');
        if (hpText) hpText.textContent = my.hp;
        if (scoreText) scoreText.textContent = my.score;
    }

    if (gameState.elapsedMs) {
        const seconds = Math.floor(gameState.elapsedMs / 1000);
        const minutes = Math.floor(seconds / 60);
        const secs = seconds % 60;
        const timeText = document.getElementById('timeText');
        if (timeText) {
            timeText.textContent = `${minutes}:${secs.toString().padStart(2, '0')}`;
        }
    }

    const scoreList = document.getElementById('scoreList');
    if (!scoreList) return;
    scoreList.innerHTML = '';

    const sorted = [...gameState.players].sort((a, b) => b.score - a.score);
    sorted.forEach(p => {
        const li = document.createElement('li');
        li.textContent = `✈ ${p.username}: ${p.score} (HP: ${p.hp})`;

        const color = getPlayerColor(p.username);
        li.style.color = color;
        if (!p.alive) {
            li.style.color = '#888';
            li.style.textDecoration = 'line-through';
        }
        scoreList.appendChild(li);
    });
}

// ================== 退出 ==================
function leaveGame() {
    if (ws) {
        ws.send(JSON.stringify({ type: 'LEAVE_GAME' }));
        ws.close();
    }
    // 用 fromGameExit=1 标记：我主动退出了，不要再把我自动送回游戏
    window.location.href = '/lobby.html?fromGameExit=1';
}
