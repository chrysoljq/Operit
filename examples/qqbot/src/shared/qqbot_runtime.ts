type QQBotConfigureParams = {
    app_id?: string;
    app_secret?: string;
    use_sandbox?: boolean;
    callback_host?: string;
    callback_port?: number;
    callback_path?: string;
    public_base_url?: string;
    test_connection?: boolean;
    restart_service?: boolean;
};

type QQBotTestConnectionParams = {
    timeout_ms?: number;
};

type QQBotServiceStartParams = {
    restart?: boolean;
    timeout_ms?: number;
};

type QQBotServiceStopParams = {
    timeout_ms?: number;
};

type QQBotReceiveEventsParams = {
    limit?: number;
    consume?: boolean;
    scene?: string;
    event_type?: string;
    include_raw?: boolean;
    auto_start?: boolean;
};

type QQBotSendMessageParams = {
    content: string;
    msg_id?: string;
    event_id?: string;
    msg_seq?: number;
    msg_type?: number;
    timeout_ms?: number;
};

type QQBotSendC2CMessageParams = QQBotSendMessageParams & {
    openid: string;
};

type QQBotSendGroupMessageParams = QQBotSendMessageParams & {
    group_openid: string;
};

type QQBotConfigSnapshot = {
    appId: string;
    appSecret: string;
    useSandbox: boolean;
    callbackHost: string;
    callbackPort: number;
    callbackPath: string;
    publicBaseUrl: string;
};

type QQBotTokenResponse = {
    accessToken: string;
    expiresIn: number;
    tokenType: string;
};

type EnsureQQBotServiceOptions = {
    restart?: boolean;
    timeout_ms?: number;
    allow_missing_config?: boolean;
    source?: string;
    lifecycle_event?: string;
};

const PACKAGE_VERSION = "0.2.0";
const DEFAULT_TIMEOUT_MS = 20000;
const DEFAULT_SERVICE_WAIT_MS = 4000;
const DEFAULT_CALLBACK_HOST = "0.0.0.0";
const DEFAULT_CALLBACK_PORT = 9000;
const DEFAULT_CALLBACK_PATH = "/qqbot";
const DEFAULT_RECEIVE_LIMIT = 20;
const MAX_RECEIVE_LIMIT = 100;
const MAX_QUEUE_ITEMS = 200;
const SERVICE_POLL_INTERVAL_MS = 100;
const CONTROL_PATH = "/_operit/qqbot/control";
const STATE_DIRECTORY_NAME = "toolpkg_qqbot_service";
const CONFIG_FILE_NAME = "config.json";
const STATE_FILE_NAME = "service_state.json";
const QUEUE_FILE_NAME = "event_queue.json";
const LOCK_FILE_NAME = "runtime.lock";
const TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
const API_BASE_URL = "https://api.sgroup.qq.com";
const SANDBOX_API_BASE_URL = "https://sandbox.api.sgroup.qq.com";
const HTTP_OK = 200;

const OPCODES = {
    dispatch: 0,
    heartbeat: 1,
    heartbeatAck: 11,
    callbackAck: 12,
    callbackValidation: 13
};

const ENV_KEYS = {
    appId: "QQBOT_APP_ID",
    appSecret: "QQBOT_APP_SECRET"
};

const serviceRuntime: {
    thread: any;
    serverSocket: any;
    startedAt: number;
    lastPacketAt: number;
    lastEventAt: number;
    packetCount: number;
    eventCount: number;
    stopRequested: boolean;
    stopReason: string;
    lastError: string;
    controlToken: string;
    callbackHost: string;
    callbackPort: number;
    callbackPath: string;
    source: string;
} = {
    thread: null,
    serverSocket: null,
    startedAt: 0,
    lastPacketAt: 0,
    lastEventAt: 0,
    packetCount: 0,
    eventCount: 0,
    stopRequested: false,
    stopReason: "",
    lastError: "",
    controlToken: "",
    callbackHost: "",
    callbackPort: 0,
    callbackPath: "",
    source: ""
};

function asText(value: unknown): string {
    return String(value == null ? "" : value);
}

function hasOwn(value: unknown, key: string): boolean {
    return !!value && typeof value === "object" && Object.prototype.hasOwnProperty.call(value, key);
}

function isObject(value: unknown): value is Record<string, unknown> {
    return !!value && typeof value === "object" && !Array.isArray(value);
}

function firstNonBlank(...values: Array<string | null | undefined>): string {
    for (let index = 0; index < values.length; index += 1) {
        const candidate = values[index];
        if (typeof candidate === "string" && candidate.trim()) {
            return candidate.trim();
        }
    }
    return "";
}

function safeErrorMessage(error: any): string {
    return error && error.message ? error.message : String(error);
}

function readEnv(key: string): string {
    if (typeof getEnv !== "function") {
        return "";
    }
    const value = getEnv(key);
    return value == null ? "" : asText(value).trim();
}

async function writeEnv(key: string, value: string): Promise<void> {
    await Tools.SoftwareSettings.writeEnvironmentVariable(key, value);
}

function ensureHttpScheme(raw: string): string {
    if (raw.startsWith("http://") || raw.startsWith("https://")) {
        return raw;
    }
    return `http://${raw}`;
}

function normalizeBaseUrl(raw: string): string {
    const value = raw.trim();
    if (!value) {
        return "";
    }
    return ensureHttpScheme(value).replace(/\/+$/g, "");
}

function normalizeCallbackPath(raw: string): string {
    const value = raw.trim();
    if (!value) {
        return DEFAULT_CALLBACK_PATH;
    }
    const withoutQuery = value.split("?")[0].split("#")[0].trim();
    if (!withoutQuery) {
        return DEFAULT_CALLBACK_PATH;
    }
    const prefixed = withoutQuery.startsWith("/") ? withoutQuery : `/${withoutQuery}`;
    return prefixed.replace(/\/+$/g, "") || DEFAULT_CALLBACK_PATH;
}

function parsePositiveInt(value: unknown, fieldName: string, fallbackValue: number): number {
    const raw = asText(value).trim();
    if (!raw) {
        return fallbackValue;
    }
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed <= 0) {
        throw new Error(`Invalid ${fieldName}: expected positive integer`);
    }
    return parsed;
}

function parsePort(value: unknown, fieldName: string, fallbackValue: number): number {
    const port = parsePositiveInt(value, fieldName, fallbackValue);
    if (port < 1 || port > 65535) {
        throw new Error(`Invalid ${fieldName}: expected value between 1 and 65535`);
    }
    return port;
}

function parseNonNegativeInt(value: unknown, fieldName: string, fallbackValue: number): number {
    const raw = asText(value).trim();
    if (!raw) {
        return fallbackValue;
    }
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed < 0) {
        throw new Error(`Invalid ${fieldName}: expected non-negative integer`);
    }
    return parsed;
}

function parseOptionalBoolean(value: unknown, fieldName: string): boolean | undefined {
    if (value === undefined) {
        return undefined;
    }
    if (typeof value === "boolean") {
        return value;
    }
    const raw = asText(value).trim().toLowerCase();
    if (!raw) {
        return undefined;
    }
    if (raw === "true" || raw === "1" || raw === "yes") {
        return true;
    }
    if (raw === "false" || raw === "0" || raw === "no") {
        return false;
    }
    throw new Error(`Invalid ${fieldName}: expected boolean`);
}

function parseMessageType(value: unknown): number {
    const raw = asText(value).trim();
    if (!raw) {
        return 0;
    }
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed < 0) {
        throw new Error("Invalid msg_type: expected non-negative integer");
    }
    return parsed;
}

function parseMsgSeq(value: unknown): number {
    const raw = asText(value).trim();
    if (!raw) {
        return 1;
    }
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed <= 0) {
        throw new Error("Invalid msg_seq: expected positive integer");
    }
    return parsed;
}

function parseJsonObject(content: string): Record<string, unknown> {
    const trimmed = content.trim();
    if (!trimmed) {
        return {};
    }
    const parsed = JSON.parse(trimmed);
    if (!isObject(parsed)) {
        throw new Error("Expected JSON object");
    }
    return parsed;
}

