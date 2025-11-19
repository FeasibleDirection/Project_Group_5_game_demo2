// lobby.js

const TABLE_COUNT = 20;

let lobbyAutoRefreshTimer = null;
let lobbyAutoRefreshing = false;
let currentUser = null;
let currentRoomId = null; // 当前用户所在的房间 id（如果有）
let allowAutoEnterGame = true; // 🔥 是否允许自动进入游戏（防止无限跳转）
let lastGameSessionId = -1; // 🔥 记录上一次的游戏局数ID，用于检测"新游戏开始"

function startAutoRefreshLobby() {
    if (lobbyAutoRefreshTimer !== null) return;

    lobbyAutoRefreshTimer = setInterval(async () => {
        if (lobbyAutoRefreshing) return;
        lobbyAutoRefreshing = true;
        try {
            await fetchLobby();
        } catch (e) {
            console.error('auto refresh lobby error', e);
        } finally {
            lobbyAutoRefreshing = false;
        }
    }, 500); // 每 500ms 刷新一次
}

document.addEventListener('DOMContentLoaded', async () => {
    // 🔥 检查URL参数：如果从游戏页面错误返回或主动退出，禁用自动跳转
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.has('fromGameError') || urlParams.has('fromGameExit')) {
        allowAutoEnterGame = false;
        console.log('[LOBBY] 禁用自动进入游戏（从游戏页面返回）');
        // 清除URL参数，避免刷新后仍然禁用
        window.history.replaceState({}, document.title, '/lobby.html');
    }

    // 先检查登录状态
    try {
        const user = await validateToken(); // 来自 session.js
        if (!user) {
            window.location.href = '/login.html';
            return;
        }
        currentUser = user.username;
    } catch (e) {
        window.location.href = '/login.html';
        return;
    }

    const btnRefresh = document.getElementById('btnRefresh');
    const btnCreateRoom = document.getElementById('btnCreateRoom');
    const btnCancelCreate = document.getElementById('btnCancelCreate');
    const btnConfirmCreate = document.getElementById('btnConfirmCreate');

    const createPanel = document.getElementById('createPanel');
    const createMsg = document.getElementById('createMessage');

    // 初始化 20 个空桌子
    renderEmptyTables();

    // 首次拉取大厅
    await fetchLobby();
    // 启动自动刷新
    startAutoRefreshLobby();

    // 顶部按钮
    btnRefresh.addEventListener('click', async () => {
        await fetchLobby();
    });

    btnCreateRoom.addEventListener('click', () => {
        createMsg.textContent = '';
        createMsg.style.color = '#ffffff';
        createPanel.classList.remove('hidden');
    });

    btnCancelCreate.addEventListener('click', () => {
        resetCreateOptions();
        createMsg.textContent = '';
        createMsg.style.color = '#ffffff';
        createPanel.classList.add('hidden');
    });

    btnConfirmCreate.addEventListener('click', async () => {
        await onCreateRoom();
    });

    // 选项 chips
    setupChipGroup('optPlayers');
    setupChipGroup('optMap');
    setupChipGroup('optWin');
});

// 画 20 个“空桌子”的卡片
function renderEmptyTables() {
    const container = document.getElementById('tablesContainer');
    container.innerHTML = '';
    for (let i = 0; i < TABLE_COUNT; i++) {
        const card = document.createElement('div');
        card.className = 'table-card';
        card.dataset.index = String(i);

        card.innerHTML = `
          <div class="table-header">
              <div class="table-title">桌子 #${i}</div>
              <div class="table-status" id="table-status-${i}">空桌</div>
          </div>
          <div class="table-body" id="table-body-${i}">
              <div class="table-empty">暂无房间</div>
          </div>
        `;
        container.appendChild(card);
    }
}

// 向后端请求大厅数据
async function fetchLobby() {
    try {
        const resp = await authFetch('/api/lobby');
        if (!resp.ok) {
            console.error('fetch lobby failed:', await resp.text());
            return;
        }
        const slots = await resp.json();
        applyLobbySlots(slots);
    } catch (e) {
        console.error('fetchLobby error', e);
    }
}

