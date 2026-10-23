"""
NEO AI backend — runs inside Termux on the phone.

Design rules:
  * binds to 127.0.0.1 by default (no LAN exposure)
  * every request needs X-Neo-Token (constant-time compare)
  * API keys live only in the environment / .env file, never in the APK
  * deterministic intent parsing works without OpenAI for device commands
"""

from __future__ import annotations

import asyncio
import base64
import hmac
import json
import logging
import os
import re
import secrets
import time
from collections import deque
from datetime import datetime
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Deque, Dict, List, Optional

import httpx
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parent
load_dotenv(ROOT / ".env")
LOG_DIR = ROOT / "logs"
LOG_DIR.mkdir(exist_ok=True)

handler = RotatingFileHandler(LOG_DIR / "neo.log", maxBytes=512_000, backupCount=2)
handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
log = logging.getLogger("neo")
log.setLevel(logging.INFO)
log.addHandler(handler)
log.addHandler(logging.StreamHandler())

NEO_TOKEN = os.getenv("NEO_TOKEN", "").strip()
if not NEO_TOKEN:
    token_file = ROOT / ".neo_token"
    if token_file.exists():
        NEO_TOKEN = token_file.read_text().strip()
    else:
        NEO_TOKEN = secrets.token_urlsafe(24)
        token_file.write_text(NEO_TOKEN)
        token_file.chmod(0o600)
    log.warning("NEO_TOKEN was empty. Using generated token: %s", NEO_TOKEN)

OPENAI_KEY = os.getenv("OPENAI_API_KEY", "").strip()
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini").strip()
OPENAI_URL = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")

ELEVEN_KEY = os.getenv("ELEVENLABS_API_KEY", "").strip()
ELEVEN_VOICE = os.getenv("ELEVENLABS_VOICE_ID", "").strip()
ELEVEN_MODEL = os.getenv("ELEVENLABS_MODEL", "eleven_multilingual_v2").strip()

DEFAULT_LAT = os.getenv("NEO_DEFAULT_LAT", "28.6139")
DEFAULT_LON = os.getenv("NEO_DEFAULT_LON", "77.2090")
DEFAULT_CITY = os.getenv("NEO_DEFAULT_CITY", "New Delhi")
RATE_PER_MIN = int(os.getenv("NEO_RATE_PER_MIN", "60"))

app = FastAPI(title="NEO AI", version="1.0", docs_url=None, redoc_url=None)


# ----------------------------------------------------------------- utilities

class RateLimiter:
    def __init__(self, limit: int) -> None:
        self.limit = limit
        self.hits: Dict[str, Deque[float]] = {}
        self.lock = asyncio.Lock()

    async def check(self, key: str) -> None:
        now = time.monotonic()
        async with self.lock:
            bucket = self.hits.setdefault(key, deque())
            while bucket and now - bucket[0] > 60:
                bucket.popleft()
            if len(bucket) >= self.limit:
                raise HTTPException(status_code=429, detail="Rate limit. Thoda ruk kar try karein.")
            bucket.append(now)


limiter = RateLimiter(RATE_PER_MIN)


async def auth(
    request: Request,
    x_neo_token: Optional[str] = Header(default=None, alias="X-Neo-Token"),
) -> None:
    if not x_neo_token or not hmac.compare_digest(x_neo_token, NEO_TOKEN):
        log.info("auth rejected from %s", request.client.host if request.client else "?")
        raise HTTPException(status_code=401, detail="Unauthorized: token galat hai.")
    key = x_neo_token[:8] + "|" + (request.client.host if request.client else "local")
    await limiter.check(key)


