/* METADATA
{
    "name": "qqbot",
    "display_name": {
        "zh": "QQ Bot",
        "en": "QQ Bot"
    },
    "description": {
        "zh": "把腾讯 QQ Bot 的配置、后台 Webhook 收消息服务、消息队列读取，以及 C2C/群发消息能力整理成 Operit 工具。",
        "en": "Expose Tencent QQ Bot configuration, background webhook service, queued inbound events, and C2C/group messaging as Operit tools."
    },
    "enabledByDefault": true,
    "category": "Communication",
    "env": [
        {
            "name": "QQBOT_APP_ID",
            "description": { "zh": "QQ Bot AppID", "en": "QQ Bot AppID" },
            "required": false
        },
        {
            "name": "QQBOT_APP_SECRET",
            "description": { "zh": "QQ Bot AppSecret", "en": "QQ Bot AppSecret" },
            "required": false
        }
    ],
    "tools": [
        {
            "name": "usage_advice",
            "description": {
                "zh": "QQ Bot 使用建议：\\n- 先用 qqbot_configure 保存 AppID、AppSecret 和回调监听地址。\\n- ToolPkg 会在 application_on_create / application_on_foreground 尝试自启动收消息服务；热加载后如果想立刻拉起，也可以手动调用 qqbot_service_start。\\n- 腾讯平台的回调地址请填写 qqbot_status 返回的 publicCallbackUrl（有公网地址时）或 localCallbackUrl（仅本地调试时）。\\n- 收到消息后，用 qqbot_receive_events 取出事件，再把其中 replyHint 里的 scene / openid / group_openid / msg_id 传给发送工具。\\n- 这套实现不改 Kotlin，收消息完全走 ToolPkg 内部启动的本地 HTTP 服务。",
                "en": "QQ Bot advice:\\n- Save AppID, AppSecret, and webhook listen settings with qqbot_configure first.\\n- The ToolPkg will try to auto-start its inbound service on application_on_create / application_on_foreground; after hot reload you can also call qqbot_service_start to launch it immediately.\\n- Configure the Tencent callback URL from qqbot_status, preferably publicCallbackUrl when a public base URL is available.\\n- Use qqbot_receive_events to dequeue inbound events, then pass replyHint.scene / openid / group_openid / msg_id into the send tools.\\n- No Kotlin changes are required; inbound messages are handled by a local HTTP service started inside the ToolPkg."
            },
            "parameters": [],
            "advice": true
        },
        {
            "name": "qqbot_configure",
            "description": {
                "zh": "保存 QQ Bot 的 AppID、AppSecret，并把沙箱开关与 Webhook 监听参数写入本地配置；按需自动重启后台收消息服务。",
                "en": "Persist QQ Bot AppID and AppSecret, while storing sandbox and webhook listen settings in local config; optionally restart the background inbound service."
            },
            "parameters": [
                {
                    "name": "app_id",
                    "description": { "zh": "QQ Bot AppID", "en": "QQ Bot AppID" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "app_secret",
                    "description": { "zh": "QQ Bot AppSecret", "en": "QQ Bot AppSecret" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "use_sandbox",
                    "description": { "zh": "是否使用沙箱 OpenAPI", "en": "Whether to use sandbox OpenAPI" },
                    "type": "boolean",
                    "required": false
                },
                {
                    "name": "callback_host",
                    "description": { "zh": "本地监听 Host，例如 0.0.0.0", "en": "Local callback listen host, for example 0.0.0.0" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "callback_port",
                    "description": { "zh": "本地监听端口，例如 9000", "en": "Local callback listen port, for example 9000" },
                    "type": "number",
                    "required": false
                },
                {
                    "name": "callback_path",
                    "description": { "zh": "回调路径，例如 /qqbot", "en": "Callback path, for example /qqbot" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "public_base_url",
                    "description": { "zh": "公网基础地址，例如 https://example.com", "en": "Public base URL, for example https://example.com" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "test_connection",
                    "description": { "zh": "保存后是否立即测试凭证", "en": "Whether to test credentials after saving" },
                    "type": "boolean",
                    "required": false
                },
                {
                    "name": "restart_service",
                    "description": { "zh": "保存后是否强制重启后台服务", "en": "Whether to force-restart the background service after saving" },
                    "type": "boolean",
                    "required": false
                }
            ]
        },
        {
            "name": "qqbot_status",
            "description": {
                "zh": "读取当前 QQ Bot 配置摘要、后台服务状态、消息队列积压数量和推荐回调地址。",
                "en": "Read the current QQ Bot config summary, background service status, queued event count, and suggested callback URLs."
            },
            "parameters": []
        },
        {
            "name": "qqbot_service_start",
            "description": {
                "zh": "立即启动 QQ Bot 后台 Webhook 收消息服务；可选强制重启，适合热加载后立刻拉起。",
                "en": "Start the QQ Bot background webhook service immediately; optionally force a restart, which is useful right after hot reload."
            },
            "parameters": [
                {
                    "name": "restart",
                    "description": { "zh": "是否强制先停掉旧服务再重启", "en": "Whether to force-stop the previous service before starting" },
                    "type": "boolean",
                    "required": false
                },
                {
                    "name": "timeout_ms",
                    "description": { "zh": "等待服务启动成功的超时毫秒数，默认 4000", "en": "Timeout in milliseconds while waiting for the service to become healthy, default 4000" },
                    "type": "number",
                    "required": false
                }
            ]
        },
        {
            "name": "qqbot_service_stop",
            "description": {
                "zh": "停止当前 QQ Bot 后台 Webhook 收消息服务。",
                "en": "Stop the current QQ Bot background webhook service."
            },
            "parameters": [
                {
                    "name": "timeout_ms",
                    "description": { "zh": "等待服务停掉的超时毫秒数，默认 4000", "en": "Timeout in milliseconds while waiting for the service to stop, default 4000" },
                    "type": "number",
                    "required": false
                }
            ]
        },
        {
            "name": "qqbot_receive_events",
            "description": {
                "zh": "从后台服务维护的事件队列里读取 QQ Bot 收到的消息/回调事件；默认会消费并移除这些事件。",
                "en": "Read inbound QQ Bot message/webhook events from the queue maintained by the background service; by default the events are consumed and removed."
            },
            "parameters": [
                {
                    "name": "limit",
                    "description": { "zh": "最多取多少条事件，默认 20", "en": "Maximum number of events to return, default 20" },
                    "type": "number",
                    "required": false
                },
                {
                    "name": "consume",
                    "description": { "zh": "是否在读取后从队列移除，默认 true", "en": "Whether to remove the returned events from the queue, default true" },
                    "type": "boolean",
                    "required": false
                },
                {
                    "name": "scene",
                    "description": { "zh": "可选过滤：c2c / group / unknown", "en": "Optional scene filter: c2c / group / unknown" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "event_type",
                    "description": { "zh": "可选过滤：只保留指定 eventType", "en": "Optional filter: keep only a specific eventType" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "include_raw",
                    "description": { "zh": "是否返回 rawBody / rawPayload，默认 false", "en": "Whether to return rawBody / rawPayload, default false" },
                    "type": "boolean",
                    "required": false
                },
                {
                    "name": "auto_start",
                    "description": { "zh": "若服务未运行，是否自动尝试启动，默认 true", "en": "Whether to auto-start the service when it is not running, default true" },
                    "type": "boolean",
                    "required": false
                }
            ]
        },
        {
            "name": "qqbot_clear_events",
            "description": {
                "zh": "清空当前 QQ Bot 事件队列。",
                "en": "Clear the current QQ Bot event queue."
            },
            "parameters": []
        },
        {
            "name": "qqbot_test_connection",
            "description": {
                "zh": "验证 AppID/AppSecret 是否能正常获取 access token，并尝试读取机器人资料。",
                "en": "Verify whether AppID/AppSecret can obtain an access token and attempt to read the bot profile."
            },
            "parameters": [
                {
                    "name": "timeout_ms",
                    "description": { "zh": "本次请求超时毫秒数（默认 20000）", "en": "Timeout for this request in milliseconds (default 20000)" },
                    "type": "number",
                    "required": false
                }
            ]
        },
        {
            "name": "qqbot_send_c2c_message",
            "description": {
                "zh": "向指定用户 openid 发送一条 C2C 文本消息，可用于主动消息，也可用于对已有 msg_id 的被动回复。",
                "en": "Send one C2C text message to a specific user openid. Can be used as a proactive message or a passive reply to an existing msg_id."
            },
            "parameters": [
                {
                    "name": "openid",
                    "description": { "zh": "目标用户 openid", "en": "Target user openid" },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "content",
                    "description": { "zh": "发送的文本内容", "en": "Text content to send" },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "msg_id",
                    "description": { "zh": "可选：要回复的原消息 ID", "en": "Optional source message ID to reply to" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "event_id",
                    "description": { "zh": "可选：要回复的事件 ID", "en": "Optional source event ID to reply to" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "msg_seq",
                    "description": { "zh": "回复序号，默认 1", "en": "Reply sequence number, default 1" },
                    "type": "number",
                    "required": false
                },
                {
                    "name": "msg_type",
                    "description": { "zh": "消息类型，默认 0（文本）", "en": "Message type, default 0 (text)" },
                    "type": "number",
                    "required": false
                },
                {
                    "name": "timeout_ms",
                    "description": { "zh": "本次请求超时毫秒数（默认 20000）", "en": "Timeout for this request in milliseconds (default 20000)" },
                    "type": "number",
                    "required": false
                }
            ]
        },
        {
            "name": "qqbot_send_group_message",
            "description": {
                "zh": "向指定群 group_openid 发送一条群文本消息，可用于主动消息，也可用于对已有 msg_id 的被动回复。",
                "en": "Send one group text message to a specific group_openid. Can be used as a proactive message or a passive reply to an existing msg_id."
            },
            "parameters": [
                {
                    "name": "group_openid",
                    "description": { "zh": "目标群 group_openid", "en": "Target group_openid" },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "content",
                    "description": { "zh": "发送的文本内容", "en": "Text content to send" },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "msg_id",
                    "description": { "zh": "可选：要回复的原消息 ID", "en": "Optional source message ID to reply to" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "event_id",
                    "description": { "zh": "可选：要回复的事件 ID", "en": "Optional source event ID to reply to" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "msg_seq",
                    "description": { "zh": "回复序号，默认 1", "en": "Reply sequence number, default 1" },
                    "type": "number",
                    "required": false
                },
                {
                    "name": "msg_type",
                    "description": { "zh": "消息类型，默认 0（文本）", "en": "Message type, default 0 (text)" },
                    "type": "number",
                    "required": false
                },
                {
                    "name": "timeout_ms",
                    "description": { "zh": "本次请求超时毫秒数（默认 20000）", "en": "Timeout for this request in milliseconds (default 20000)" },
                    "type": "number",
                    "required": false
                }
            ]
        }
    ]
}
*/

import {
    ensureQQBotServiceStarted,
    qqbot_configure,
    qqbot_status,
    qqbot_service_start,
    qqbot_service_stop,
    qqbot_receive_events,
    qqbot_clear_events,
    qqbot_test_connection,
    qqbot_send_c2c_message,
    qqbot_send_group_message
} from "../shared/qqbot_runtime";

exports.ensureQQBotServiceStarted = ensureQQBotServiceStarted;
exports.qqbot_configure = qqbot_configure;
exports.qqbot_status = qqbot_status;
exports.qqbot_service_start = qqbot_service_start;
exports.qqbot_service_stop = qqbot_service_stop;
exports.qqbot_receive_events = qqbot_receive_events;
exports.qqbot_clear_events = qqbot_clear_events;
exports.qqbot_test_connection = qqbot_test_connection;
exports.qqbot_send_c2c_message = qqbot_send_c2c_message;
exports.qqbot_send_group_message = qqbot_send_group_message;