function toHttpTimeoutSeconds(timeoutMs: number): number {
    return Math.max(1, Math.ceil(timeoutMs / 1000));
}

function maskSecret(secret: string): string {
    const value = secret.trim();
    if (!value) {
        return "";
    }
    if (value.length <= 6) {
        return `${value.slice(0, 1)}***${value.slice(-1)}`;
    }
    return `${value.slice(0, 3)}***${value.slice(-3)}`;
}

function buildStatus(snapshot: QQBotConfigSnapshot) {
    const publicCallbackUrl = snapshot.publicBaseUrl
        ? `${snapshot.publicBaseUrl}${snapshot.callbackPath}`
        : "";
    const localConnectHost = resolveLocalConnectHost(snapshot.callbackHost);
    return {
        packageVersion: PACKAGE_VERSION,
        configured: !!snapshot.appId && !!snapshot.appSecret,
        appId: snapshot.appId,
        appSecretMasked: maskSecret(snapshot.appSecret),
        useSandbox: snapshot.useSandbox,
        callbackHost: snapshot.callbackHost,
        callbackPort: snapshot.callbackPort,
        callbackPath: snapshot.callbackPath,
        publicBaseUrl: snapshot.publicBaseUrl,
        publicCallbackUrl,
        localCallbackUrl: `http://${localConnectHost}:${snapshot.callbackPort}${snapshot.callbackPath}`,
        openApiBaseUrl: snapshot.useSandbox ? SANDBOX_API_BASE_URL : API_BASE_URL
    };
}

function readConfigSnapshot(overrides?: Record<string, unknown>): QQBotConfigSnapshot {
    const storedConfig = readPersistedConfig();
    const appId =
        overrides && hasOwn(overrides, "appId")
            ? asText(overrides.appId).trim()
            : readEnv(ENV_KEYS.appId);
    const appSecret =
        overrides && hasOwn(overrides, "appSecret")
            ? asText(overrides.appSecret).trim()
            : readEnv(ENV_KEYS.appSecret);
    const useSandboxRaw =
        overrides && hasOwn(overrides, "useSandbox")
            ? overrides.useSandbox
            : storedConfig.useSandbox;
    const callbackHost =
        overrides && hasOwn(overrides, "callbackHost")
            ? firstNonBlank(asText(overrides.callbackHost).trim(), DEFAULT_CALLBACK_HOST)
            : firstNonBlank(asText(storedConfig.callbackHost), DEFAULT_CALLBACK_HOST);
    const callbackPort =
        overrides && hasOwn(overrides, "callbackPort")
            ? parsePort(overrides.callbackPort, "callback_port", DEFAULT_CALLBACK_PORT)
            : parsePort(storedConfig.callbackPort, "callback_port", DEFAULT_CALLBACK_PORT);
    const callbackPath =
        overrides && hasOwn(overrides, "callbackPath")
            ? normalizeCallbackPath(asText(overrides.callbackPath))
            : normalizeCallbackPath(asText(storedConfig.callbackPath) || DEFAULT_CALLBACK_PATH);
    const publicBaseUrl =
        overrides && hasOwn(overrides, "publicBaseUrl")
            ? normalizeBaseUrl(asText(overrides.publicBaseUrl))
            : normalizeBaseUrl(asText(storedConfig.publicBaseUrl));
    const parsedUseSandbox = parseOptionalBoolean(useSandboxRaw, "use_sandbox");

    return {
        appId,
        appSecret,
        useSandbox: parsedUseSandbox === true,
        callbackHost,
        callbackPort,
        callbackPath,
        publicBaseUrl
    };
}

function requireConfiguredSnapshot(overrides?: Record<string, unknown>): QQBotConfigSnapshot {
    const snapshot = readConfigSnapshot(overrides);
    if (!snapshot.appId) {
        throw new Error("Missing env: QQBOT_APP_ID");
    }
    if (!snapshot.appSecret) {
        throw new Error("Missing env: QQBOT_APP_SECRET");
    }
    return snapshot;
}

function byteArrayLength(value: any): number {
    const ReflectArray = Java.type("java.lang.reflect.Array");
    return ReflectArray.getLength(value);
}

function utf8Bytes(value: string): any {
    const JavaString: any = Java.type("java.lang.String");
    const StandardCharsets = Java.type("java.nio.charset.StandardCharsets");
    return new JavaString(String(value)).getBytes(StandardCharsets.UTF_8);
}

function bytesToUtf8String(bytes: any): string {
    const JavaString: any = Java.type("java.lang.String");
    const StandardCharsets = Java.type("java.nio.charset.StandardCharsets");
    return new JavaString(bytes, StandardCharsets.UTF_8).toString();
}

function concatBytes(left: any, right: any): any {
    const ByteArrayOutputStream = Java.type("java.io.ByteArrayOutputStream");
    const buffer = new ByteArrayOutputStream();
    buffer.write(left);
    buffer.write(right);
    return buffer.toByteArray();
}

function buildSeedBytes(secret: string): any {
    let seed = secret;
    while (seed.length < 32) {
        seed += seed;
    }
    return utf8Bytes(seed.slice(0, 32));
}

function generateSignatureHex(secret: string, payloadBytes: any): string {
    const Ed25519PrivateKeyParameters = Java.type("org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters");
    const Ed25519Signer = Java.type("org.bouncycastle.crypto.signers.Ed25519Signer");
    const Hex = Java.type("org.bouncycastle.util.encoders.Hex");

    const privateKey = new Ed25519PrivateKeyParameters(buildSeedBytes(secret), 0);
    const signer = new Ed25519Signer();
    signer.init(true, privateKey);
    signer.update(payloadBytes, 0, byteArrayLength(payloadBytes));
    return String(Hex.toHexString(signer.generateSignature()));
}

function verifySignatureHex(secret: string, payloadBytes: any, signatureHex: string): boolean {
    try {
        const Ed25519PrivateKeyParameters = Java.type("org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters");
        const Ed25519Signer = Java.type("org.bouncycastle.crypto.signers.Ed25519Signer");
        const Hex = Java.type("org.bouncycastle.util.encoders.Hex");

        const privateKey = new Ed25519PrivateKeyParameters(buildSeedBytes(secret), 0);
        const publicKey = privateKey.generatePublicKey();
        const signer = new Ed25519Signer();
        signer.init(false, publicKey);
        signer.update(payloadBytes, 0, byteArrayLength(payloadBytes));
        return !!signer.verifySignature(Hex.decode(signatureHex));
    } catch (_error) {
        return false;
    }
}

function getStateDirectory(): any {
    const File = Java.type("java.io.File");
    const directory = new File(Java.getApplicationContext().getFilesDir(), STATE_DIRECTORY_NAME);
    if (!directory.exists()) {
        directory.mkdirs();
    }
    return directory;
}

function getStateFile(name: string): any {
    const File = Java.type("java.io.File");
    return new File(getStateDirectory(), name);
}

function safeClose(target: any): void {
    try {
        if (target) {
            target.close();
        }
    } catch (_error) {
        // Ignore close errors.
    }
}

function safeRelease(target: any): void {
    try {
        if (target) {
            target.release();
        }
    } catch (_error) {
        // Ignore release errors.
    }
}

function readTextFile(file: any): string {
    const Files = Java.type("java.nio.file.Files");
    if (!file.exists()) {
        return "";
    }
    const bytes = Files.readAllBytes(file.toPath());
    return bytesToUtf8String(bytes);
}

function writeTextFile(file: any, content: string): void {
    const FileOutputStream = Java.type("java.io.FileOutputStream");
    const parent = file.getParentFile();
    if (parent && !parent.exists()) {
        parent.mkdirs();
    }

    let outputStream: any = null;
    try {
        outputStream = new FileOutputStream(file, false);
        outputStream.write(utf8Bytes(content));
        outputStream.flush();
    } finally {
        safeClose(outputStream);
    }
}