class ChatIn(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    language: str = Field(default="hi-IN", max_length=16)
    user_name: str = Field(default="", max_length=60)
    device: Dict[str, Any] = Field(default_factory=dict)


class TtsIn(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    language: str = Field(default="hi-IN", max_length=16)


class AckIn(BaseModel):
    intent: str = Field(default="", max_length=60)
    ok: bool = True
    detail: str = Field(default="", max_length=400)


# --------------------------------------------------------------- intent rules

INTENTS_HELP = """
Available device intents (use exactly these names):
open_app {"app": "<app name>"}
open_url {"url": "https://..."}
search {"app": "youtube|instagram|google|whatsapp", "query": "..."}
call {"name": "<contact>"}
whatsapp {"name": "<contact>", "message": "<draft>"}
sms {"name": "<contact>", "message": "<draft>"}
volume {"direction": "up|down|mute|set", "percent": 0-100}
torch {"on": true|false}
settings {"screen": "wifi|bluetooth|battery|accessibility|display|sound|apps|main"}
reminder {"title": "<what>", "when": "<natural time>"}
alarm {"hour": 0-23, "minute": 0-59}
accessibility {"action": "back|home|recents|notifications|click|type|scroll", "text": "<optional>"}
time {}
battery {}
none {}
""".strip()

SYSTEM_PROMPT = """You are NEO, a warm, witty female personal assistant living on the user's Android phone.
You speak natural Hinglish (Hindi + English mixed) by default, in short sentences (max 2 sentences).
You are honest: never claim an action happened unless the device reports it. If the user wants a
consequential action (call, message, delete, publish) the app will ask them to confirm.
Never ask for OTPs, passwords, or private messages. Do not invent contacts.

Reply ONLY with compact JSON:
{"reply": "<what you say>", "intent": "<intent name or none>", "args": { ... }}

Rules:
- Casual talk / questions -> intent "none" and a useful reply.
- Device actions -> pick the intent and extract args from the user's words.
- If a contact or app name is ambiguous or missing, keep intent "none" and ask a short question.
- Instagram does not expose a public in-app search API; for profile requests use open_url with
  https://www.instagram.com/_u/<username>/ or intent "search" with app "instagram" and explain.
- Weather: intent "none"; the app gets live weather separately. You may answer briefly.

""" + INTENTS_HELP

KNOWN_APPS = [
    "youtube", "instagram", "whatsapp", "telegram", "facebook", "chrome", "spotify",
    "settings", "camera", "gallery", "photos", "maps", "gmail", "phone", "calendar",
    "clock", "snapchat", "twitter", "x", "netflix", "paytm", "phonepe", "zomato",
]


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower().strip())


def rule_intent(message: str) -> Optional[Dict[str, Any]]:
    t = normalize(message)
    t = re.sub(r"^(hey |ok |okay )?neo[, ]+", "", t)

    if re.search(r"\b(torch|flashlight|flash)\b", t):
        return {"intent": "torch", "args": {"on": not re.search(r"\b(off|band|bujha|बंद)\b", t)}}

    if re.search(r"\b(volume|awaaz|aawaz|sound)\b", t):
        if re.search(r"\bmute\b", t):
            return {"intent": "volume", "args": {"direction": "mute"}}
        if re.search(r"\b(badha|badhao|up|increase|tez)\b", t):
            return {"intent": "volume", "args": {"direction": "up"}}
        if re.search(r"\b(kam|down|decrease|dheere)\b", t):
            return {"intent": "volume", "args": {"direction": "down"}}
        m = re.search(r"(\d{1,3})\s*%?", t)
        if m:
            return {"intent": "volume", "args": {"direction": "set", "percent": int(m.group(1))}}

    if re.search(r"\b(weather|mausam|garmi|barish|temperature)\b", t):
        return {"intent": "weather", "args": {}}

    if re.search(r"\b(samay|time|kya baja)\b", t) and "time" not in ("",):
        return {"intent": "time", "args": {}}

    if re.search(r"\b(battery|charge)\b", t):
        return {"intent": "battery", "args": {}}

    if re.search(r"\b(reminder|yaad dila)\b", t):
        title = re.sub(r".*(reminder|yaad dila(?:o|ye)?)\s*(set\s*karke?\s*do)?[:\-]?\s*", "", t)
        return {"intent": "reminder", "args": {"title": title.strip() or "NEO reminder", "when": t}}

    m = re.search(r"\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|baje|bje)\b", t)
    if m and re.search(r"\b(alarm|utha|jaga)\b", t):
        hour = int(m.group(1))
        minute = int(m.group(2) or 0)
        if m.group(3) == "pm" and hour < 12:
            hour += 12
        return {"intent": "alarm", "args": {"hour": hour % 24, "minute": minute}}

    m = re.search(r"instagram (?:par|pe|me[n]?)\s+(.+?)\s+(?:ki|ka)\s+profile", t)
    if m:
        user = m.group(1).strip().replace(" ", "")
        return {"intent": "open_url", "args": {"url": f"https://www.instagram.com/_u/{user}/"}}

    m = re.search(r"youtube (?:par|pe|me[n]?)\s+(.+?)\s+(?:search|dhoondo|dhoondh|chalao|play)", t)
    if m:
        return {"intent": "search", "args": {"app": "youtube", "query": m.group(1).strip()}}

    m = re.search(r"instagram (?:par|pe|me[n]?)\s+(.+?)\s+(?:search|dhoondo|dhoondh)", t)
    if m:
        return {"intent": "search", "args": {"app": "instagram", "query": m.group(1).strip()}}

    m = re.search(r"^(.+?)\s+ko\s+(?:call|phone)\s*(?:karo|kar|lagao|laga)?", t)
    if m:
        return {"intent": "call", "args": {"name": m.group(1).strip()}}

    m = re.search(r"^(.+?)\s+ko\s+whatsapp\s+(?:par\s+)?(?:message|msg|sandesh)?\s*(?:likho|bhejo|kar do|karo)?[:\-]?\s*(.*)$", t)
    if m:
        return {
            "intent": "whatsapp",
            "args": {"name": m.group(1).strip(), "message": (m.group(2) or "").strip()},
        }

    m = re.search(r"^(.+?)\s+ko\s+(?:sms|message)\s+(?:likho|bhejo|karo)?[:\-]?\s*(.*)$", t)
    if m:
        return {"intent": "sms", "args": {"name": m.group(1).strip(), "message": (m.group(2) or "").strip()}}

    if re.search(r"\b(accessibility|automation)\b", t):
        if re.search(r"\bback\b", t):
            return {"intent": "accessibility", "args": {"action": "back"}}
        if re.search(r"\bhome\b", t):
            return {"intent": "accessibility", "args": {"action": "home"}}
        if re.search(r"\b(scroll|neeche|upar)\b", t):
            return {"intent": "accessibility", "args": {"action": "scroll"}}

    if re.search(r"\bsettings?\b", t) and re.search(r"\b(kholo|khol|open)\b", t):
        screen = "main"
        for key in ("wifi", "bluetooth", "battery", "accessibility", "display", "sound"):
            if key in t:
                screen = key
                break
        return {"intent": "settings", "args": {"screen": screen}}

    if re.search(r"\b(kholo|khol|open|launch)\b", t):
        for app in KNOWN_APPS:
            if re.search(rf"\b{re.escape(app)}\b", t):
                return {"intent": "open_app", "args": {"app": app}}
        m = re.search(r"(.+?)\s+(?:kholo|khol|open|launch)", t)
        if m:
            return {"intent": "open_app", "args": {"app": m.group(1).strip()}}

    return None


CANNED = [
    (r"\b(tum kaun|who are you|tumhara naam)\b", "Main NEO hoon — aapki phone ke andar rehne wali assistant. Boliye kya karna hai?"),
    (r"\b(thank|shukriya|dhanyavad)\b", "Koi baat nahi! Aur kuch chahiye to bataiye."),
    (r"\b(hello|hi|namaste|hey)\b", "Namaste! Main sun rahi hoon."),
    (r"\b(time|samay)\b", "Abhi ka time phone par dekhti hoon — bas ek second."),
]


def rule_reply(message: str) -> Dict[str, Any]:
    found = rule_intent(message)
    if found:
        intent = found["intent"]
        if intent == "weather":
            return {"reply": "Weather nikal rahi hoon…", "intent": "weather", "args": {}}
        return {"reply": "", "intent": intent, "args": found.get("args", {})}

    t = normalize(message)
    for pattern, reply in CANNED:
        if re.search(pattern, t):
            return {"reply": reply, "intent": "none", "args": {}}

    if re.search(r"\b(kaise ho|how are you)\b", t):
        return {"reply": "Main bilkul mast hoon! Aap kaise ho?", "intent": "none", "args": {}}

    return {
        "reply": "Iske liye AI brain chahiye. Termux .env me OPENAI_API_KEY daal kar server restart karein — device commands abhi bhi chalenge.",
        "intent": "none",
        "args": {},
    }


# ------------------------------------------------------------------ OpenAI

async def openai_chat(message: str, language: str, user_name: str, device: Dict[str, Any]) -> Dict[str, Any]:
    payload = {
        "model": OPENAI_MODEL,
        "temperature": 0.6,
        "max_tokens": 320,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "user": user_name or "user",
                        "language": language,
                        "device": device,
                        "message": message,
                    },
                    ensure_ascii=False,
                ),
            },
        ],
    }
    headers = {"Authorization": f"Bearer {OPENAI_KEY}", "Content-Type": "application/json"}
    async with httpx.AsyncClient(timeout=40) as client:
        res = await client.post(f"{OPENAI_URL}/chat/completions", json=payload, headers=headers)
    if res.status_code >= 400:
        detail = res.text[:300]
        log.warning("openai %s: %s", res.status_code, detail)
        raise HTTPException(status_code=502, detail="OpenAI se jawab nahi mila.")
    data = res.json()
    content = data["choices"][0]["message"]["content"].strip()
    content = re.sub(r"^```(?:json)?|```$", "", content, flags=re.MULTILINE).strip()
    try:
        parsed = json.loads(content)
    except json.JSONDecodeError:
        return {"reply": content[:600], "intent": "none", "args": {}}
    intent = str(parsed.get("intent") or "none")
    args = parsed.get("args") if isinstance(parsed.get("args"), dict) else {}
    reply = str(parsed.get("reply") or "").strip()
    if intent == "weather":
        intent, args = "weather", {}
    if intent not in {
        "none", "open_app", "open_url", "search", "call", "whatsapp", "sms", "volume",
        "torch", "settings", "reminder", "alarm", "accessibility", "time", "battery", "weather",
    }:
        intent = "none"
    return {"reply": reply, "intent": intent, "args": args}