// 把 lobby 数据映射到 20 个桌子卡片上
function applyLobbySlots(slots) {
    currentRoomId = null;

    // 先清空所有桌子为“空桌”
    for (let i = 0; i < TABLE_COUNT; i++) {
        const statusEl = document.getElementById(`table-status-${i}`);
        const bodyEl = document.getElementById(`table-body-${i}`);
        if (!statusEl || !bodyEl) continue;
        statusEl.textContent = '空桌';
        bodyEl.innerHTML = `<div class="table-empty">暂无房间</div>`;
    }

    if (!Array.isArray(slots)) return;

    let shouldEnterGame = false;
    let enterRoomId = null;
    let enterArchitecture = 'A'; // 🔥 记录架构模式
    let enterWinMode = 'SCORE_50'; // 🔥 记录胜利条件

    for (const slot of slots) {
        const idx = slot.index;
        if (idx < 0 || idx >= TABLE_COUNT) continue;
        const statusEl = document.getElementById(`table-status-${idx}`);
        const bodyEl = document.getElementById(`table-body-${idx}`);
        if (!statusEl || !bodyEl) continue;

        if (!slot.occupied || !slot.room) continue;
        const room = slot.room;

        // --- 新结构：players 是 [{username, owner, ready}, ...] ---
        const players = Array.isArray(room.players) ? room.players : [];

        // 当前用户在不在这个房间里
        const currentPlayer = players.find(p => p.username === currentUser);
        const isInRoom = !!currentPlayer;
        const isOwner = currentPlayer ? !!currentPlayer.owner : false;
        const isReady = currentPlayer ? !!currentPlayer.ready : false;

        // 记录当前房间 ID，用于控制"只能加入一个房间"
        if (isInRoom) {
            currentRoomId = room.roomId;
            
            // 🔥 检测游戏局数ID变化：gameSessionId > lastGameSessionId = 新游戏开始
            const currentSessionId = room.gameSessionId || 0;
            if (currentSessionId > lastGameSessionId && lastGameSessionId >= 0) {
                console.log('[LOBBY] 检测到新游戏开始（session', lastGameSessionId, '→', currentSessionId, '），重新允许自动跳转');
                allowAutoEnterGame = true;
            }
            lastGameSessionId = currentSessionId;
            
            if (room.started) {
                shouldEnterGame = true;
                enterRoomId = room.roomId;
                // 🔥 记录架构模式和胜利条件
                enterArchitecture = room.architecture || 'A';
                enterWinMode = room.winMode || 'SCORE_50';
            }
        }

        // 房间标题
        statusEl.textContent = `房间 #${room.roomId}${room.started ? '（已开始）' : ''}`;

        // 队员列表文本
        let membersText = '';
        if (players.length > 0) {
            // 非房主为队员
            const others = players.filter(p => !p.owner);
            if (others.length > 0) {
                membersText = others.map(p =>
                    `${p.username}（${p.ready ? '已准备' : '未准备'}）`
                ).join('，');
            } else {
                membersText = '（暂无队员）';
            }
        }

        const winText = (() => {
            switch (room.winMode) {
                case 'SCORE_50': return 'Score 50';
                case 'SCORE_100': return 'Score 100';
                case 'TIME_1M': return 'Time 1m';
                case 'TIME_5M': return 'Time 5m';
                default: return room.winMode;
            }
        })();

        // 填充桌子信息 + 按钮区域
        bodyEl.innerHTML = '';
        const info = document.createElement('div');
        info.innerHTML = `
            <div>房主：${room.ownerName}</div>
            <div>队员：${membersText}</div>
            <div>人数：${room.currentPlayers} / ${room.maxPlayers}</div>
            <div>地图：${room.mapName}</div>
            <div>胜利条件：${winText}</div>
        `;
        bodyEl.appendChild(info);

        const btnBox = document.createElement('div');
        btnBox.className = 'table-actions';

        if (isInRoom) {
            // 当前用户在这个房间中
            if (!room.started) {
                if (isOwner) {
                    // 房主：两个架构的开始按钮 + 退出
                    const btnStartA = document.createElement('button');
                    btnStartA.textContent = 'Start (Arch A)';
                    btnStartA.className = 'btn-primary';
                    btnStartA.title = 'Architecture A: Server-Authoritative + Event-Driven';
                    btnStartA.onclick = () => startGameArchitectureA(room.roomId, room.winMode);

                    const btnStartB = document.createElement('button');
                    btnStartB.textContent = 'Start (Arch B)';
                    btnStartB.className = 'btn-secondary';
                    btnStartB.title = 'Architecture B: P2P Lockstep (Not implemented)';
                    btnStartB.onclick = () => startGameArchitectureB(room.roomId, room.winMode);

                    const btnLeave = document.createElement('button');
                    btnLeave.textContent = '退出';
                    btnLeave.className = 'btn-danger';
                    btnLeave.onclick = () => leaveRoom(room.roomId);

                    btnBox.appendChild(btnStartA);
                    btnBox.appendChild(btnStartB);
                    btnBox.appendChild(btnLeave);
                } else {
                    // 队员：准备/取消准备 + 退出
                    const btnReady = document.createElement('button');
                    btnReady.textContent = isReady ? '取消准备' : '准备';
                    btnReady.className = isReady ? 'btn-secondary' : 'btn-primary';
                    btnReady.onclick = () => toggleReady(room.roomId);

                    const btnLeave = document.createElement('button');
                    btnLeave.textContent = '退出';
                    btnLeave.className = 'btn-danger';
                    btnLeave.onclick = () => leaveRoom(room.roomId);

                    btnBox.appendChild(btnReady);
                    btnBox.appendChild(btnLeave);
                }
            } else {
                // 游戏已开始：进入游戏 + 退出
                const btnEnter = document.createElement('button');
                btnEnter.textContent = '进入游戏';
                btnEnter.className = 'btn-primary';
                // 🔥 手动点击"进入游戏"按钮时，重新允许自动跳转，并使用正确的架构模式
                btnEnter.onclick = () => {
                    allowAutoEnterGame = true;
                    const arch = room.architecture || 'A';
                    enterGame(room.roomId, room.winMode, arch);
                };

                const btnLeave = document.createElement('button');
                btnLeave.textContent = '退出';
                btnLeave.className = 'btn-danger';
                btnLeave.onclick = () => leaveRoom(room.roomId);

                btnBox.appendChild(btnEnter);
                btnBox.appendChild(btnLeave);
            }
        } else {
            // 当前用户不在这个房间
            const btnJoin = document.createElement('button');
            btnJoin.textContent = '加入';
            btnJoin.className = 'btn-primary';
            btnJoin.disabled =
                room.started ||
                room.currentPlayers >= room.maxPlayers ||
                (currentRoomId !== null && currentRoomId !== room.roomId);

            btnJoin.onclick = () => joinRoom(room.roomId);
            btnBox.appendChild(btnJoin);
        }

        bodyEl.appendChild(btnBox);
    }

    // 🔥 只在允许自动跳转时才执行（防止从游戏错误返回后无限循环）
    if (shouldEnterGame && enterRoomId !== null && allowAutoEnterGame) {
        console.log('[LOBBY] 自动进入游戏 roomId:', enterRoomId, 'arch:', enterArchitecture, 'winMode:', enterWinMode);
        enterGame(enterRoomId, enterWinMode, enterArchitecture);
    }
}

