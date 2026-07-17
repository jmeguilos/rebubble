/**
 * Rebubble fixture BlueBubbles server — REST + socket.io on one port.
 *
 * Fixture reuse (shapes from app/src/test/resources/fixtures/):
 * - server-info.json → GET /api/v1/server/info fields (version overridden to 1.9.9-fixture)
 * - chat-query-page.json → chat/handle field shapes (guids/names adapted for Maestro)
 * - message-page.json / sent-message-with-tempguid.json → MessageDto field set
 * - socket-new-message.json → new-message payload includes chats[]
 * - error-envelope.json → error { type, message } shape for 4xx/5xx
 */
"use strict";

const path = require("path");
const http = require("http");
const express = require("express");
const multer = require("multer");
const { Server } = require("socket.io");

const PORT = Number(process.env.PORT || 12346);
const PASSWORD = process.env.FIXTURE_PASSWORD || "fixture-pass";
/** One terminal 400 so UI shows tap-to-retry immediately (no WorkManager 5xx backoff). */
const FAIL_SEND_BUDGET = process.env.FAIL_FIRST_SEND === "1" ? 1 : 0;

const DM_GUID = "iMessage;-;+15550100001";
const GROUP_GUID = "iMessage;+;chatfixturefriends";

const app = express();
const upload = multer({ storage: multer.memoryStorage() });
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: "*" } });

let nextRowId = 100;
let nextMsgSeq = 1;
let nextAttSeq = 1;
let sendFailRemaining = FAIL_SEND_BUDGET;
/** @type {Map<string, object>} */
const messagesByGuid = new Map();
/** @type {object[]} ordered by originalROWID ascending */
let messages = [];

function envelope(status, message, data = null, metadata = undefined, error = undefined) {
  const body = { status, message };
  if (data !== undefined) body.data = data;
  if (metadata !== undefined) body.metadata = metadata;
  if (error !== undefined) body.error = error;
  return body;
}

function ok(data, message = "Success", metadata) {
  return envelope(200, message, data, metadata);
}

function err(status, message, type, detail) {
  return envelope(status, message, null, undefined, { type, message: detail });
}

function requireGuid(req, res, next) {
  const guid = req.query.guid;
  if (guid !== PASSWORD) {
    return res.status(401).json(
      err(401, "Unauthorized", "Authentication Error", "Invalid or missing guid password"),
    );
  }
  next();
}

const CHATS = [
  {
    originalROWID: 12,
    guid: DM_GUID,
    style: 45,
    chatIdentifier: "+15550100001",
    displayName: null,
    isArchived: false,
    participants: [{ originalROWID: 3, address: "+15550100001", service: "iMessage" }],
  },
  {
    originalROWID: 13,
    guid: GROUP_GUID,
    style: 43,
    chatIdentifier: "chatfixturefriends",
    displayName: "Fixture Friends",
    isArchived: false,
    participants: [
      { originalROWID: 4, address: "+15557654321", service: "iMessage" },
      { originalROWID: 5, address: "friend@example.com", service: "iMessage" },
    ],
  },
];

function lastMessageFor(chatGuid) {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].chats?.[0]?.guid === chatGuid) return messages[i];
  }
  return null;
}

function chatSeed() {
  return CHATS.map((c) => ({ ...c, lastMessage: lastMessageFor(c.guid) }));
}

