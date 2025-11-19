let GAME_MODE = 'ARCH_A';  // 默认 A
let ROOM_ID = null;

const ASTEROID_INTERVAL_MS = 800;   // 掉落间隔
const BULLET_SPEED = 400;          // 子弹速度 (px/s)
const ASTEROID_MIN_SPEED = 80;
const ASTEROID_MAX_SPEED = 160;
const PLAYER_SPEED = 220;
const MAX_HP = 3;

// 从 URL 取 roomId / winMode，例：game.html?roomId=1&win=SCORE_50
function getQueryParam(name) {
    const sp = new URLSearchParams(window.location.search);
    return sp.get(name);
}

let canvas, ctx;
let player;
let bullets = [];
let asteroids = [];
let keys = {};
let lastFrameTime = 0;
let spawnTimer = 0;
let elapsedMillis = 0;
let gameOver = false;
let winMode = 'SCORE_50';
let roomId = 0;
let currentUser = null;
let heartbeatTimer = null;
let scoreboardTimer = null;

document.addEventListener('DOMContentLoaded', async () => {
    const params = new URLSearchParams(window.location.search);
    ROOM_ID = parseInt(params.get('roomId'), 10);

    if (!ROOM_ID) {
        alert('Missing roomId');
        window.location.href = '/lobby.html';
        return;
    }

    // 先查房间配置，拿到 mode
    try {
        const resp = await authFetch(`/api/lobby/rooms/${ROOM_ID}/config`);
        if (resp.ok) {
            const cfg = await resp.json();
            GAME_MODE = cfg.mode || 'ARCH_A';
            console.log('Game mode =', GAME_MODE);
        }
    } catch (e) {
        console.error('failed to load room config', e);
    }
    // 1. 校验登录
    try {
        currentUser = await validateToken();
        if (!currentUser) {
            window.location.href = '/login.html';
            return;
        }
    } catch (e) {
        window.location.href = '/login.html';
        return;
    }

    // 2. 解析 roomId / winMode
    const roomStr = getQueryParam('roomId');
    if (!roomStr) {
        alert('Missing roomId in URL, e.g. /game.html?roomId=1');
        window.location.href = '/lobby.html';
        return;
    }
    roomId = parseInt(roomStr, 10) || 0;
    winMode = getQueryParam('win') || 'SCORE_50';

    // 3. 填充顶部信息
    document.getElementById('lblRoom').textContent = `Room #${roomId}`;
    document.getElementById('lblUser').textContent = currentUser.username;

    document.getElementById('btnLeave').addEventListener('click', leaveGameAndBack);
    document.getElementById('btnBackLobby').addEventListener('click', leaveGameAndBack);

    canvas = document.getElementById('gameCanvas');
    ctx = canvas.getContext('2d');

    initPlayer();
    initKeyboard();
    updateHud();

    // 心跳：2 秒上报一次分数
    heartbeatTimer = setInterval(sendHeartbeat, 2000);
    // 记分板：2 秒刷新一次
    scoreboardTimer = setInterval(fetchScoreboard, 2000);

    requestAnimationFrame(gameLoop);
});

// 初始化玩家
function initPlayer() {
    player = {
        x: canvas.width / 2,
        y: canvas.height - 60,
        width: 28,
        height: 40,
        hp: MAX_HP,
        score: 0,
        lastShotTime: 0,
        shotInterval: 200 // ms
    };
}

// 键盘控制
function initKeyboard() {
    window.addEventListener('keydown', (e) => {
        keys[e.key.toLowerCase()] = true;
    });
    window.addEventListener('keyup', (e) => {
        keys[e.key.toLowerCase()] = false;
    });
}

function gameLoop(timestamp) {
    if (!lastFrameTime) lastFrameTime = timestamp;
    const dt = (timestamp - lastFrameTime) / 1000;
    lastFrameTime = timestamp;

    if (!gameOver) {
        updateGame(dt);
        renderGame();
        requestAnimationFrame(gameLoop);
    } else {
        // 确保最后一帧也画出来
        renderGame();
        sendHeartbeat(true);
        showGameOverPanel();
    }
}