async def weather_text(lat: float, lon: float, lang: str) -> str:
    url = "https://api.open-meteo.com/v1/forecast"
    params = {
        "latitude": lat,
        "longitude": lon,
        "current": "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m",
        "timezone": "auto",
    }
    async with httpx.AsyncClient(timeout=15) as client:
        res = await client.get(url, params=params)
    if res.status_code >= 400:
        raise HTTPException(status_code=502, detail="Weather service unavailable")
    cur = res.json().get("current", {})
    temp = cur.get("temperature_2m")
    feels = cur.get("apparent_temperature")
    hum = cur.get("relative_humidity_2m")
    wind = cur.get("wind_speed_10m")
    code = int(cur.get("weather_code") or 0)
    label = WEATHER_CODES.get(code, "mausam")
    if lang.startswith("en"):
        return f"It's {temp}°C and {label.lower()}, feels like {feels}°C. Humidity {hum}%, wind {wind} km/h."
    return f"Abhi {temp}°C hai, {label}. Feels like {feels}°C, humidity {hum}% aur hawa {wind} km/h."


WEATHER_CODES = {
    0: "saaf aasman", 1: "mostly clear", 2: "thodi badal", 3: "overcast",
    45: "dhundh", 48: "freezing fog", 51: "halki drizzling", 53: "drizzling",
    55: "tez drizzling", 61: "halki barish", 63: "barish", 65: "tez barish",
    71: "halki barf", 73: "barf", 75: "tez barf", 80: "barish ke jhonke",
    81: "barish", 82: "bhaari barish", 95: "thunderstorm", 96: "thunderstorm with hail",
    99: "bhaari thunderstorm",
}