function withRuntimeLock<T>(action: () => T): T {
    const RandomAccessFile = Java.type("java.io.RandomAccessFile");

    const lockFile = getStateFile(LOCK_FILE_NAME);
    let randomAccessFile: any = null;
    let channel: any = null;
    let lock: any = null;

    try {
        randomAccessFile = new RandomAccessFile(lockFile, "rw");
        channel = randomAccessFile.getChannel();
        lock = channel.lock();
        return action();
    } finally {
        safeRelease(lock);
        safeClose(channel);
        safeClose(randomAccessFile);
    }
}

function readPersistedStateUnlocked(): Record<string, unknown> {
    const raw = readTextFile(getStateFile(STATE_FILE_NAME)).trim();
    if (!raw) {
        return {};
    }
    return parseJsonObject(raw);
}

function writePersistedStateUnlocked(state: Record<string, unknown>): void {
    writeTextFile(getStateFile(STATE_FILE_NAME), JSON.stringify(state));
}

function updatePersistedState(patch: Record<string, unknown>): Record<string, unknown> {
    return withRuntimeLock(() => {
        const current = readPersistedStateUnlocked();
        const next = { ...current, ...patch };
        writePersistedStateUnlocked(next);
        return next;
    });
}

function readPersistedState(): Record<string, unknown> {
    return withRuntimeLock(() => readPersistedStateUnlocked());
}

function readPersistedConfigUnlocked(): Record<string, unknown> {
    const raw = readTextFile(getStateFile(CONFIG_FILE_NAME)).trim();
    if (!raw) {
        return {};
    }
    return parseJsonObject(raw);
}

function writePersistedConfigUnlocked(config: Record<string, unknown>): void {
    writeTextFile(getStateFile(CONFIG_FILE_NAME), JSON.stringify(config));
}

function updatePersistedConfig(patch: Record<string, unknown>): Record<string, unknown> {
    return withRuntimeLock(() => {
        const current = readPersistedConfigUnlocked();
        const next = { ...current, ...patch };
        writePersistedConfigUnlocked(next);
        return next;
    });
}

function readPersistedConfig(): Record<string, unknown> {
    return withRuntimeLock(() => readPersistedConfigUnlocked());
}

function readQueueUnlocked(): Array<Record<string, unknown>> {
    const raw = readTextFile(getStateFile(QUEUE_FILE_NAME)).trim();
    if (!raw) {
        return [];
    }
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
        throw new Error("QQ Bot queue file is not a JSON array");
    }
    return parsed.filter(isObject);
}

function writeQueueUnlocked(events: Array<Record<string, unknown>>): void {
    writeTextFile(getStateFile(QUEUE_FILE_NAME), JSON.stringify(events));
}

function appendEventToQueue(event: Record<string, unknown>): { queueSize: number } {
    return withRuntimeLock(() => {
        const events = readQueueUnlocked();
        events.push(event);
        if (events.length > MAX_QUEUE_ITEMS) {
            events.splice(0, events.length - MAX_QUEUE_ITEMS);
        }
        writeQueueUnlocked(events);
        return { queueSize: events.length };
    });
}

function clearQueuedEventsInternal(): { clearedCount: number } {
    return withRuntimeLock(() => {
        const events = readQueueUnlocked();
        writeQueueUnlocked([]);
        return { clearedCount: events.length };
    });
}

function buildQueueSummary(): Record<string, unknown> {
    return withRuntimeLock(() => {
        const events = readQueueUnlocked();
        return {
            pendingCount: events.length,
            oldestEventAt: events.length > 0 ? firstNonBlank(asText(events[0].receivedAt), asText(events[0].timestamp)) : "",
            newestEventAt: events.length > 0
                ? firstNonBlank(
                    asText(events[events.length - 1].receivedAt),
                    asText(events[events.length - 1].timestamp)
                )
                : ""
        };
    });
}

function sanitizeEvent(event: Record<string, unknown>, includeRaw: boolean): Record<string, unknown> {
    if (includeRaw) {
        return event;
    }
    const clone: Record<string, unknown> = { ...event };
    delete clone.rawBody;
    delete clone.rawPayload;
    return clone;
}

function eventMatchesFilter(
    event: Record<string, unknown>,
    scene: string,
    eventType: string
): boolean {
    if (scene && asText(event.scene).trim().toLowerCase() !== scene) {
        return false;
    }
    if (eventType && asText(event.eventType).trim() !== eventType) {
        return false;
    }
    return true;
}

function receiveQueuedEvents(params: QQBotReceiveEventsParams = {}) {
    const limit = Math.min(
        MAX_RECEIVE_LIMIT,
        parsePositiveInt(params.limit, "limit", DEFAULT_RECEIVE_LIMIT)
    );
    const consume = parseOptionalBoolean(params.consume, "consume") !== false;
    const includeRaw = parseOptionalBoolean(params.include_raw, "include_raw") === true;
    const scene = asText(params.scene).trim().toLowerCase();
    const eventType = asText(params.event_type).trim();

    return withRuntimeLock(() => {
        const queued = readQueueUnlocked();
        const selected: Array<Record<string, unknown>> = [];
        const remaining: Array<Record<string, unknown>> = [];

        for (let index = 0; index < queued.length; index += 1) {
            const item = queued[index];
            const matches = eventMatchesFilter(item, scene, eventType);
            if (matches && selected.length < limit) {
                selected.push(sanitizeEvent(item, includeRaw));
                if (!consume) {
                    remaining.push(item);
                }
                continue;
            }
            remaining.push(item);
        }

        if (consume) {
            writeQueueUnlocked(remaining);
        }

        return {
            consume,
            filter: {
                scene,
                eventType
            },
            returnedCount: selected.length,
            remainingCount: consume ? remaining.length : queued.length,
            events: selected
        };
    });
}

function readBodyBytes(inputStream: any, contentLength: number): any {
    const ByteArrayOutputStream = Java.type("java.io.ByteArrayOutputStream");
    const buffer = new ByteArrayOutputStream();
    for (let index = 0; index < contentLength; index += 1) {
        const nextByte = inputStream.read();
        if (nextByte < 0) {
            break;
        }
        buffer.write(nextByte);
    }
    return buffer.toByteArray();
}

function readStreamBytes(inputStream: any): any {
    const ByteArrayOutputStream = Java.type("java.io.ByteArrayOutputStream");
    const ReflectArray = Java.type("java.lang.reflect.Array");
    const ByteType = Java.type("java.lang.Byte");
    const buffer = new ByteArrayOutputStream();
    const chunk = ReflectArray.newInstance(ByteType.TYPE, 4096);

    while (true) {
        const read = inputStream.read(chunk);
        if (read == null || Number(read) < 0) {
            break;
        }
        if (Number(read) === 0) {
            continue;
        }
        buffer.write(chunk, 0, Number(read));
    }

    return buffer.toByteArray();
}

function readHeaderBytes(inputStream: any): any {
    const ByteArrayOutputStream = Java.type("java.io.ByteArrayOutputStream");
    const buffer = new ByteArrayOutputStream();
    let state = 0;

    while (true) {
        const nextByte = inputStream.read();
        if (nextByte < 0) {
            break;
        }
        buffer.write(nextByte);
        if (state === 0 && nextByte === 13) {
            state = 1;
            continue;
        }
        if (state === 1 && nextByte === 10) {
            state = 2;
            continue;
        }
        if (state === 2 && nextByte === 13) {
            state = 3;
            continue;
        }
        if (state === 3 && nextByte === 10) {
            break;
        }
        state = nextByte === 13 ? 1 : 0;
    }

    return buffer.toByteArray();
}

