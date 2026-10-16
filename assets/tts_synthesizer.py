#!/usr/bin/env python3
import sys
import ssl
import asyncio
import edge_tts
import edge_tts.communicate

# Configure SSL context to bypass TLS certificate validation inside Android sandbox
try:
    ssl_ctx = ssl.create_default_context()
    ssl_ctx.check_hostname = False
    ssl_ctx.verify_mode = ssl.CERT_NONE
    edge_tts.communicate._SSL_CTX = ssl_ctx
except Exception:
    pass

async def synthesize(text: str, output_path: str, voice: str, rate: str, pitch: str):
    # Primary attempt with requested male voice (default: bn-BD-PradeepNeural)
    try:
        communicate = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
        await communicate.save(output_path)
        return
    except Exception as primary_err:
        sys.stderr.write(f"Primary voice ({voice}) warning: {primary_err}\n")

    # High-reliability fallback to secondary Bengali male voice (bn-IN-BashkarNeural)
    fallback_voice = "bn-IN-BashkarNeural" if voice != "bn-IN-BashkarNeural" else "bn-BD-PradeepNeural"
    communicate = edge_tts.Communicate(text, fallback_voice, rate=rate, pitch=pitch)
    await communicate.save(output_path)

if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(1)
    text = sys.argv[1]
    out_file = sys.argv[2]
    voice = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3].strip() else "bn-BD-PradeepNeural"
    rate = sys.argv[4] if len(sys.argv) > 4 and sys.argv[4].strip() else "+3%"
    pitch = sys.argv[5] if len(sys.argv) > 5 and sys.argv[5].strip() else "-4Hz"
    try:
        asyncio.run(synthesize(text, out_file, voice, rate, pitch))
    except Exception as e:
        sys.stderr.write(f"TTS Error: {e}\n")
        sys.exit(2)