# ------------------------------------------------------------------ routes

@app.get("/health")
async def health(_: None = Depends(auth)) -> Dict[str, Any]:
    return {
        "ok": True,
        "openai": bool(OPENAI_KEY),
        "elevenlabs": bool(ELEVEN_KEY and ELEVEN_VOICE),
        "model": OPENAI_MODEL,
        "time": datetime.now().isoformat(timespec="seconds"),
    }


@app.post("/chat")
async def chat(body: ChatIn, _: None = Depends(auth)) -> Dict[str, Any]:
    message = body.message.strip()
    if not message:
        raise HTTPException(status_code=422, detail="Message khali hai.")

    if OPENAI_KEY:
        try:
            result = await openai_chat(message, body.language, body.user_name, body.device)
        except HTTPException:
            result = rule_reply(message)
        except Exception as exc:  # network, quota, parse
            log.warning("openai failure: %s", exc)
            result = rule_reply(message)
    else:
        result = rule_reply(message)

    if result.get("intent") == "weather":
        lat = body.device.get("lat")
        lon = body.device.get("lon")
        try:
            text = await weather_text(
                float(lat) if lat is not None else float(DEFAULT_LAT),
                float(lon) if lon is not None else float(DEFAULT_LON),
                body.language,
            )
        except Exception:
            text = "Weather service se connect nahi ho paya."
        result = {"reply": text, "intent": "none", "args": {}}

    log.info("chat intent=%s ok=%s", result.get("intent"), bool(result.get("reply")))
    return result