function makeMessage({ text, chatGuid, isFromMe, tempGuid, attachments }) {
  const originalROWID = nextRowId++;
  const guid = `fixture-${nextMsgSeq++}`;
  const chat = CHATS.find((c) => c.guid === chatGuid) || CHATS[0];
  const handle = isFromMe
    ? null
    : chat.participants[0]
      ? { ...chat.participants[0] }
      : { originalROWID: 3, address: "+15550100001", service: "iMessage" };
  const dto = {
    originalROWID,
    tempGuid: tempGuid || null,
    guid,
    text: text ?? null,
    subject: null,
    error: 0,
    dateCreated: Date.now(),
    dateRead: null,
    dateDelivered: isFromMe ? null : Date.now(),
    isFromMe: !!isFromMe,
    handle,
    handleId: handle?.originalROWID ?? 0,
    attachments: attachments || [],
    itemType: 0,
    groupTitle: null,
    groupActionType: 0,
    associatedMessageGuid: null,
    associatedMessageType: null,
    threadOriginatorGuid: null,
    threadOriginatorPart: null,
    expressiveSendStyleId: null,
    dateEdited: null,
    dateRetracted: null,
    chats: [
      {
        guid: chat.guid,
        style: chat.style,
        chatIdentifier: chat.chatIdentifier,
        displayName: chat.displayName,
        participants: [],
        isArchived: false,
      },
    ],
  };
  messages.push(dto);
  messagesByGuid.set(guid, dto);
  return dto;
}

function seedInitial() {
  messages = [];
  messagesByGuid.clear();
  nextRowId = 100;
  nextMsgSeq = 1;
  nextAttSeq = 1;
  sendFailRemaining = FAIL_SEND_BUDGET;
  // Seeded history (message-page.json field shapes); watermark init uses max ROWID.
  makeMessage({
    text: "Hey from the fixture DM",
    chatGuid: DM_GUID,
    isFromMe: false,
  });
  makeMessage({
    text: "Welcome to Fixture Friends",
    chatGuid: GROUP_GUID,
    isFromMe: false,
  });
}

function parseRowIdWatermark(body) {
  const where = body?.where || [];
  for (const clause of where) {
    const stmt = String(clause.statement || "");
    if (/ROWID\s*>/i.test(stmt) && clause.args && clause.args.rowid != null) {
      return Number(clause.args.rowid);
    }
  }
  return null;
}

function messagesForChat(chatGuid) {
  return messages.filter((m) => m.chats?.[0]?.guid === chatGuid);
}

// --- REST (auth on /api/* only; /_fixture/* is open for Maestro/scripts) ---
app.use(express.json({ limit: "2mb" }));

app.get("/api/v1/ping", requireGuid, (_req, res) => {
  res.json(ok("pong", "Success"));
});

app.get("/api/v1/server/info", requireGuid, (_req, res) => {
  res.json(
    ok({
      computer_id: "F0E1D2C3-B4A5-4678-9012-ABCDEF012345",
      os_version: "14.5",
      server_version: "1.9.9-fixture",
      private_api: true,
      helper_connected: true,
      proxy_service: "dynamic-dns",
      detected_icloud: "fixture@icloud.com",
      detected_imessage: "fixture@icloud.com",
      macos_time_sync: 0,
      local_ipv4s: ["10.0.2.2"],
      local_ipv6s: [],
    }),
  );
});

app.post("/api/v1/chat/query", requireGuid, (req, res) => {
  const chats = chatSeed();
  const offset = Number(req.body?.offset || 0);
  const limit = Number(req.body?.limit || 1000);
  const page = chats.slice(offset, offset + limit);
  res.json(
    ok(page, "Success", {
      count: page.length,
      total: chats.length,
      offset,
      limit,
    }),
  );
});

app.post("/api/v1/message/query", requireGuid, (req, res) => {
  const watermark = parseRowIdWatermark(req.body);
  const sort = String(req.body?.sort || "ASC").toUpperCase();
  const limit = Number(req.body?.limit || 1000);
  let list = [...messages];
  if (watermark != null && !Number.isNaN(watermark)) {
    list = list.filter((m) => m.originalROWID > watermark);
  }
  list.sort((a, b) =>
    sort === "DESC" ? b.originalROWID - a.originalROWID : a.originalROWID - b.originalROWID,
  );
  const page = list.slice(0, limit);
  res.json(
    ok(page, "Successfully fetched messages!", {
      offset: 0,
      limit,
      total: list.length,
      count: page.length,
    }),
  );
});

