"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.registerToolPkg = registerToolPkg;
exports.onInputMenuToggle = onInputMenuToggle;
exports.onFinalize = onFinalize;
const DEFAULT_FLOOR_LIMIT = 5;
const DEFAULT_LIMITER_ENABLED = true;
const FLOOR_OPTIONS = [3, 5, 8, 10, 15, 20, 30, 50, 100];
const ENV_KEYS = {
    floorLimit: "CTX_LIMITER_C_FLOOR_LIMIT",
    enabled: "CTX_LIMITER_C_ENABLED",
};
function readEnv(key) {
    if (typeof getEnv !== "function") {
        return "";
    }
    const value = getEnv(key);
    return value == null ? "" : String(value).trim();
}
function readFloorLimit() {
    const raw = readEnv(ENV_KEYS.floorLimit);
    const parsed = Number.parseInt(raw, 10);
    if (!Number.isFinite(parsed) || parsed < 1) {
        return DEFAULT_FLOOR_LIMIT;
    }
    return parsed;
}
function readLimiterEnabled() {
    const raw = readEnv(ENV_KEYS.enabled).toLowerCase();
    if (!raw) {
        return DEFAULT_LIMITER_ENABLED;
    }
    return raw === "1" || raw === "true" || raw === "yes" || raw === "on";
}
async function writeEnvValue(key, value) {
    await Tools.SoftwareSettings.writeEnvironmentVariable(key, value);
}
async function writeFloorLimit(nextLimit) {
    await writeEnvValue(ENV_KEYS.floorLimit, String(nextLimit));
}
async function writeLimiterEnabled(nextEnabled) {
    await writeEnvValue(ENV_KEYS.enabled, nextEnabled ? "true" : "false");
}
function registerToolPkg() {
    ToolPkg.registerPromptFinalizeHook({
        id: "ctx_limiter_c_finalize",
        function: onFinalize,
    });
    ToolPkg.registerInputMenuTogglePlugin({
        id: "ctx_limiter_c_menu",
        function: onInputMenuToggle,
    });
    return true;
}
async function onInputMenuToggle(event) {
    const payload = event.eventPayload || {};
    const action = payload.action;
    const floorLimit = readFloorLimit();
    const limiterEnabled = readLimiterEnabled();
    if (action === "create") {
        return {
            toggles: [
                {
                    id: "ctx_limiter_toggle",
                    title: "楼层限制器",
                    description: limiterEnabled
                        ? `已开启 · 保留最近 ${floorLimit} 层`
                        : "已关闭",
                    isChecked: limiterEnabled,
                },
                {
                    id: "ctx_limiter_adjust",
                    title: `调节楼层数 ▶ ${floorLimit}`,
                    description: `点击切换: ${FLOOR_OPTIONS.join("/")}`,
                    isChecked: true,
                },
            ],
        };
    }
    if (action === "toggle") {
        const toggleId = payload.toggleId;
        if (toggleId === "ctx_limiter_toggle") {
            await writeLimiterEnabled(!limiterEnabled);
            return { ok: true };
        }
        if (toggleId === "ctx_limiter_adjust") {
            const currentIndex = FLOOR_OPTIONS.indexOf(floorLimit);
            const nextIndex = (currentIndex + 1) % FLOOR_OPTIONS.length;
            await writeFloorLimit(FLOOR_OPTIONS[nextIndex]);
            return { ok: true };
        }
    }
    return { ok: false };
}
function onFinalize(input) {
    const payload = input.eventPayload || {};
    const history = payload.preparedHistory || payload.chatHistory || [];
    if (!history.length) {
        return null;
    }
    const floorLimit = readFloorLimit();
    const limiterEnabled = readLimiterEnabled();
    if (!limiterEnabled) {
        console.log(`[limiter_c] disabled, pass through ${history.length} msgs`);
        return null;
    }
    const systemMsgs = [];
    const userAssistantMsgs = [];
    for (const message of history) {
        if (message.kind === "SYSTEM") {
            systemMsgs.push(message);
            continue;
        }
        if (message.kind === "USER" || message.kind === "ASSISTANT") {
            userAssistantMsgs.push(message);
        }
    }
    const userCount = userAssistantMsgs.filter((message) => message.kind === "USER").length;
    if (userCount <= floorLimit) {
        const result = systemMsgs.concat(userAssistantMsgs);
        console.log(`[limiter_c] ${userCount} floors <= limit ${floorLimit}, no trim, msgs: ${history.length} -> ${result.length}`);
        return { preparedHistory: result };
    }
    let keepFromIndex = 0;
    let countedUsers = 0;
    for (let index = userAssistantMsgs.length - 1; index >= 0; index -= 1) {
        if (userAssistantMsgs[index].kind !== "USER") {
            continue;
        }
        countedUsers += 1;
        if (countedUsers === floorLimit) {
            keepFromIndex = index;
            break;
        }
    }
    const keptMsgs = userAssistantMsgs.slice(keepFromIndex);
    const finalMsgs = systemMsgs.concat(keptMsgs);
    console.log(`[limiter_c] floors: ${userCount}, limit: ${floorLimit}, msgs: ${history.length} -> ${finalMsgs.length} (${systemMsgs.length} sys + ${keptMsgs.length} chat)`);
    return { preparedHistory: finalMsgs };
}