function parseHttpRequest(inputStream: any): {
    method: string;
    path: string;
    headers: Record<string, string>;
    bodyBytes: any;
    bodyText: string;
} {
    const headerBytes = readHeaderBytes(inputStream);
    const headerText = bytesToUtf8String(headerBytes);
    const headerLines = headerText.split(/\r?\n/).filter((line) => line.length > 0);
    if (headerLines.length === 0) {
        throw new Error("Empty HTTP request");
    }

    const requestLine = headerLines[0].trim();
    const requestParts = requestLine.split(/\s+/);
    if (requestParts.length < 2) {
        throw new Error(`Invalid HTTP request line: ${requestLine}`);
    }

    const headers: Record<string, string> = {};
    for (let index = 1; index < headerLines.length; index += 1) {
        const line = headerLines[index];
        const separator = line.indexOf(":");
        if (separator < 0) {
            continue;
        }
        const key = line.slice(0, separator).trim().toLowerCase();
        const value = line.slice(separator + 1).trim();
        headers[key] = value;
    }

    const contentLength = parseNonNegativeInt(headers["content-length"], "content-length", 0);
    const bodyBytes = contentLength > 0 ? readBodyBytes(inputStream, contentLength) : utf8Bytes("");

    return {
        method: requestParts[0].toUpperCase(),
        path: requestParts[1],
        headers,
        bodyBytes,
        bodyText: bytesToUtf8String(bodyBytes)
    };
}

function writeHttpResponse(
    socket: any,
    statusCode: number,
    statusText: string,
    body: string,
    contentType: string
): void {
    const outputStream = socket.getOutputStream();
    const bodyBytes = utf8Bytes(body);
    const headerText =
        `HTTP/1.1 ${statusCode} ${statusText}\r\n` +
        `Content-Type: ${contentType}\r\n` +
        `Content-Length: ${byteArrayLength(bodyBytes)}\r\n` +
        "Connection: close\r\n" +
        "\r\n";
    outputStream.write(utf8Bytes(headerText));
    outputStream.write(bodyBytes);
    outputStream.flush();
}

function buildCallbackPayloadBytes(timestamp: string, bodyBytes: any): any {
    return concatBytes(utf8Bytes(timestamp), bodyBytes);
}

function buildNormalizedEvent(
    payload: Record<string, unknown>,
    bodyText: string,
    remoteAddress: string
) {
    const data = isObject(payload.d) ? payload.d : {};
    const author = isObject(data.author) ? data.author : {};
    const eventType = asText(payload.t).trim();
    const scene =
        eventType === "C2C_MESSAGE_CREATE"
            ? "c2c"
            : eventType === "GROUP_AT_MESSAGE_CREATE"
                ? "group"
                : "unknown";
    const userOpenId = firstNonBlank(
        asText(author.user_openid),
        asText(author.id),
        asText(data.user_openid),
        asText(data.openid)
    );
    const groupOpenId = firstNonBlank(
        asText(data.group_openid),
        asText(data.group_id)
    );
    const messageId = firstNonBlank(asText(data.id), asText(payload.id));
    const receivedAt = new Date().toISOString();

    return {
        scene,
        eventType,
        eventId: asText(payload.id).trim(),
        seq: Number(payload.s == null ? 0 : payload.s),
        messageId,
        content: asText(data.content),
        timestamp: asText(data.timestamp),
        receivedAt,
        userOpenId,
        groupOpenId,
        authorId: asText(author.id),
        remoteAddress,
        rawBody: bodyText,
        rawPayload: payload,
        replyHint: {
            scene,
            msg_id: messageId,
            event_id: asText(payload.id).trim(),
            openid: userOpenId,
            group_openid: groupOpenId
        }
    };
}

function parseQueryParams(rawPath: string): Record<string, string> {
    const URLDecoder = Java.type("java.net.URLDecoder");
    const queryIndex = rawPath.indexOf("?");
    if (queryIndex < 0) {
        return {};
    }
    const query = rawPath.slice(queryIndex + 1).trim();
    if (!query) {
        return {};
    }

    const result: Record<string, string> = {};
    const parts = query.split("&");
    for (let index = 0; index < parts.length; index += 1) {
        const item = parts[index];
        if (!item) {
            continue;
        }
        const separator = item.indexOf("=");
        const rawKey = separator >= 0 ? item.slice(0, separator) : item;
        const rawValue = separator >= 0 ? item.slice(separator + 1) : "";
        const key = String(URLDecoder.decode(rawKey, "UTF-8")).trim();
        const value = String(URLDecoder.decode(rawValue, "UTF-8")).trim();
        if (key) {
            result[key] = value;
        }
    }
    return result;
}

function resolveLocalConnectHost(host: string): string {
    const normalized = host.trim().toLowerCase();
    if (!normalized || normalized === "0.0.0.0" || normalized === "::" || normalized === "[::]") {
        return "127.0.0.1";
    }
    return host.trim();
}

function buildControlBaseUrl(host: string, port: number): string {
    return `http://${resolveLocalConnectHost(host)}:${port}${CONTROL_PATH}`;
}

function generateControlToken(): string {
    const UUID = Java.type("java.util.UUID");
    return String(UUID.randomUUID().toString());
}

function sleepMs(ms: number): void {
    Java.java.lang.Thread.sleep(ms);
}

function isSocketTimeoutError(error: any): boolean {
    const message = safeErrorMessage(error);
    return message.includes("SocketTimeoutException") || message.toLowerCase().includes("timed out");
}

function isSocketClosedError(error: any): boolean {
    const message = safeErrorMessage(error).toLowerCase();
    return message.includes("socket closed") || message.includes("socket is closed");
}

function buildServiceStatePatch(snapshot: QQBotConfigSnapshot, extra?: Record<string, unknown>) {
    return {
        packageVersion: PACKAGE_VERSION,
        callbackHost: snapshot.callbackHost,
        callbackPort: snapshot.callbackPort,
        callbackPath: snapshot.callbackPath,
        publicBaseUrl: snapshot.publicBaseUrl,
        publicCallbackUrl: buildStatus(snapshot).publicCallbackUrl,
        localCallbackUrl: buildStatus(snapshot).localCallbackUrl,
        controlPath: CONTROL_PATH,
        ...extra
    };
}

function readHealthProbe(timeoutMs: number, host: string, port: number): {
    reachable: boolean;
    success: boolean;
    statusCode: number;
    body: string;
    json: Record<string, unknown>;
} {
    const URL = Java.type("java.net.URL");
    const connection = URL(`${buildControlBaseUrl(host, port)}?action=health`).openConnection();
    let inputStream: any = null;
    try {
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setRequestProperty("Accept", "application/json");
        const statusCode = Number(connection.getResponseCode());
        inputStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        const body = inputStream ? bytesToUtf8String(readStreamBytes(inputStream)) : "";
        let json: Record<string, unknown> = {};
        if (body.trim()) {
            try {
                const parsed = JSON.parse(body);
                json = isObject(parsed) ? parsed : {};
            } catch (_error) {
                json = {};
            }
        }
        return {
            reachable: true,
            success: statusCode >= 200 && statusCode < 300,
            statusCode,
            body,
            json
        };
    } catch (error: any) {
        return {
            reachable: false,
            success: false,
            statusCode: 0,
            body: safeErrorMessage(error),
            json: {}
        };
    } finally {
        safeClose(inputStream);
        try {
            connection.disconnect();
        } catch (_error) {
            // Ignore disconnect errors.
        }
    }
}

function requestStopByControlToken(
    host: string,
    port: number,
    controlToken: string,
    timeoutMs: number
): {
    reachable: boolean;
    success: boolean;
    statusCode: number;
    json: Record<string, unknown>;
    body: string;
} {
    const URL = Java.type("java.net.URL");
    const URLEncoder = Java.type("java.net.URLEncoder");
    const encodedToken = String(URLEncoder.encode(controlToken, "UTF-8"));
    const url = `${buildControlBaseUrl(host, port)}?action=stop&token=${encodedToken}`;
    const connection = URL(url).openConnection();
    let inputStream: any = null;

    try {
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setRequestProperty("Accept", "application/json");
        const statusCode = Number(connection.getResponseCode());
        inputStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        const body = inputStream ? bytesToUtf8String(readStreamBytes(inputStream)) : "";
        let json: Record<string, unknown> = {};
        if (body.trim()) {
            try {
                const parsed = JSON.parse(body);
                json = isObject(parsed) ? parsed : {};
            } catch (_error) {
                json = {};
            }
        }
        return {
            reachable: true,
            success: statusCode >= 200 && statusCode < 300,
            statusCode,
            json,
            body
        };
    } catch (error: any) {
        return {
            reachable: false,
            success: false,
            statusCode: 0,
            json: {},
            body: safeErrorMessage(error)
        };
    } finally {
        safeClose(inputStream);
        try {
            connection.disconnect();
        } catch (_error) {
            // Ignore disconnect errors.
        }
    }
}