// --------- 创建房间 ---------

async function onCreateRoom() {
    const createMsg = document.getElementById('createMessage');
    createMsg.textContent = '';
    createMsg.style.color = '#ffffff';

    const maxPlayers = parseInt(getSelectedValue('optPlayers', '2'), 10);
    const mapName = getSelectedValue('optMap', 'Nebula-01');
    const winMode = getSelectedValue('optWin', 'SCORE_50');

    const body = { maxPlayers, mapName, winMode };

    try {
        const resp = await authFetch('/api/lobby/rooms', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });

        if (!resp.ok) {
            const text = await resp.text();
            createMsg.style.color = '#ff6b6b';
            createMsg.textContent = text || '创建房间失败';
            return;
        }

        createMsg.style.color = '#8df59d';
        createMsg.textContent = '创建成功！';
        await fetchLobby();
    } catch (e) {
        console.error('create room error', e);
        createMsg.style.color = '#ff6b6b';
        createMsg.textContent = '网络错误，创建失败';
    }
}

// --------- 按钮动作（加入 / 退出 / 准备 / 开始 / 进入游戏） ---------

async function joinRoom(roomId) {
    try {
        const resp = await authFetch(`/api/lobby/rooms/${roomId}/join`, {
            method: 'POST'
        });
        if (!resp.ok) {
            console.error('join room failed', await resp.text());
        } else {
            await fetchLobby();
        }
    } catch (e) {
        console.error('joinRoom error', e);
    }
}

async function leaveRoom(roomId) {
    try {
        const resp = await authFetch(`/api/lobby/rooms/${roomId}/leave`, {
            method: 'POST'
        });
        if (!resp.ok) {
            console.error('leave room failed', await resp.text());
        } else {
            await fetchLobby();
        }
    } catch (e) {
        console.error('leaveRoom error', e);
    }
}

async function toggleReady(roomId) {
    try {
        const resp = await authFetch(`/api/lobby/rooms/${roomId}/toggle-ready`, {
            method: 'POST'
        });
        if (!resp.ok) {
            console.error('toggle ready failed', await resp.text());
        } else {
            await fetchLobby();
        }
    } catch (e) {
        console.error('toggleReady error', e);
    }
}

// Architecture A: 服务器权威 + 事件驱动
async function startGameArchitectureA(roomId, winMode) {
    try {
        const resp = await authFetch(`/api/lobby/rooms/${roomId}/start-architecture-a`, {
            method: 'POST'
        });
        if (!resp.ok) {
            console.error('start game (Arch A) failed', await resp.text());
            alert('无法开始游戏（Architecture A）');
            // 🔥 开始失败，禁用自动跳转
            allowAutoEnterGame = false;
        } else {
            // 🔥 开始成功，允许自动跳转
            allowAutoEnterGame = true;
            console.log('[LOBBY] 开始游戏成功，跳转到 game.html');
            // 房主立即跳转到Architecture A游戏
            enterGame(roomId, winMode, 'A');
        }
    } catch (e) {
        console.error('startGameArchitectureA error', e);
        alert('网络错误，无法开始游戏');
        allowAutoEnterGame = false;
    }
}