function updateGame(dt) {
    elapsedMillis += dt * 1000;
    spawnTimer += dt * 1000;

    handlePlayerMove(dt);
    handleShooting();
    maybeSpawnAsteroid();
    updateAsteroids(dt);
    updateBullets(dt);
    handleCollisions();

    checkWinOrLose();
    updateHud();
}

function handlePlayerMove(dt) {
    let dx = 0, dy = 0;
    if (keys['a'] || keys['arrowleft']) dx -= 1;
    if (keys['d'] || keys['arrowright']) dx += 1;
    if (keys['w'] || keys['arrowup']) dy -= 1;
    if (keys['s'] || keys['arrowdown']) dy += 1;

    const len = Math.hypot(dx, dy);
    if (len > 0) {
        dx /= len;
        dy /= len;
    }

    player.x += dx * PLAYER_SPEED * dt;
    player.y += dy * PLAYER_SPEED * dt;

    // 边界
    const halfW = player.width / 2;
    const halfH = player.height / 2;
    player.x = Math.max(halfW, Math.min(canvas.width - halfW, player.x));
    player.y = Math.max(halfH + 40, Math.min(canvas.height - halfH, player.y));
}

function handleShooting() {
    if (!keys['j']) return;
    const now = performance.now();
    if (now - player.lastShotTime < player.shotInterval) return;
    player.lastShotTime = now;

    bullets.push({
        x: player.x,
        y: player.y - player.height / 2,
        vy: -BULLET_SPEED,
        radius: 4
    });
}

function maybeSpawnAsteroid() {
    if (spawnTimer < ASTEROID_INTERVAL_MS) return;
    spawnTimer = 0;

    const x = Math.random() * (canvas.width - 60) + 30;
    const isBig = Math.random() < 0.4;
    const speed = ASTEROID_MIN_SPEED +
        Math.random() * (ASTEROID_MAX_SPEED - ASTEROID_MIN_SPEED);

    asteroids.push({
        x,
        y: -30,
        radius: isBig ? 26 : 16,
        hp: isBig ? 2 : 1,
        speed,
        isBig
    });
}

function updateAsteroids(dt) {
    for (const a of asteroids) {
        a.y += a.speed * dt;
    }
    // 删除飞出屏幕的
    asteroids = asteroids.filter(a => a.y - a.radius <= canvas.height + 40);
}

function updateBullets(dt) {
    for (const b of bullets) {
        b.y += b.vy * dt;
    }
    bullets = bullets.filter(b => b.y + b.radius >= -20);
}

function handleCollisions() {
    // 子弹打石头
    for (const b of bullets) {
        for (const a of asteroids) {
            const dx = a.x - b.x;
            const dy = a.y - b.y;
            const dist = Math.hypot(dx, dy);
            if (dist < a.radius + b.radius) {
                b._hit = true;
                a.hp -= 1;
                if (a.hp <= 0) {
                    a._dead = true;
                    player.score += a.isBig ? 10 : 5;
                }
                break;
            }
        }
    }
    bullets = bullets.filter(b => !b._hit);
    asteroids = asteroids.filter(a => !a._dead);

    // 石头撞玩家
    for (const a of asteroids) {
        const dx = Math.abs(a.x - player.x);
        const dy = Math.abs(a.y - player.y);
        const halfW = player.width / 2;
        const halfH = player.height / 2;
        if (dx <= halfW + a.radius && dy <= halfH + a.radius) {
            a._dead = true;
            player.hp -= 1;
            if (player.hp <= 0) {
                player.hp = 0;
                gameOver = true;
                break;
            }
        }
    }
    asteroids = asteroids.filter(a => !a._dead);
}

function checkWinOrLose() {
    if (player.hp <= 0) {
        gameOver = true;
        return;
    }

    const sec = Math.floor(elapsedMillis / 1000);
    if (winMode === 'SCORE_50' && player.score >= 50) {
        gameOver = true;
    } else if (winMode === 'SCORE_100' && player.score >= 100) {
        gameOver = true;
    } else if (winMode === 'TIME_1M' && sec >= 60) {
        gameOver = true;
    } else if (winMode === 'TIME_5M' && sec >= 300) {
        gameOver = true;
    }
}