function probeConfiguredService(timeoutMs: number) {
    const snapshot = readConfigSnapshot();
    return readHealthProbe(timeoutMs, snapshot.callbackHost, snapshot.callbackPort);
}

function buildServiceStatus(timeoutMs: number) {
    const persisted = readPersistedState();
    const queue = buildQueueSummary();
    const snapshot = readConfigSnapshot();
    const health = probeConfiguredService(timeoutMs);

    return {
        healthy: health.reachable && health.success && health.json.ok === true,
        healthStatusCode: health.statusCode,
        stateFilePath: String(getStateFile(STATE_FILE_NAME).getAbsolutePath()),
        queueFilePath: String(getStateFile(QUEUE_FILE_NAME).getAbsolutePath()),
        persisted,
        runtime: {
            threadAlive: !!(serviceRuntime.thread && serviceRuntime.thread.isAlive && serviceRuntime.thread.isAlive()),
            startedAt: serviceRuntime.startedAt || 0,
            lastPacketAt: serviceRuntime.lastPacketAt || 0,
            lastEventAt: serviceRuntime.lastEventAt || 0,
            packetCount: serviceRuntime.packetCount || 0,
            eventCount: serviceRuntime.eventCount || 0,
            lastError: serviceRuntime.lastError || "",
            source: serviceRuntime.source || ""
        },
        queue,
        configuredHost: snapshot.callbackHost,
        configuredPort: snapshot.callbackPort
    };
}

function handleIncomingWebhook(
    snapshot: QQBotConfigSnapshot,
    request: {
        method: string;
        path: string;
        headers: Record<string, string>;
        bodyBytes: any;
        bodyText: string;
    },
    remoteAddress: string
) {
    const requestPath = request.path.split("?")[0].trim();
    if (request.method !== "POST") {
        return {
            statusCode: 405,
            statusText: "Method Not Allowed",
            body: JSON.stringify({ ok: false, error: "Only POST is supported" }),
            contentType: "application/json; charset=utf-8",
            packet: null,
            event: null,
            shouldStopService: false
        };
    }

    if (requestPath !== snapshot.callbackPath) {
        return {
            statusCode: 404,
            statusText: "Not Found",
            body: JSON.stringify({ ok: false, error: `Unexpected path: ${requestPath}` }),
            contentType: "application/json; charset=utf-8",
            packet: null,
            event: null,
            shouldStopService: false
        };
    }

    const signatureHeader = firstNonBlank(
        request.headers["x-signature-ed25519"],
        request.headers["X-Signature-Ed25519"]
    );
    const timestampHeader = firstNonBlank(
        request.headers["x-signature-timestamp"],
        request.headers["X-Signature-Timestamp"]
    );
    if (!signatureHeader || !timestampHeader) {
        return {
            statusCode: 401,
            statusText: "Unauthorized",
            body: JSON.stringify({ ok: false, error: "Missing QQ Bot signature headers" }),
            contentType: "application/json; charset=utf-8",
            packet: null,
            event: null,
            shouldStopService: false
        };
    }

    const signatureOk = verifySignatureHex(
        snapshot.appSecret,
        buildCallbackPayloadBytes(timestampHeader, request.bodyBytes),
        signatureHeader
    );
    if (!signatureOk) {
        return {
            statusCode: 401,
            statusText: "Unauthorized",
            body: JSON.stringify({ ok: false, error: "QQ Bot signature verification failed" }),
            contentType: "application/json; charset=utf-8",
            packet: null,
            event: null,
            shouldStopService: false
        };
    }

    const payload = parseJsonObject(request.bodyText);
    const op = Number(payload.op == null ? -1 : payload.op);

    if (op === OPCODES.callbackValidation) {
        const data = isObject(payload.d) ? payload.d : {};
        const plainToken = asText(data.plain_token).trim();
        const eventTs = firstNonBlank(asText(data.event_ts), timestampHeader);
        const validationBodyBytes = utf8Bytes(plainToken);
        const validationSignature = generateSignatureHex(
            snapshot.appSecret,
            buildCallbackPayloadBytes(eventTs, validationBodyBytes)
        );
        return {
            statusCode: 200,
            statusText: "OK",
            body: JSON.stringify({
                plain_token: plainToken,
                signature: validationSignature
            }),
            contentType: "application/json; charset=utf-8",
            packet: {
                kind: "validation",
                remoteAddress,
                eventTs,
                plainToken
            },
            event: null,
            shouldStopService: false
        };
    }

    if (op === OPCODES.heartbeat) {
        return {
            statusCode: 200,
            statusText: "OK",
            body: JSON.stringify({
                op: OPCODES.heartbeatAck,
                d: payload.d == null ? 0 : payload.d
            }),
            contentType: "application/json; charset=utf-8",
            packet: {
                kind: "heartbeat",
                remoteAddress,
                seq: Number(payload.s == null ? 0 : payload.s)
            },
            event: null,
            shouldStopService: false
        };
    }

    if (op === OPCODES.dispatch) {
        const event = buildNormalizedEvent(payload, request.bodyText, remoteAddress);
        return {
            statusCode: 200,
            statusText: "OK",
            body: JSON.stringify({
                op: OPCODES.callbackAck,
                d: 0
            }),
            contentType: "application/json; charset=utf-8",
            packet: {
                kind: "dispatch",
                remoteAddress,
                eventType: event.eventType,
                eventId: event.eventId
            },
            event,
            shouldStopService: false
        };
    }

    return {
        statusCode: 200,
        statusText: "OK",
        body: "",
        contentType: "text/plain; charset=utf-8",
        packet: {
            kind: "unknown",
            remoteAddress,
            op
        },
        event: null,
        shouldStopService: false
    };
}

function handleControlRequest(
    request: {
        method: string;
        path: string;
    },
    snapshot: QQBotConfigSnapshot,
    queueSummary: Record<string, unknown>
) {
    if (request.method !== "GET") {
        return {
            statusCode: 405,
            statusText: "Method Not Allowed",
            body: JSON.stringify({ ok: false, error: "Control route only supports GET" }),
            contentType: "application/json; charset=utf-8",
            shouldStopService: false
        };
    }

    const query = parseQueryParams(request.path);
    const action = firstNonBlank(query.action, "health");

    if (action === "health") {
        return {
            statusCode: 200,
            statusText: "OK",
            body: JSON.stringify({
                ok: true,
                packageVersion: PACKAGE_VERSION,
                service: {
                    running: true,
                    startedAt: serviceRuntime.startedAt,
                    lastPacketAt: serviceRuntime.lastPacketAt,
                    lastEventAt: serviceRuntime.lastEventAt,
                    packetCount: serviceRuntime.packetCount,
                    eventCount: serviceRuntime.eventCount,
                    callbackHost: snapshot.callbackHost,
                    callbackPort: snapshot.callbackPort,
                    callbackPath: snapshot.callbackPath,
                    publicBaseUrl: snapshot.publicBaseUrl,
                    source: serviceRuntime.source
                },
                queue: queueSummary
            }),
            contentType: "application/json; charset=utf-8",
            shouldStopService: false
        };
    }

    const providedToken = asText(query.token).trim();
    if (!providedToken || providedToken !== serviceRuntime.controlToken) {
        return {
            statusCode: 403,
            statusText: "Forbidden",
            body: JSON.stringify({ ok: false, error: "Invalid control token" }),
            contentType: "application/json; charset=utf-8",
            shouldStopService: false
        };
    }

    if (action === "stop") {
        serviceRuntime.stopRequested = true;
        serviceRuntime.stopReason = "control_stop";
        return {
            statusCode: 200,
            statusText: "OK",
            body: JSON.stringify({
                ok: true,
                stopping: true,
                reason: serviceRuntime.stopReason
            }),
            contentType: "application/json; charset=utf-8",
            shouldStopService: true
        };
    }

    return {
        statusCode: 400,
        statusText: "Bad Request",
        body: JSON.stringify({ ok: false, error: `Unsupported control action: ${action}` }),
        contentType: "application/json; charset=utf-8",
        shouldStopService: false
    };
}