@app.post("/chat/ack")
async def chat_ack(body: AckIn, _: None = Depends(auth)) -> Dict[str, bool]:
    log.info("ack intent=%s ok=%s detail=%s", body.intent, body.ok, body.detail[:120])
    return {"received": True}


@app.get("/weather")
async def weather(
    lang: str = "hi-IN",
    lat: Optional[float] = None,
    lon: Optional[float] = None,
    _: None = Depends(auth),
) -> Dict[str, str]:
    try:
        text = await weather_text(
            lat if lat is not None else float(DEFAULT_LAT),
            lon if lon is not None else float(DEFAULT_LON),
            lang,
        )
    except Exception:
        text = "Weather service se connect nahi ho paya."
    return {"text": text}


@app.post("/tts")
async def tts(body: TtsIn, _: None = Depends(auth)) -> Response:
    if not (ELEVEN_KEY and ELEVEN_VOICE):
        raise HTTPException(status_code=503, detail="ElevenLabs configure nahi hai")
    text = body.text.strip()
    if not text:
        raise HTTPException(status_code=422, detail="Text khali hai")
    if len(text) > 1200:
        text = text[:1200]
    url = f"https://api.elevenlabs.io/v1/text-to-speech/{ELEVEN_VOICE}"
    payload = {
        "text": text,
        "model_id": ELEVEN_MODEL,
        "voice_settings": {"stability": 0.45, "similarity_boost": 0.78, "style": 0.25},
    }
    headers = {
        "xi-api-key": ELEVEN_KEY,
        "Content-Type": "application/json",
        "Accept": "audio/mpeg",
    }
    async with httpx.AsyncClient(timeout=45) as client:
        res = await client.post(url, json=payload, headers=headers)
    if res.status_code >= 400:
        log.warning("elevenlabs %s: %s", res.status_code, res.text[:200])
        raise HTTPException(status_code=502, detail="TTS fail hua — phone voice use karein.")
    audio = res.content
    if not audio:
        raise HTTPException(status_code=502, detail="TTS ne audio nahi diya")
    return Response(content=audio, media_type="audio/mpeg", headers={"Cache-Control": "no-store"})


@app.post("/transcribe")
async def transcribe(request: Request, _: None = Depends(auth)) -> Dict[str, str]:
    """Optional server-side STT. The app uses on-device recognition by default."""
    if not OPENAI_KEY:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY missing")
    form = await request.form()
    upload = form.get("file")
    if upload is None or not hasattr(upload, "read"):
        raise HTTPException(status_code=422, detail="file field chahiye (audio/m4a)")
    raw = await upload.read()
    if len(raw) > 8_000_000:
        raise HTTPException(status_code=413, detail="Audio bahut bada hai")
    async with httpx.AsyncClient(timeout=60) as client:
        res = await client.post(
            f"{OPENAI_URL}/audio/transcriptions",
            headers={"Authorization": f"Bearer {OPENAI_KEY}"},
            files={"file": ("voice.m4a", raw, "audio/m4a")},
            data={"model": "whisper-1"},
        )
    if res.status_code >= 400:
        raise HTTPException(status_code=502, detail="Transcription fail hui")
    return {"text": res.json().get("text", "")}


@app.exception_handler(HTTPException)
async def http_error(_: Request, exc: HTTPException) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})


@app.on_event("startup")
async def startup() -> None:
    log.info("NEO backend ready. openai=%s elevenlabs=%s", bool(OPENAI_KEY), bool(ELEVEN_KEY and ELEVEN_VOICE))


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host=os.getenv("NEO_HOST", "127.0.0.1"),
        port=int(os.getenv("NEO_PORT", "8787")),
        log_level="info",
    )
