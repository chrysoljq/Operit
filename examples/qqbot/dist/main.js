"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.registerToolPkg = registerToolPkg;
exports.onApplicationCreate = onApplicationCreate;
exports.onApplicationForeground = onApplicationForeground;
const qqbot_runtime_1 = require("./shared/qqbot_runtime");
function registerToolPkg() {
    ToolPkg.registerAppLifecycleHook({
        id: "qqbot_app_create",
        event: "application_on_create",
        function: onApplicationCreate,
    });
    ToolPkg.registerAppLifecycleHook({
        id: "qqbot_app_foreground",
        event: "application_on_foreground",
        function: onApplicationForeground,
    });
    return true;
}
function runAutoStart(source, input) {
    try {
        return (0, qqbot_runtime_1.ensureQQBotServiceStarted)({
            source,
            allow_missing_config: true,
            timeout_ms: 2500,
            lifecycle_event: input?.eventName || source,
        });
    }
    catch (error) {
        return {
            ok: false,
            source,
            lifecycleEvent: input?.eventName || source,
            error: error && error.message ? error.message : String(error),
        };
    }
}
function onApplicationCreate(input) {
    return runAutoStart("application_on_create", input);
}
function onApplicationForeground(input) {
    return runAutoStart("application_on_foreground", input);
}