function handleHttpRequest(
    request: {
        method: string;
        path: string;
        headers: Record<string, string>;
        bodyBytes: any;
        bodyText: string;
    },
    remoteAddress: string
) {
    const requestPath = request.path.split("?")[0].trim();
    const snapshot = readConfigSnapshot();
    const queueSummary = buildQueueSummary();

    if (requestPath === CONTROL_PATH) {
        return handleControlRequest(request, snapshot, queueSummary);
    }

    return handleIncomingWebhook(snapshot, request, remoteAddress);
}

function persistServiceRunningState(snapshot: QQBotConfigSnapshot): void {
    updatePersistedState(
        buildServiceStatePatch(snapshot, {
            running: true,
            startedAt: serviceRuntime.startedAt,
            stoppedAt: 0,
            stopReason: "",
            lastError: "",
            lastPacketAt: serviceRuntime.lastPacketAt,
            lastEventAt: serviceRuntime.lastEventAt,
            packetCount: serviceRuntime.packetCount,
            eventCount: serviceRuntime.eventCount,
            controlToken: serviceRuntime.controlToken,
            source: serviceRuntime.source
        })
    );
}

function persistServiceStoppedState(snapshot: QQBotConfigSnapshot, lastError: string): void {
    updatePersistedState(
        buildServiceStatePatch(snapshot, {
            running: false,
            stoppedAt: Date.now(),
            stopReason: serviceRuntime.stopReason,
            lastError,
            lastPacketAt: serviceRuntime.lastPacketAt,
            lastEventAt: serviceRuntime.lastEventAt,
            packetCount: serviceRuntime.packetCount,
            eventCount: serviceRuntime.eventCount,
            controlToken: serviceRuntime.controlToken,
            source: serviceRuntime.source
        })
    );
}

function resetRuntimeHandles(): void {
    serviceRuntime.thread = null;
    serviceRuntime.serverSocket = null;
    serviceRuntime.stopRequested = false;
    serviceRuntime.stopReason = "";
}

function startServiceThread(snapshot: QQBotConfigSnapshot, source: string): void {
    const ServerSocket = Java.type("java.net.ServerSocket");
    const InetSocketAddress = Java.type("java.net.InetSocketAddress");
    const Thread = Java.java.lang.Thread;
    const Runnable = Java.java.lang.Runnable;

    serviceRuntime.startedAt = 0;
    serviceRuntime.lastPacketAt = 0;
    serviceRuntime.lastEventAt = 0;
    serviceRuntime.packetCount = 0;
    serviceRuntime.eventCount = 0;
    serviceRuntime.stopRequested = false;
    serviceRuntime.stopReason = "";
    serviceRuntime.lastError = "";
    serviceRuntime.controlToken = generateControlToken();
    serviceRuntime.callbackHost = snapshot.callbackHost;
    serviceRuntime.callbackPort = snapshot.callbackPort;
    serviceRuntime.callbackPath = snapshot.callbackPath;
    serviceRuntime.source = source;

    const runnable = Java.implement(Runnable, {
        run() {
            let serverSocket: any = null;
            let lastError = "";

            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(snapshot.callbackHost, snapshot.callbackPort));
                serviceRuntime.serverSocket = serverSocket;
                serviceRuntime.startedAt = Date.now();
                persistServiceRunningState(snapshot);

                while (!serviceRuntime.stopRequested) {
                    serverSocket.setSoTimeout(1000);
                    let socket: any = null;

                    try {
                        socket = serverSocket.accept();
                        socket.setSoTimeout(5000);
                        const remoteAddress = firstNonBlank(
                            asText(socket.getInetAddress() && socket.getInetAddress().getHostAddress()),
                            "unknown"
                        );
                        const request = parseHttpRequest(socket.getInputStream());
                        const handled = handleHttpRequest(request, remoteAddress);

                        writeHttpResponse(
                            socket,
                            handled.statusCode,
                            handled.statusText,
                            handled.body,
                            handled.contentType
                        );

                        serviceRuntime.lastPacketAt = Date.now();
                        serviceRuntime.packetCount += 1;

                        if ((handled as any).event) {
                            appendEventToQueue((handled as any).event);
                            serviceRuntime.lastEventAt = Date.now();
                            serviceRuntime.eventCount += 1;
                        }

                        persistServiceRunningState(readConfigSnapshot());

                        if ((handled as any).shouldStopService) {
                            serviceRuntime.stopRequested = true;
                            safeClose(serviceRuntime.serverSocket);
                        }
                    } catch (error: any) {
                        if (isSocketTimeoutError(error)) {
                            continue;
                        }
                        if (serviceRuntime.stopRequested && isSocketClosedError(error)) {
                            break;
                        }
                        throw error;
                    } finally {
                        safeClose(socket);
                    }
                }
            } catch (error: any) {
                lastError = safeErrorMessage(error);
                serviceRuntime.lastError = lastError;
            } finally {
                safeClose(serverSocket);
                safeClose(serviceRuntime.serverSocket);
                serviceRuntime.serverSocket = null;
                persistServiceStoppedState(readConfigSnapshot(), lastError);
                resetRuntimeHandles();
            }
        }
    });

    const worker = new Thread(runnable, `qqbot-webhook-service-${Date.now()}`);
    serviceRuntime.thread = worker;
    worker.start();
}

function waitForHealthyService(timeoutMs: number) {
    const startedAt = Date.now();
    const snapshot = readConfigSnapshot();

    while (Date.now() - startedAt <= timeoutMs) {
        const probe = readHealthProbe(
            Math.min(1000, timeoutMs),
            snapshot.callbackHost,
            snapshot.callbackPort
        );
        if (probe.reachable && probe.success && probe.json.ok === true) {
            return probe;
        }
        if (serviceRuntime.lastError) {
            break;
        }
        sleepMs(SERVICE_POLL_INTERVAL_MS);
    }

    return readHealthProbe(Math.min(1000, timeoutMs), snapshot.callbackHost, snapshot.callbackPort);
}

function waitForServiceStop(timeoutMs: number, host: string, port: number) {
    const startedAt = Date.now();
    while (Date.now() - startedAt <= timeoutMs) {
        const probe = readHealthProbe(Math.min(1000, timeoutMs), host, port);
        if (!probe.reachable || !probe.success || probe.json.ok !== true) {
            return probe;
        }
        sleepMs(SERVICE_POLL_INTERVAL_MS);
    }
    return readHealthProbe(Math.min(1000, timeoutMs), host, port);
}

function stopQQBotServiceInternal(timeoutMs: number) {
    const persisted = readPersistedState();
    const host = firstNonBlank(asText(persisted.callbackHost), readConfigSnapshot().callbackHost, DEFAULT_CALLBACK_HOST);
    const port = parsePort(
        firstNonBlank(asText(persisted.callbackPort), asText(readConfigSnapshot().callbackPort)),
        "callback_port",
        DEFAULT_CALLBACK_PORT
    );
    const controlToken = asText(persisted.controlToken).trim();
    const initialHealth = readHealthProbe(Math.min(1000, timeoutMs), host, port);

    if (!initialHealth.reachable || !initialHealth.success || initialHealth.json.ok !== true) {
        updatePersistedState({
            running: false,
            stoppedAt: Date.now()
        });
        return {
            success: true,
            alreadyStopped: true,
            packageVersion: PACKAGE_VERSION,
            health: initialHealth
        };
    }

    if (!controlToken) {
        throw new Error("QQ Bot service is healthy, but control token is missing from persisted state");
    }

    const stopResponse = requestStopByControlToken(host, port, controlToken, Math.min(1500, timeoutMs));
    if (!stopResponse.reachable || !stopResponse.success || stopResponse.json.ok !== true) {
        throw new Error(
            firstNonBlank(
                asText(stopResponse.json.error),
                stopResponse.body,
                "Failed to stop QQ Bot service"
            )
        );
    }

    const afterStop = waitForServiceStop(timeoutMs, host, port);
    return {
        success: !afterStop.reachable || !afterStop.success || afterStop.json.ok !== true,
        alreadyStopped: false,
        packageVersion: PACKAGE_VERSION,
        stopResponse,
        afterStop
    };
}

