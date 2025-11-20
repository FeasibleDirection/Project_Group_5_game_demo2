// game-architecture-b.js
// 🔥 Architecture B: P2P Gossip（完全去中心化）
// 每个用户平等，生成自己的石头，本地计算碰撞，广播状态

const CANVAS_WIDTH = 480;
const CANVAS_HEIGHT = 640;
const FPS = 60;

// 🎨 玩家颜色：红橙黄绿
const PLAYER_COLORS = [
    '#ff4d4f', // player1: 红
    '#fa8c16', // player2: 橙
    '#fadb14', // player3: 黄
    '#52c41a'  // player4: 绿
];

// WebSocket
let ws = null;
let roomId = null;
let username = null;
let token = null;
let winMode = null;

// 房间所有玩家列表（用于分配颜色）
let allPlayers = []; // ['xushikuan', 'zhaoyuan', ...]

// 游戏状态（合并所有玩家的数据）
let gameState = {
    phase: 'IN_PROGRESS',
    players: {}, // username -> {x, y, hp, score, alive}
    asteroids: {}, // asteroidId -> {owner, x, y, velocityY, radius, hp, isBig}
    bullets: {} // bulletId -> {owner, x, y, velocityY}
};

// 本地玩家状态
let myPlayer = {
    x: CANVAS_WIDTH / 2,
    y: CANVAS_HEIGHT - 80,
    hp: 3,
    score: 0,
    alive: true,
    lastFireTime: 0
};

// 本地生成的游戏对象
let myAsteroids = {}; // asteroidId -> AsteroidEntity
let myBullets = {}; // bulletId -> BulletEntity

// 输入
let keys = {
    w: false,
    a: false,
    s: false,
    d: false,
    j: false,
    ' ': false
};

let canvas, ctx;
let asteroidIdCounter = 0;
let bulletIdCounter = 0;
let lastAsteroidSpawnTime = 0;
let lastPositionBroadcast = 0;
let lastStateBroadcast = 0;

// 🔥 游戏结束判定
let gameStartTime = 0; // 游戏开始时间戳
let hasVotedGameEnd = false; // 是否已投票
let gameEndReason = null; // 结束原因

// 常量
const PLAYER_SPEED = 200; // pixels/second
const BULLET_SPEED = 400;
const ASTEROID_MIN_SPEED = 80;
const ASTEROID_MAX_SPEED = 160;
const ASTEROID_SPAWN_INTERVAL = 800; // ms
const PLAYER_RADIUS = 16;
const BULLET_RADIUS = 4;
const MIN_FIRE_INTERVAL = 200; // ms

const POSITION_BROADCAST_INTERVAL = 50; // 20Hz
const STATE_BROADCAST_INTERVAL = 100; // 10Hz (石头/子弹位置)

// ============ 启动入口 ============
(function initGameArchB() {
    console.log('[ArchB-Gossip] game-architecture-b.js loaded');

    const params = new URLSearchParams(window.location.search);
    roomId = parseInt(params.get('roomId'));
    winMode = params.get('win') || 'SCORE_50';
    const arch = params.get('arch') || 'B';

    console.log('[ArchB-Gossip] roomId:', roomId, 'winMode:', winMode, 'arch:', arch);

    username = localStorage.getItem('game_demo_username');
    token = localStorage.getItem('game_demo_token');

    console.log('[ArchB-Gossip] username:', username, 'token exists:', !!token);

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

    // 初始化自己的玩家状态
    gameState.players[username] = { ...myPlayer };

    const lblRoom = document.getElementById('lblRoom');
    const lblUser = document.getElementById('lblUser');
    const lblArch = document.getElementById('lblArchitecture');

    if (lblRoom) lblRoom.textContent = `Room ${roomId}`;
    if (lblUser) lblUser.textContent = username;
    if (lblArch) lblArch.textContent = '[Architecture B: P2P Gossip]';

    canvas = document.getElementById('gameCanvas');
    if (!canvas) {
        console.error('[ArchB-Gossip] canvas not found');
        return;
    }
    ctx = canvas.getContext('2d');

    setupInput();
    setupButtons();
    connectWebSocket();

    // 启动游戏循环
    setInterval(gameLoop, 1000 / FPS);
})();