function renderGame() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // 背景星星简单点：直接不画，或者可以加一点点
    // 画玩家
    drawPlayer();
    // 画子弹
    for (const b of bullets) {
        ctx.beginPath();
        ctx.arc(b.x, b.y, b.radius, 0, Math.PI * 2);
        ctx.fillStyle = '#ffd86b';
        ctx.fill();
    }
    // 画石头
    for (const a of asteroids) {
        ctx.beginPath();
        ctx.arc(a.x, a.y, a.radius, 0, Math.PI * 2);
        ctx.fillStyle = '#c48a4b';
        ctx.fill();
    }
}

function drawPlayer() {
    const w = player.width;
    const h = player.height;
    const x = player.x;
    const y = player.y;

    ctx.save();
    ctx.translate(x, y);

    // 机身
    ctx.fillStyle = '#67d0ff';
    ctx.fillRect(-w / 4, -h / 2, w / 2, h);

    // 左右翼
    ctx.fillRect(-w / 2, 0, w / 2, 8);
    ctx.fillRect(0, 0, w / 2, 8);

    ctx.restore();
}

function updateHud() {
    document.getElementById('hpText').textContent = `${player.hp}/${MAX_HP}`;
    document.getElementById('scoreText').textContent = player.score.toString();
    document.getElementById('timeText').textContent =
        formatTime(Math.floor(elapsedMillis / 1000));
}

function formatTime(sec) {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
}

// ===== 服务器心跳 & 记分板 =====

// finishedOnly 参数用来在游戏结束时强制再发一次
async function sendHeartbeat(finishedOnly = false) {
    const payload = {
        roomId: ROOM_ID,
        hp: hp,
        score: score,
        elapsedMillis: elapsed,
        finished: finished,
        input: ''   // 预留：将来你想给架构 A 传键盘事件，可以在这里填
    };

    try {
        await authFetch('/api/game/heartbeat', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
    } catch (e) {
        console.error('heartbeat error', e);
    }
}

async function fetchScoreboard() {
    if (!roomId) return;
    try {
        const resp = await authFetch(`/api/game/room/${roomId}/scoreboard`);
        if (!resp.ok) return;
        const list = await resp.json();
        renderScoreboard(list);
    } catch (e) {
        console.error('scoreboard error', e);
    }
}

function renderScoreboard(list) {
    const ul = document.getElementById('scoreList');
    ul.innerHTML = '';

    // 按分数倒序
    list.sort((a, b) => b.score - a.score);

    for (const p of list) {
        const li = document.createElement('li');
        const sec = Math.floor((p.elapsedMillis || 0) / 1000);
        li.textContent = `${p.username}: ${p.score} pts, HP ${p.hp}, ${formatTime(sec)}`;
        if (currentUser && p.username === currentUser.username) {
            li.classList.add('me');
        }
        if (p.finished) {
            li.classList.add('finished');
        }
        ul.appendChild(li);
    }
}

function showGameOverPanel() {
    clearInterval(scoreboardTimer);
    scoreboardTimer = null;

    const overlay = document.getElementById('overlay');
    const title = document.getElementById('overTitle');
    const summary = document.getElementById('overSummary');

    const sec = Math.floor(elapsedMillis / 1000);

    if (player.hp <= 0) {
        title.textContent = 'You are destroyed';
    } else {
        title.textContent = 'Mission Complete';
    }
    summary.textContent = `Score: ${player.score}, Time: ${formatTime(sec)}, HP: ${player.hp}/${MAX_HP}`;

    overlay.classList.remove('hidden');
}


// 统一的“离开游戏并回大厅”函数
async function leaveGameAndBack() {
    try {
        if (ROOM_ID) {
            await authFetch(`/api/game/room/${ROOM_ID}/leave`, { method: 'POST' });
        }
    } catch (e) {
        console.error('leave game error', e);
    } finally {
        window.location.href = '/lobby.html';
    }
}