function isServiceConfigMatching(
    persisted: Record<string, unknown>,
    snapshot: QQBotConfigSnapshot
): boolean {
    return (
        asText(persisted.callbackHost).trim() === snapshot.callbackHost &&
        Number(persisted.callbackPort == null ? 0 : persisted.callbackPort) === snapshot.callbackPort
    );
}

export function ensureQQBotServiceStarted(options: EnsureQQBotServiceOptions = {}) {
    const timeoutMs = parsePositiveInt(options.timeout_ms, "timeout_ms", DEFAULT_SERVICE_WAIT_MS);
    const source = firstNonBlank(options.source, "manual");
    const allowMissingConfig = parseOptionalBoolean(options.allow_missing_config, "allow_missing_config") === true;
    const shouldRestart = parseOptionalBoolean(options.restart, "restart") === true;
    const snapshot = readConfigSnapshot();

    if ((!snapshot.appId || !snapshot.appSecret) && allowMissingConfig) {
        return {
            ok: true,
            skipped: true,
            reason: "missing_credentials",
            source,
            lifecycleEvent: firstNonBlank(options.lifecycle_event, source),
            status: buildStatus(snapshot)
        };
    }

    requireConfiguredSnapshot();

    const persisted = readPersistedState();
    const health = readHealthProbe(
        Math.min(1000, timeoutMs),
        snapshot.callbackHost,
        snapshot.callbackPort
    );

    if (health.reachable && health.success && health.json.ok === true && !shouldRestart) {
        return {
            ok: true,
            started: false,
            source,
            lifecycleEvent: firstNonBlank(options.lifecycle_event, source),
            status: buildStatus(snapshot),
            service: buildServiceStatus(timeoutMs)
        };
    }

    if ((shouldRestart || !isServiceConfigMatching(persisted, snapshot)) && asText(persisted.controlToken).trim()) {
        try {
            stopQQBotServiceInternal(timeoutMs);
        } catch (error: any) {
            if (shouldRestart || isServiceConfigMatching(persisted, snapshot)) {
                throw error;
            }
        }
    }

    startServiceThread(snapshot, source);
    const afterStart = waitForHealthyService(timeoutMs);
    if (!afterStart.reachable || !afterStart.success || afterStart.json.ok !== true) {
        const startError = serviceRuntime.lastError || asText(afterStart.body).trim();
        throw new Error(firstNonBlank(startError, "QQ Bot service failed to become healthy"));
    }

    return {
        ok: true,
        started: true,
        source,
        lifecycleEvent: firstNonBlank(options.lifecycle_event, source),
        status: buildStatus(snapshot),
        service: buildServiceStatus(timeoutMs)
    };
}

async function requestJson(
    url: string,
    method: "GET" | "POST",
    headers: Record<string, string>,
    body: Record<string, unknown> | null,
    timeoutMs: number
): Promise<{
    success: boolean;
    statusCode: number;
    content: string;
    json: Record<string, unknown>;
    url: string;
}> {
    const timeoutSeconds = toHttpTimeoutSeconds(timeoutMs);
    const response = await Tools.Net.http({
        url,
        method,
        headers,
        body: body || undefined,
        connect_timeout: Math.min(timeoutSeconds, 10),
        read_timeout: timeoutSeconds + 5,
        validateStatus: false
    });

    const content = asText(response.content);
    let parsed: Record<string, unknown> = {};
    try {
        parsed = parseJsonObject(content);
    } catch (_error) {
        parsed = {};
    }

    return {
        success: response.statusCode >= 200 && response.statusCode < 300,
        statusCode: response.statusCode,
        content,
        json: parsed,
        url: asText(response.url)
    };
}

async function fetchAccessToken(
    snapshot: QQBotConfigSnapshot,
    timeoutMs: number
): Promise<QQBotTokenResponse> {
    const result = await requestJson(
        TOKEN_URL,
        "POST",
        {
            Accept: "application/json",
            "Content-Type": "application/json; charset=utf-8"
        },
        {
            appId: snapshot.appId,
            clientSecret: snapshot.appSecret
        },
        timeoutMs
    );

    const accessToken = firstNonBlank(asText(result.json.access_token), asText(result.json.accessToken));
    const expiresIn = parsePositiveInt(
        firstNonBlank(asText(result.json.expires_in), asText(result.json.expiresIn)),
        "expires_in",
        0
    );
    const code = Number(result.json.code == null ? 0 : result.json.code);
    const message = firstNonBlank(asText(result.json.message), result.success ? "" : `HTTP ${result.statusCode}`);

    if (!result.success || code !== 0 || !accessToken) {
        throw new Error(
            firstNonBlank(message, "Failed to retrieve QQ Bot access token")
        );
    }

    return {
        accessToken,
        expiresIn,
        tokenType: "QQBot"
    };
}

async function openApiRequest(
    snapshot: QQBotConfigSnapshot,
    path: string,
    method: "GET" | "POST",
    body: Record<string, unknown> | null,
    timeoutMs: number
) {
    const token = await fetchAccessToken(snapshot, timeoutMs);
    const baseUrl = snapshot.useSandbox ? SANDBOX_API_BASE_URL : API_BASE_URL;
    return requestJson(
        `${baseUrl}${path}`,
        method,
        {
            Accept: "application/json",
            Authorization: `${token.tokenType} ${token.accessToken}`,
            "X-Union-Appid": snapshot.appId,
            ...(body ? { "Content-Type": "application/json; charset=utf-8" } : {})
        },
        body,
        timeoutMs
    );
}

async function qqbot_configure(params: QQBotConfigureParams = {}): Promise<any> {
    try {
        const before = readConfigSnapshot();
        const updatedEnvironmentKeys: string[] = [];
        const configPatch: Record<string, unknown> = {};
        const updatedConfigFields: string[] = [];

        if (hasOwn(params, "app_id")) {
            await writeEnv(ENV_KEYS.appId, asText(params.app_id).trim());
            updatedEnvironmentKeys.push(ENV_KEYS.appId);
        }
        if (hasOwn(params, "app_secret")) {
            await writeEnv(ENV_KEYS.appSecret, asText(params.app_secret).trim());
            updatedEnvironmentKeys.push(ENV_KEYS.appSecret);
        }
        if (hasOwn(params, "use_sandbox")) {
            configPatch.useSandbox = parseOptionalBoolean(params.use_sandbox, "use_sandbox") === true;
            updatedConfigFields.push("useSandbox");
        }
        if (hasOwn(params, "callback_host")) {
            configPatch.callbackHost = firstNonBlank(
                asText(params.callback_host).trim(),
                DEFAULT_CALLBACK_HOST
            );
            updatedConfigFields.push("callbackHost");
        }
        if (hasOwn(params, "callback_port")) {
            configPatch.callbackPort = parsePort(
                params.callback_port,
                "callback_port",
                DEFAULT_CALLBACK_PORT
            );
            updatedConfigFields.push("callbackPort");
        }
        if (hasOwn(params, "callback_path")) {
            configPatch.callbackPath = normalizeCallbackPath(asText(params.callback_path));
            updatedConfigFields.push("callbackPath");
        }
        if (hasOwn(params, "public_base_url")) {
            configPatch.publicBaseUrl = normalizeBaseUrl(asText(params.public_base_url));
            updatedConfigFields.push("publicBaseUrl");
        }

        if (updatedConfigFields.length > 0) {
            updatePersistedConfig(configPatch);
        }

        const after = readConfigSnapshot();
        const status = buildStatus(after);
        const shouldTest = parseOptionalBoolean(params.test_connection, "test_connection") === true;
        const shouldRestart = parseOptionalBoolean(params.restart_service, "restart_service") === true;
        const listenChanged =
            before.callbackHost !== after.callbackHost ||
            before.callbackPort !== after.callbackPort;
        const credentialsReady = !!after.appId && !!after.appSecret;

        let serviceResult: any = null;
        if (!credentialsReady) {
            serviceResult = stopQQBotServiceInternal(DEFAULT_SERVICE_WAIT_MS);
        } else {
            serviceResult = ensureQQBotServiceStarted({
                restart: shouldRestart || listenChanged,
                timeout_ms: DEFAULT_SERVICE_WAIT_MS,
                source: "qqbot_configure"
            });
        }

        const result: Record<string, unknown> = {
            success: true,
            packageVersion: PACKAGE_VERSION,
            updatedEnvironmentKeys,
            updatedConfigFields,
            status,
            service: serviceResult
        };

        if (shouldTest) {
            result.connection = await qqbot_test_connection();
            result.success = !!(result.connection as any).success;
        }

        return result;
    } catch (error: any) {
        return {
            success: false,
            packageVersion: PACKAGE_VERSION,
            error: safeErrorMessage(error)
        };
    }
}