app.get("/api/v1/chat/:guid/message", requireGuid, (req, res) => {
  const chatGuid = decodeURIComponent(req.params.guid);
  const before = req.query.before != null ? Number(req.query.before) : null;
  const limit = Number(req.query.limit || 50);
  let list = messagesForChat(chatGuid).sort((a, b) => b.dateCreated - a.dateCreated);
  if (before != null && !Number.isNaN(before)) {
    list = list.filter((m) => (m.dateCreated || 0) < before);
  }
  const page = list.slice(0, limit);
  res.json(
    ok(page, "Successfully fetched messages!", {
      offset: 0,
      limit,
      total: list.length,
      count: page.length,
    }),
  );
});

app.post("/api/v1/message/text", requireGuid, (req, res) => {
  if (sendFailRemaining > 0) {
    sendFailRemaining -= 1;
    return res.status(400).json(
      err(400, "You've made a bad request! Please check your request params & body", "Validation Error", "Fixture-injected send failure"),
    );
  }
  const { chatGuid, tempGuid, message } = req.body || {};
  if (!chatGuid || message == null) {
    return res.status(400).json(
      err(400, "You've made a bad request! Please check your request params & body", "Validation Error", "chatGuid and message required"),
    );
  }
  const dto = makeMessage({
    text: String(message),
    chatGuid: String(chatGuid),
    isFromMe: true,
    tempGuid: tempGuid || null,
  });
  res.json(ok(dto, "Message sent!"));
});

app.post("/api/v1/message/attachment", requireGuid, upload.single("attachment"), (req, res) => {
  const chatGuid = req.body?.chatGuid;
  const tempGuid = req.body?.tempGuid;
  const name = req.body?.name || req.file?.originalname || "attachment.png";
  if (!chatGuid) {
    return res.status(400).json(
      err(400, "You've made a bad request! Please check your request params & body", "Validation Error", "chatGuid required"),
    );
  }
  const attGuid = `fixture-att-${nextAttSeq++}`;
  const attachments = [
    {
      originalROWID: nextAttSeq + 80,
      guid: attGuid,
      uti: "public.png",
      mimeType: req.file?.mimetype || "image/png",
      transferName: name,
      totalBytes: req.file?.size || 70,
      height: 1,
      width: 1,
      hasLivePhoto: false,
    },
  ];
  const dto = makeMessage({
    text: null,
    chatGuid: String(chatGuid),
    isFromMe: true,
    tempGuid: tempGuid || null,
    attachments,
  });
  res.json(ok(dto, "Message sent!"));
});

app.get("/api/v1/attachment/:guid/download", requireGuid, (_req, res) => {
  res.sendFile(path.join(__dirname, "fixture-pixel.png"));
});

app.get("/api/v1/fcm/client", requireGuid, (_req, res) => {
  res.status(404).json(
    err(404, "Not Found", "Not Found", "Fixture server has no Firebase client config"),
  );
});

app.post("/api/v1/fcm/device", requireGuid, (_req, res) => {
  res.json(ok(null, "Success"));
});

app.get("/api/v1/contact", requireGuid, (_req, res) => {
  res.json(ok([], "Success"));
});

// --- Scenario controls (no guid auth) ---
app.post("/_fixture/receive", (req, res) => {
  const chatGuid = req.body?.chatGuid || DM_GUID;
  const text = req.body?.text || "Fixture incoming ping";
  const dto = makeMessage({ text, chatGuid, isFromMe: false });
  io.emit("new-message", dto);
  res.json(ok(dto, "Emitted new-message"));
});

app.post("/_fixture/reset", (_req, res) => {
  seedInitial();
  res.json(ok({ nextRowId, messageCount: messages.length }, "Fixture reset"));
});

// --- socket.io ---
io.use((socket, next) => {
  const guid = socket.handshake.query?.guid;
  if (guid !== PASSWORD) {
    return next(new Error("unauthorized"));
  }
  next();
});

io.on("connection", (socket) => {
  socket.emit("connected", true);
});

seedInitial();
server.listen(PORT, "0.0.0.0", () => {
  console.log(`Rebubble fixture server on http://0.0.0.0:${PORT} (password=${PASSWORD})`);
  if (FAIL_SEND_BUDGET > 0) {
    console.log(`FAIL_FIRST_SEND: first /message/text → 400 (Validation Error), then succeed`);
  }
});