// ============ WebSocket ============
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/game-b`;
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log('[ArchB-Gossip] WebSocket connected');

        ws.send(JSON.stringify({
            type: 'JOIN_GAME_B',
            roomId,
            username,
            token
        }));
    };

    ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        handleServerMessage(msg);
    };

    ws.onerror = (error) => {
        console.error('[ArchB-Gossip] WebSocket error', error);
        alert('连接失败');
    };

    ws.onclose = () => {
        console.log('[ArchB-Gossip] WebSocket closed');
    };
}

function handleServerMessage(msg) {
    switch (msg.type) {
        case 'CONNECTED':
            console.log('[ArchB-Gossip] Server acknowledged connection');
            break;

        case 'JOINED_B':
            console.log('[ArchB-Gossip] Successfully joined room:', msg);
            allPlayers = msg.players || [username];
            console.log('[ArchB-Gossip] All players:', allPlayers);
            // 🔥 记录游戏开始时间
            gameStartTime = Date.now();
            console.log('[ArchB-Gossip] Game started at:', gameStartTime);
            break;

        case 'PLAYER_JOINED':
            console.log('[ArchB-Gossip] New player joined:', msg.username);
            allPlayers = msg.players || allPlayers;
            // 初始化新玩家状态
            if (msg.username && !gameState.players[msg.username]) {
                gameState.players[msg.username] = {
                    x: CANVAS_WIDTH / 2,
                    y: CANVAS_HEIGHT - 80,
                    hp: 100,
                    score: 0,
                    alive: true
                };
            }
            break;

        case 'PLAYER_LEFT':
            console.log('[ArchB-Gossip] Player left:', msg.username);
            allPlayers = msg.players || allPlayers;
            if (msg.username && gameState.players[msg.username]) {
                gameState.players[msg.username].alive = false;
            }
            break;

        case 'PLAYER_POSITION':
            updatePlayerPosition(msg);
            break;

        case 'ASTEROID_SPAWN':
            handleAsteroidSpawn(msg);
            break;

        case 'ASTEROID_POSITION':
            handleAsteroidPosition(msg);
            break;

        case 'BULLET_FIRED':
            handleBulletFired(msg);
            break;

        case 'BULLET_POSITION':
            handleBulletPosition(msg);
            break;

        case 'BULLET_HIT_ASTEROID':
            handleBulletHitAsteroid(msg);
            break;

        case 'PLAYER_HIT':
            handlePlayerHit(msg);
            break;

        case 'PLAYER_DEAD':
            handlePlayerDead(msg);
            break;

        case 'SCORE_UPDATE':
            handleScoreUpdate(msg);
            break;

        case 'ASTEROID_DESTROYED':
            handleAsteroidDestroyed(msg);
            break;

        case 'BULLET_DESTROYED':
            handleBulletDestroyed(msg);
            break;

        case 'GAME_ENDED':
            handleGameEnded(msg);
            break;

        case 'NOT_IN_ROOM':
            console.error('[ArchB-Gossip] NOT_IN_ROOM:', msg.message);
            alert(msg.message + '\n\n请先在大厅点击 "Start (Arch B)" 按钮');
            window.location.href = '/lobby.html?fromGameError=1';
            break;

        case 'ERROR':
            console.error('[ArchB-Gossip] ERROR:', msg.message);
            alert('Error: ' + msg.message);
            break;

        default:
            console.log('[ArchB-Gossip] Unknown message:', msg);
    }
}

// ============ 接收其他玩家消息 ============
function updatePlayerPosition(msg) {
    const { username: uname, x, y } = msg;
    if (uname === username) return; // 忽略自己的消息

    if (!gameState.players[uname]) {
        gameState.players[uname] = { x, y, hp: 100, score: 0, alive: true };
    } else {
        gameState.players[uname].x = x;
        gameState.players[uname].y = y;
    }
}

function handleAsteroidSpawn(msg) {
    const { asteroidId, owner, x, y, velocityY, radius, hp, isBig } = msg;
    gameState.asteroids[asteroidId] = { owner, x, y, velocityY, radius, hp, isBig };
}

function handleAsteroidPosition(msg) {
    const { asteroidId, x, y } = msg;
    if (gameState.asteroids[asteroidId]) {
        gameState.asteroids[asteroidId].x = x;
        gameState.asteroids[asteroidId].y = y;
    }
}

function handleBulletFired(msg) {
    const { bulletId, owner, x, y, velocityY } = msg;
    gameState.bullets[bulletId] = { owner, x, y, velocityY };
}

function handleBulletPosition(msg) {
    const { bulletId, x, y } = msg;
    if (gameState.bullets[bulletId]) {
        gameState.bullets[bulletId].x = x;
        gameState.bullets[bulletId].y = y;
    }
}

function handleBulletHitAsteroid(msg) {
    const { asteroidId, bulletId } = msg;
    // 删除石头和子弹
    delete gameState.asteroids[asteroidId];
    delete gameState.bullets[bulletId];
    // 如果是自己的石头，也删除本地副本
    if (myAsteroids[asteroidId]) {
        delete myAsteroids[asteroidId];
    }
    if (myBullets[bulletId]) {
        delete myBullets[bulletId];
    }
}

function handlePlayerHit(msg) {
    const { username: uname, hp } = msg;
    if (gameState.players[uname]) {
        gameState.players[uname].hp = hp;
    }
}

function handlePlayerDead(msg) {
    const { username: uname } = msg;
    if (gameState.players[uname]) {
        gameState.players[uname].alive = false;
        gameState.players[uname].hp = 0;
    }
}

function handleScoreUpdate(msg) {
    const { username: uname, score } = msg;
    if (gameState.players[uname]) {
        gameState.players[uname].score = score;
    }
}

function handleAsteroidDestroyed(msg) {
    const { asteroidId } = msg;
    // 删除石头
    delete gameState.asteroids[asteroidId];
    if (myAsteroids[asteroidId]) {
        delete myAsteroids[asteroidId];
    }
}

function handleBulletDestroyed(msg) {
    const { bulletId } = msg;
    // 删除子弹
    delete gameState.bullets[bulletId];
    if (myBullets[bulletId]) {
        delete myBullets[bulletId];
    }
}

function handleGameEnded(msg) {
    console.log('[ArchB-Gossip] Game ended:', msg);
    gameState.phase = 'FINISHED';
    
    // 跳转到结算页面
    setTimeout(() => {
        alert('游戏结束！\n原因：' + (msg.reason || 'unknown'));
        // 🔥 添加fromGameExit=1参数，防止自动重新进入游戏
        window.location.href = '/lobby.html?fromGameExit=1';
    }, 1000);
}

// ============ 游戏主循环 ============
function gameLoop() {
    const deltaSeconds = 1 / FPS;
    const now = performance.now();

    // 1. 更新本地玩家位置
    updateMyPlayer(deltaSeconds);

    // 2. 生成本地石头
    spawnMyAsteroids(now, deltaSeconds);

    // 3. 更新本地石头位置
    updateMyAsteroids(deltaSeconds);

    // 4. 更新本地子弹位置
    updateMyBullets(deltaSeconds);

    // 5. 🔥 更新其他玩家的实体（本地预测）
    updateOthersEntities(deltaSeconds);

    // 6. 🔥 清理超出边界的实体（防止残留）
    cleanupOutOfBoundsEntities();

    // 7. 本地碰撞检测
    detectMyCollisions();

    // 8. 🔥 检测游戏结束条件
    checkGameEndConditions();

    // 9. 广播状态
    broadcastStates(now);

    // 10. 渲染
    render();

    // 11. 更新UI
    updateUI();
}

// ============ 本地玩家更新 ============
function updateMyPlayer(deltaSeconds) {
    if (!myPlayer.alive) return;

    let vx = 0, vy = 0;

    if (keys.w) vy -= 1;
    if (keys.s) vy += 1;
    if (keys.a) vx -= 1;
    if (keys.d) vx += 1;

    // 归一化
    const mag = Math.hypot(vx, vy);
    if (mag > 0) {
        vx = (vx / mag) * PLAYER_SPEED;
        vy = (vy / mag) * PLAYER_SPEED;
    }

    myPlayer.x += vx * deltaSeconds;
    myPlayer.y += vy * deltaSeconds;

    // 边界限制
    myPlayer.x = Math.max(PLAYER_RADIUS, Math.min(CANVAS_WIDTH - PLAYER_RADIUS, myPlayer.x));
    myPlayer.y = Math.max(PLAYER_RADIUS, Math.min(CANVAS_HEIGHT - PLAYER_RADIUS, myPlayer.y));

    // 更新全局状态
    gameState.players[username].x = myPlayer.x;
    gameState.players[username].y = myPlayer.y;

    // 射击
    const now = performance.now();
    if ((keys.j || keys[' ']) && now - myPlayer.lastFireTime >= MIN_FIRE_INTERVAL) {
        fireBullet();
        myPlayer.lastFireTime = now;
    }
}

function fireBullet() {
    const bulletId = `${username}_bullet_${bulletIdCounter++}`;
    
    const bullet = {
        owner: username,
        x: myPlayer.x,
        y: myPlayer.y - 20,
        velocityY: -BULLET_SPEED
    };

    myBullets[bulletId] = bullet;
    gameState.bullets[bulletId] = bullet;

    // 广播子弹发射
    sendMessage({
        type: 'BULLET_FIRED',
        bulletId,
        owner: username,
        x: bullet.x,
        y: bullet.y,
        velocityY: bullet.velocityY
    });
}

// ============ 本地石头管理 ============
function spawnMyAsteroids(now, deltaSeconds) {
    if (now - lastAsteroidSpawnTime < ASTEROID_SPAWN_INTERVAL) return;
    if (Object.keys(myAsteroids).length >= 5) return; // 每个用户最多5个石头

    lastAsteroidSpawnTime = now;

    const asteroidId = `${username}_asteroid_${asteroidIdCounter++}`;
    const x = 30 + Math.random() * (CANVAS_WIDTH - 60);
    const isBig = Math.random() < 0.4;
    const radius = isBig ? 26 : 16;
    const hp = isBig ? 2 : 1;
    const velocityY = ASTEROID_MIN_SPEED + Math.random() * (ASTEROID_MAX_SPEED - ASTEROID_MIN_SPEED);

    const asteroid = {
        owner: username,
        x,
        y: -radius,
        velocityY,
        radius,
        hp,
        isBig
    };

    myAsteroids[asteroidId] = asteroid;
    gameState.asteroids[asteroidId] = asteroid;

    // 广播石头生成
    sendMessage({
        type: 'ASTEROID_SPAWN',
        asteroidId,
        owner: username,
        x,
        y: asteroid.y,
        velocityY,
        radius,
        hp,
        isBig
    });
}

function updateMyAsteroids(deltaSeconds) {
    Object.keys(myAsteroids).forEach(asteroidId => {
        const asteroid = myAsteroids[asteroidId];
        asteroid.y += asteroid.velocityY * deltaSeconds;

        // 更新全局状态
        if (gameState.asteroids[asteroidId]) {
            gameState.asteroids[asteroidId].y = asteroid.y;
        }

        // 移除飞出屏幕的石头
        if (asteroid.y - asteroid.radius > CANVAS_HEIGHT + 40) {
            delete myAsteroids[asteroidId];
            delete gameState.asteroids[asteroidId];
            
            // 🔥 广播石头被销毁（超出边界）
            sendMessage({
                type: 'ASTEROID_DESTROYED',
                asteroidId,
                reason: 'out_of_bounds'
            });
        }
    });
}

function updateMyBullets(deltaSeconds) {
    Object.keys(myBullets).forEach(bulletId => {
        const bullet = myBullets[bulletId];
        bullet.y += bullet.velocityY * deltaSeconds;

        // 更新全局状态
        if (gameState.bullets[bulletId]) {
            gameState.bullets[bulletId].y = bullet.y;
        }

        // 移除飞出屏幕的子弹
        if (bullet.y < -10 || bullet.y > CANVAS_HEIGHT + 10) {
            delete myBullets[bulletId];
            delete gameState.bullets[bulletId];
            
            // 🔥 广播子弹被销毁（超出边界）
            sendMessage({
                type: 'BULLET_DESTROYED',
                bulletId,
                reason: 'out_of_bounds'
            });
        }
    });
}

// ============ 🔥 更新其他玩家的实体（本地预测） ============
function updateOthersEntities(deltaSeconds) {
    // 更新其他玩家的石头（如果没有收到owner的位置更新，本地预测）
    Object.keys(gameState.asteroids).forEach(asteroidId => {
        // 跳过自己的石头（已经在updateMyAsteroids中处理）
        if (myAsteroids[asteroidId]) return;

        const asteroid = gameState.asteroids[asteroidId];
        if (!asteroid || !asteroid.velocityY) return;

        // 本地预测：匀速向下运动
        asteroid.y += asteroid.velocityY * deltaSeconds;
    });

    // 更新其他玩家的子弹
    Object.keys(gameState.bullets).forEach(bulletId => {
        // 跳过自己的子弹（已经在updateMyBullets中处理）
        if (myBullets[bulletId]) return;

        const bullet = gameState.bullets[bulletId];
        if (!bullet || !bullet.velocityY) return;

        // 本地预测：匀速向上运动
        bullet.y += bullet.velocityY * deltaSeconds;
    });
}

// ============ 🔥 清理超出边界的实体（防止残留） ============
function cleanupOutOfBoundsEntities() {
    // 清理超出边界的石头
    Object.keys(gameState.asteroids).forEach(asteroidId => {
        const asteroid = gameState.asteroids[asteroidId];
        if (!asteroid) return;

        // 如果石头超出下边界，本地删除（防止残留）
        if (asteroid.y - asteroid.radius > CANVAS_HEIGHT + 50) {
            console.log('[ArchB-Gossip] 本地清理超出边界的石头:', asteroidId);
            delete gameState.asteroids[asteroidId];
            // 注意：不删除myAsteroids，因为那是owner的责任
        }
    });

    // 清理超出边界的子弹
    Object.keys(gameState.bullets).forEach(bulletId => {
        const bullet = gameState.bullets[bulletId];
        if (!bullet) return;

        // 如果子弹超出上下边界，本地删除（防止残留）
        if (bullet.y < -50 || bullet.y > CANVAS_HEIGHT + 50) {
            console.log('[ArchB-Gossip] 本地清理超出边界的子弹:', bulletId);
            delete gameState.bullets[bulletId];
            // 注意：不删除myBullets，因为那是owner的责任
        }
    });
}

// ============ 本地碰撞检测 ============
function detectMyCollisions() {
    // 1. 自己的子弹 vs 所有石头
    Object.keys(myBullets).forEach(bulletId => {
        const bullet = myBullets[bulletId];
        
        Object.keys(gameState.asteroids).forEach(asteroidId => {
            const asteroid = gameState.asteroids[asteroidId];
            if (!asteroid) return;

            const dx = bullet.x - asteroid.x;
            const dy = bullet.y - asteroid.y;
            const dist = Math.hypot(dx, dy);

            if (dist < BULLET_RADIUS + asteroid.radius) {
                // 命中！
                asteroid.hp -= 1;

                if (asteroid.hp <= 0) {
                    // 石头被打爆
                    delete gameState.asteroids[asteroidId];
                    if (myAsteroids[asteroidId]) {
                        delete myAsteroids[asteroidId];
                    }

                    // 增加分数
                    const scoreGain = asteroid.isBig ? 10 : 5;
                    myPlayer.score += scoreGain;
                    gameState.players[username].score = myPlayer.score;

                    // 广播事件
                    sendMessage({
                        type: 'BULLET_HIT_ASTEROID',
                        bulletId,
                        asteroidId,
                        shooter: username,
                        asteroidOwner: asteroid.owner,
                        destroyed: true
                    });

                    sendMessage({
                        type: 'SCORE_UPDATE',
                        username,
                        score: myPlayer.score,
                        reason: 'destroyed_asteroid'
                    });
                }

                // 删除子弹
                delete myBullets[bulletId];
                delete gameState.bullets[bulletId];
            }
        });
    });

    // 2. 所有石头 vs 自己的飞机
    if (myPlayer.alive) {
        Object.keys(gameState.asteroids).forEach(asteroidId => {
            const asteroid = gameState.asteroids[asteroidId];
            if (!asteroid) return;

            const dx = myPlayer.x - asteroid.x;
            const dy = myPlayer.y - asteroid.y;
            const dist = Math.hypot(dx, dy);

            if (dist < PLAYER_RADIUS + asteroid.radius) {
                // 被撞！
                myPlayer.hp -= 1;
                gameState.players[username].hp = myPlayer.hp;

                // 广播事件
                sendMessage({
                    type: 'PLAYER_HIT',
                    username,
                    asteroidId,
                    hp: myPlayer.hp
                });

                // 删除石头
                delete gameState.asteroids[asteroidId];
                if (myAsteroids[asteroidId]) {
                    delete myAsteroids[asteroidId];
                }

                // 🔥 广播石头被销毁（被玩家撞击）
                sendMessage({
                    type: 'ASTEROID_DESTROYED',
                    asteroidId,
                    reason: 'player_hit'
                });

                // 检查是否死亡
                if (myPlayer.hp <= 0) {
                    myPlayer.alive = false;
                    myPlayer.hp = 0;
                    gameState.players[username].alive = false;
                    gameState.players[username].hp = 0;

                    sendMessage({
                        type: 'PLAYER_DEAD',
                        username
                    });
                }
            }
        });
    }
}

// ============ 🔥 游戏结束检测 ============
function checkGameEndConditions() {
    // 如果游戏已结束或已投票，不再检测
    if (gameState.phase === 'FINISHED' || hasVotedGameEnd) return;

    const now = Date.now();

    // 条件1：有玩家达到目标分数
    if (winMode && winMode.startsWith('SCORE_')) {
        const targetScore = parseInt(winMode.substring(6));
        
        const hasWinner = Object.values(gameState.players).some(p => 
            p.score >= targetScore && p.alive
        );
        
        if (hasWinner) {
            voteGameEnd('SCORE_TARGET_REACHED');
            return;
        }
    }

    // 条件2：所有玩家HP=0
    const allDead = Object.values(gameState.players).every(p => !p.alive || p.hp <= 0);
    
    if (allDead && Object.keys(gameState.players).length > 0) {
        voteGameEnd('ALL_PLAYERS_DEAD');
        return;
    }

    // 条件3：超过时间限制
    if (winMode && winMode.startsWith('TIME_')) {
        const timeStr = winMode.substring(5); // "5M" -> "5M"
        const minutes = parseInt(timeStr.substring(0, timeStr.length - 1)); // "5M" -> 5
        const limitMs = minutes * 60 * 1000;
        const elapsedMs = now - gameStartTime;
        
        if (elapsedMs >= limitMs) {
            voteGameEnd('TIME_LIMIT_REACHED');
            return;
        }
    }
}

function voteGameEnd(reason) {
    if (hasVotedGameEnd) return;
    
    hasVotedGameEnd = true;
    gameEndReason = reason;
    
    console.log('[ArchB-Gossip] Voting game end, reason:', reason);
    
    // 🔥 发送投票到服务器（包含玩家最终数据）
    sendMessage({
        type: 'GAME_END_VOTE',
        username,
        reason,
        score: myPlayer.score,    // 玩家最终分数
        hp: myPlayer.hp,          // 玩家最终血量
        alive: myPlayer.alive,    // 玩家是否存活
        timestamp: Date.now()
    });
}

// ============ 状态广播 ============
function broadcastStates(now) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;

    // 1. 玩家位置（高频：20Hz）
    if (now - lastPositionBroadcast >= POSITION_BROADCAST_INTERVAL) {
        lastPositionBroadcast = now;
        sendMessage({
            type: 'PLAYER_POSITION',
            username,
            x: Math.round(myPlayer.x),
            y: Math.round(myPlayer.y)
        });
    }

    // 2. 石头和子弹位置（低频：10Hz）
    if (now - lastStateBroadcast >= STATE_BROADCAST_INTERVAL) {
        lastStateBroadcast = now;

        // 广播石头位置
        Object.keys(myAsteroids).forEach(asteroidId => {
            const asteroid = myAsteroids[asteroidId];
            sendMessage({
                type: 'ASTEROID_POSITION',
                asteroidId,
                x: Math.round(asteroid.x),
                y: Math.round(asteroid.y)
            });
        });

        // 广播子弹位置
        Object.keys(myBullets).forEach(bulletId => {
            const bullet = myBullets[bulletId];
            sendMessage({
                type: 'BULLET_POSITION',
                bulletId,
                x: Math.round(bullet.x),
                y: Math.round(bullet.y)
            });
        });
    }
}

function sendMessage(msg) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(msg));
    }
}

// ============ 渲染 ============
function render() {
    if (!ctx) return;

    // 清空画布
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

    // 渲染所有玩家
    Object.entries(gameState.players).forEach(([uname, player]) => {
        if (!player.alive) return;

        const color = getPlayerColor(uname);

        // 飞机
        ctx.fillStyle = color;
        ctx.fillRect(player.x - 16, player.y - 16, 32, 32);

        // 名字
        ctx.fillStyle = '#fff';
        ctx.font = '12px Arial';
        ctx.textAlign = 'center';
        ctx.fillText(`✈ ${uname}`, player.x, player.y - 25);

        // HP条
        const barWidth = 32;
        const barHeight = 4;
        ctx.fillStyle = '#f00';
        ctx.fillRect(player.x - barWidth / 2, player.y - 20, barWidth, barHeight);
        ctx.fillStyle = '#0f0';
        ctx.fillRect(player.x - barWidth / 2, player.y - 20, barWidth * (player.hp / 100), barHeight);
    });

    // 渲染所有石头
    Object.entries(gameState.asteroids).forEach(([asteroidId, asteroid]) => {
        const ownerColor = getPlayerColor(asteroid.owner);
        
        // 石头颜色：基于owner颜色，但稍微变暗
        ctx.fillStyle = ownerColor;
        ctx.globalAlpha = 0.7;
        ctx.beginPath();
        ctx.arc(asteroid.x, asteroid.y, asteroid.radius, 0, Math.PI * 2);
        ctx.fill();

        // 边框
        ctx.globalAlpha = 1;
        ctx.strokeStyle = ownerColor;
        ctx.lineWidth = 2;
        ctx.stroke();
    });

    // 渲染所有子弹
    Object.entries(gameState.bullets).forEach(([bulletId, bullet]) => {
        const ownerColor = getPlayerColor(bullet.owner);
        
        ctx.fillStyle = ownerColor;
        ctx.beginPath();
        ctx.arc(bullet.x, bullet.y, BULLET_RADIUS, 0, Math.PI * 2);
        ctx.fill();
    });
}

// ============ UI更新 ============
function updateUI() {
    const hpText = document.getElementById('hpText');
    const scoreText = document.getElementById('scoreText');
    const timeText = document.getElementById('timeText');
    const scoreList = document.getElementById('scoreList');

    if (hpText) hpText.textContent = myPlayer.hp;
    if (scoreText) scoreText.textContent = myPlayer.score;

    // 记分板
    if (scoreList) {
        scoreList.innerHTML = '';
        
        const sorted = Object.entries(gameState.players)
            .sort((a, b) => b[1].score - a[1].score);

        sorted.forEach(([uname, player]) => {
            const li = document.createElement('li');
            li.textContent = `✈ ${uname}: ${player.score} (HP: ${player.hp})`;
            li.style.color = getPlayerColor(uname);
            
            if (!player.alive) {
                li.style.textDecoration = 'line-through';
                li.style.opacity = '0.5';
            }

            scoreList.appendChild(li);
        });
    }
}

// ============ 颜色系统 ============
function getPlayerColor(uname) {
    const index = allPlayers.indexOf(uname);
    if (index === -1) return '#00bfff'; // fallback 蓝色
    return PLAYER_COLORS[index % PLAYER_COLORS.length];
}

// ============ 输入处理 ============
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

function setupButtons() {
    const btnLeave = document.getElementById('btnLeave');
    const btnBackLobby = document.getElementById('btnBackLobby');

    if (btnLeave) btnLeave.addEventListener('click', leaveGame);
    if (btnBackLobby) btnBackLobby.addEventListener('click', leaveGame);
}

function leaveGame() {
    if (ws) {
        ws.send(JSON.stringify({ type: 'LEAVE_GAME' }));
        ws.close();
    }
    window.location.href = '/lobby.html?fromGameExit=1';
}