async function qqbot_status(): Promise<any> {
    try {
        const snapshot = readConfigSnapshot();
        return {
            success: true,
            ...buildStatus(snapshot),
            service: buildServiceStatus(1200),
            queue: buildQueueSummary()
        };
    } catch (error: any) {
        return {
            success: false,
            packageVersion: PACKAGE_VERSION,
            error: safeErrorMessage(error)
        };
    }
}

async function qqbot_service_start(params: QQBotServiceStartParams = {}): Promise<any> {
    try {
        return {
            success: true,
            packageVersion: PACKAGE_VERSION,
            ...(ensureQQBotServiceStarted({
                restart: parseOptionalBoolean(params.restart, "restart") === true,
                timeout_ms: parsePositiveInt(params.timeout_ms, "timeout_ms", DEFAULT_SERVICE_WAIT_MS),
                source: "qqbot_service_start"
            }))
        };
    } catch (error: any) {
        return {
            success: false,
            packageVersion: PACKAGE_VERSION,
            error: safeErrorMessage(error)
        };
    }
}

async function qqbot_service_stop(params: QQBotServiceStopParams = {}): Promise<any> {
    try {
        const timeoutMs = parsePositiveInt(params.timeout_ms, "timeout_ms", DEFAULT_SERVICE_WAIT_MS);
        const result = stopQQBotServiceInternal(timeoutMs);
        return {
            ...result,
            service: buildServiceStatus(1200)
        };
    } catch (error: any) {
        return {
            success: false,
            packageVersion: PACKAGE_VERSION,
            error: safeErrorMessage(error)
        };
    }
}

async function qqbot_receive_events(params: QQBotReceiveEventsParams = {}): Promise<any> {
    try {
        const autoStart = parseOptionalBoolean(params.auto_start, "auto_start") !== false;
        if (autoStart) {
            ensureQQBotServiceStarted({
                allow_missing_config: false,
                timeout_ms: DEFAULT_SERVICE_WAIT_MS,
                source: "qqbot_receive_events"
            });
        }

        const result = receiveQueuedEvents(params);
        return {
            success: true,
            packageVersion: PACKAGE_VERSION,
            ...result,
            service: buildServiceStatus(1200)
        };
    } catch (error: any) {
        return {
            success: false,
            packageVersion: PACKAGE_VERSION,
            error: safeErrorMessage(error)
        };
    }
}

async function qqbot_clear_events(): Promise<any> {
    try {
        const cleared = clearQueuedEventsInternal();
        return {
            success: true,
            packageVersion: PACKAGE_VERSION,
            ...cleared,
            queue: buildQueueSummary()
        };
    } catch (error: any) {
        return {
            success: false,
            packageVersion: PACKAGE_VERSION,
            error: safeErrorMessage(error)
        };
    }
}

async function qqbot_test_connection(params: QQBotTestConnectionParams = {}): Promise<any> {
    try {
        const timeoutMs = parsePositiveInt(params.timeout_ms, "timeout_ms", DEFAULT_TIMEOUT_MS);
        const snapshot = requireConfiguredSnapshot();
        const token = await fetchAccessToken(snapshot, timeoutMs);
        const me = await openApiRequest(snapshot, "/users/@me", "GET", null, timeoutMs);

        return {
            success: me.success,
            packageVersion: PACKAGE_VERSION,
            accessTokenType: token.tokenType,
            accessTokenExpiresIn: token.expiresIn,
            httpStatus: me.statusCode,
            profile: me.json,
            status: buildStatus(snapshot),
            error: me.success ? "" : firstNonBlank(asText(me.json.message), `HTTP ${me.statusCode}`)
        };
    } catch (error: any) {
        return {
            success: false,
            packageVersion: PACKAGE_VERSION,
            error: safeErrorMessage(error)
        };
    }
}

function buildSendMessageBody(params: QQBotSendMessageParams): Record<string, unknown> {
    const content = asText(params.content).trim();
    if (!content) {
        throw new Error("Missing param: content");
    }

    const body: Record<string, unknown> = {
        content,
        msg_type: parseMessageType(params.msg_type),
        msg_seq: parseMsgSeq(params.msg_seq)
    };

    const msgId = asText(params.msg_id).trim();
    if (msgId) {
        body.msg_id = msgId;
    }

    const eventId = asText(params.event_id).trim();
    if (eventId) {
        body.event_id = eventId;
    }

    return body;
}

async function qqbot_send_c2c_message(params: QQBotSendC2CMessageParams): Promise<any> {
    try {
        const openid = asText(params.openid).trim();
        if (!openid) {
            throw new Error("Missing param: openid");
        }
        const timeoutMs = parsePositiveInt(params.timeout_ms, "timeout_ms", DEFAULT_TIMEOUT_MS);
        const snapshot = requireConfiguredSnapshot();
        const body = buildSendMessageBody(params);
        const response = await openApiRequest(
            snapshot,
            `/v2/users/${encodeURIComponent(openid)}/messages`,
            "POST",
            body,
            timeoutMs
        );

        return {
            success: response.success,
            packageVersion: PACKAGE_VERSION,
            scene: "c2c",
            openid,
            requestBody: body,
            httpStatus: response.statusCode,
            response: response.json,
            error: response.success
                ? ""
                : firstNonBlank(asText(response.json.message), `HTTP ${response.statusCode}`)
        };
    } catch (error: any) {
        return {
            success: false,
            packageVersion: PACKAGE_VERSION,
            scene: "c2c",
            error: safeErrorMessage(error)
        };
    }
}

async function qqbot_send_group_message(params: QQBotSendGroupMessageParams): Promise<any> {
    try {
        const groupOpenid = asText(params.group_openid).trim();
        if (!groupOpenid) {
            throw new Error("Missing param: group_openid");
        }
        const timeoutMs = parsePositiveInt(params.timeout_ms, "timeout_ms", DEFAULT_TIMEOUT_MS);
        const snapshot = requireConfiguredSnapshot();
        const body = buildSendMessageBody(params);
        const response = await openApiRequest(
            snapshot,
            `/v2/groups/${encodeURIComponent(groupOpenid)}/messages`,
            "POST",
            body,
            timeoutMs
        );

        return {
            success: response.success,
            packageVersion: PACKAGE_VERSION,
            scene: "group",
            groupOpenid,
            requestBody: body,
            httpStatus: response.statusCode,
            response: response.json,
            error: response.success
                ? ""
                : firstNonBlank(asText(response.json.message), `HTTP ${response.statusCode}`)
        };
    } catch (error: any) {
        return {
            success: false,
            packageVersion: PACKAGE_VERSION,
            scene: "group",
            error: safeErrorMessage(error)
        };
    }
}

export {
    qqbot_configure,
    qqbot_status,
    qqbot_service_start,
    qqbot_service_stop,
    qqbot_receive_events,
    qqbot_clear_events,
    qqbot_test_connection,
    qqbot_send_c2c_message,
    qqbot_send_group_message
};