// Architecture B: P2P Lockstep（未实现）
async function startGameArchitectureB(roomId, winMode) {
    try {
        const resp = await authFetch(`/api/lobby/rooms/${roomId}/start-architecture-b`, {
            method: 'POST'
        });
        if (!resp.ok) {
            const text = await resp.text();
            alert('Architecture B 未实现：' + text);
            // 🔥 开始失败，禁用自动跳转
            allowAutoEnterGame = false;
        } else {
            // 🔥 开始成功，允许自动跳转
            allowAutoEnterGame = true;
            console.log('[LOBBY] 开始游戏成功（Arch B），跳转到 game.html');
            enterGame(roomId, winMode, 'B');
        }
    } catch (e) {
        console.error('startGameArchitectureB error', e);
        alert('网络错误，无法开始游戏');
        allowAutoEnterGame = false;
    }
}

function enterGame(roomId, winMode, architecture = 'A') {
    // 跳转到游戏页面，传递架构类型
    window.location.href = `/game.html?roomId=${roomId}&win=${winMode}&arch=${architecture}`;
}

// --------- 选项 chips 工具函数 ---------

function setupChipGroup(containerId) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.addEventListener('click', (e) => {
        const target = e.target;
        if (!(target instanceof HTMLElement)) return;
        if (!target.classList.contains('chip')) return;
        for (const child of container.querySelectorAll('.chip')) {
            child.classList.remove('selected');
        }
        target.classList.add('selected');
    });
}

function getSelectedValue(containerId, defaultValue) {
    const container = document.getElementById(containerId);
    if (!container) return defaultValue;
    const selected = container.querySelector('.chip.selected');
    if (!selected) return defaultValue;
    return selected.getAttribute('data-value') || defaultValue;
}

function resetCreateOptions() {
    ['optPlayers', 'optMap', 'optWin'].forEach(id => {
        const container = document.getElementById(id);
        if (!container) return;
        const chips = container.querySelectorAll('.chip');
        chips.forEach((chip, i) => {
            chip.classList.toggle('selected', i === 0);
        });
    });
}


function renderRoomActions(room, currentUsername, container) {
    const isInRoom = room.players.some(p => p.username === currentUsername);
    const isOwner = room.ownerName === currentUsername;

    const actionsDiv = document.createElement('div');
    actionsDiv.className = 'table-actions';

    if (isOwner) {
        const btnStartA = document.createElement('button');
        btnStartA.textContent = '开始(A)';
        btnStartA.className = 'btn btn-primary';
        btnStartA.onclick = () => startGame(room.roomId, 'ARCH_A');

        const btnStartB = document.createElement('button');
        btnStartB.textContent = '开始(B)';
        btnStartB.className = 'btn btn-secondary';
        btnStartB.onclick = () => startGame(room.roomId, 'ARCH_B');

        const btnLeave = document.createElement('button');
        btnLeave.textContent = '退出';
        btnLeave.onclick = () => leaveRoom(room.roomId);

        actionsDiv.appendChild(btnStartA);
        actionsDiv.appendChild(btnStartB);
        actionsDiv.appendChild(btnLeave);
    } else if (isInRoom) {
        // 队员：准备 / 退出
        const btnReady = document.createElement('button');
        btnReady.textContent = room.isReady ? '取消准备' : '准备';
        btnReady.onclick = () => toggleReady(room.roomId);

        const btnLeave = document.createElement('button');
        btnLeave.textContent = '退出';
        btnLeave.onclick = () => leaveRoom(room.roomId);

        actionsDiv.appendChild(btnReady);
        actionsDiv.appendChild(btnLeave);
    } else {
        const btnJoin = document.createElement('button');
        btnJoin.textContent = '加入';
        btnJoin.onclick = () => joinRoom(room.roomId);
        actionsDiv.appendChild(btnJoin);
    }

    container.appendChild(actionsDiv);
}

async function startGame(roomId, mode) {
    const resp = await authFetch(`/api/lobby/rooms/${roomId}/start?mode=${mode}`, {
        method: 'POST'
    });
    if (!resp.ok) {
        const txt = await resp.text();
        alert(txt || '开始失败');
    } else {
        // 开始成功后，由 lobby 的 500ms 轮询检测到 room.started=true 后自动跳转 game.html
        await fetchLobbyOnce();
    }
}

