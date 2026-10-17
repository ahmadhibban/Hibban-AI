#!/usr/bin/env python3
import sys
import ssl
import re
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

def normalize_bengali_speech_text(text: str) -> str:
    if not text:
        return ""
    
    # Remove markdown code blocks & inline code backticks
    text = re.sub(r"```[\s\S]*?```", " ", text)
    text = re.sub(r"`([^`]+)`", r"\1", text)
    # Remove markdown symbols and punctuation that break pronunciation
    text = re.sub(r"[*#_~>\[\]\(\)\{\}\^|\\/\"'“”‘’—–-]", " ", text)

    # Phonetic normalization for common English loan words & tech terms
    # so the Bengali neural TTS reads them with natural native Bengali pronunciation
    # Phonetic normalization for English loan words, tech terms & common expressions
    # so the Bengali neural TTS reads them with natural native Bengali pronunciation
    replacements = [
        (r"\bA\.?I\.?\b", "এআই"),
        (r"\bAPK\b", "এপিকে"),
        (r"\bUI\b", "ইউআই"),
        (r"\bAPI\b", "এপিআই"),
        (r"\bURL\b", "ইউআরএল"),
        (r"\bID\b", "আইডি"),
        (r"\bOK(ay)?\b", "ঠিক আছে"),
        (r"\bWi-?Fi\b", "ওয়াইফাই"),
        (r"\bBluetooth\b", "ব্লুটুথ"),
        (r"\bHotspot\b", "হটস্পট"),
        (r"\bApps?\b", "অ্যাপ"),
        (r"\bSettings\b", "সেটিংস"),
        (r"\bPhones?\b", "ফোন"),
        (r"\bMobiles?\b", "মোবাইল"),
        (r"\bDevices?\b", "ডিভাইস"),
        (r"\bScreens?\b", "স্ক্রিন"),
        (r"\bDisplays?\b", "ডিসপ্লে"),
        (r"\bFiles?\b", "ফাইল"),
        (r"\bFolders?\b", "ফোল্ডার"),
        (r"\bAndroid\b", "অ্যান্ড্রয়েড"),
        (r"\bGoogle\b", "গুগল"),
        (r"\bYouTube\b", "ইউটিউব"),
        (r"\bFacebook\b", "ফেসবুক"),
        (r"\bWhatsApp\b", "হোয়াটসঅ্যাপ"),
        (r"\bMessenger\b", "মেসেঞ্জার"),
        (r"\bChrome\b", "ক্রোম"),
        (r"\bBrowsers?\b", "ব্রাউজার"),
        (r"\bInternets?\b", "ইন্টারনেট"),
        (r"\bOnline\b", "অনলাইন"),
        (r"\bOffline\b", "অফলাইন"),
        (r"\bDownloads?\b", "ডাউনলোড"),
        (r"\bDownloading\b", "ডাউনলোড করা"),
        (r"\bUploads?\b", "আপলোড"),
        (r"\bInstalls?\b", "ইনস্টল"),
        (r"\bUninstalls?\b", "আনইনস্টল"),
        (r"\bUpdates?\b", "আপডেট"),
        (r"\bDeletes?\b", "ডিলিট"),
        (r"\bCopies?\b", "কপি"),
        (r"\bPastes?\b", "পেস্ট"),
        (r"\bOpens?\b", "ওপেন"),
        (r"\bCloses?\b", "ক্লোজ"),
        (r"\bStarts?\b", "স্টার্ট"),
        (r"\bStops?\b", "স্টপ"),
        (r"\bPlays?\b", "প্লে"),
        (r"\bPauses?\b", "পজ"),
        (r"\bRestarts?\b", "রিস্টার্ট"),
        (r"\bBatter(y|ies)\b", "ব্যাটারি"),
        (r"\bAudios?\b", "অডিও"),
        (r"\bVideos?\b", "ভিডিও"),
        (r"\bMusics?\b", "মিউজিক"),
        (r"\bSongs?\b", "গান"),
        (r"\bVoices?\b", "ভয়েস"),
        (r"\bMicrophones?\b", "মাইক্রোফোন"),
        (r"\bMics?\b", "মাইক"),
        (r"\bCalls?\b", "কল"),
        (r"\bCalling\b", "কল করা"),
        (r"\bContacts?\b", "কন্টাক্ট"),
        (r"\bNumbers?\b", "নম্বর"),
        (r"\bMessages?\b", "মেসেজ"),
        (r"\bSMS\b", "এসএমএস"),
        (r"\bEmails?\b", "ইমেইল"),
        (r"\bMails?\b", "মেইল"),
        (r"\bStorages?\b", "স্টোরেজ"),
        (r"\bMemor(y|ies)\b", "মেমোরি"),
        (r"\bRAM\b", "র‍্যাম"),
        (r"\bROM\b", "রম"),
        (r"\bVolumes?\b", "ভলিউম"),
        (r"\bSounds?\b", "সাউন্ড"),
        (r"\bTorch\b", "ফ্ল্যাশলাইট"),
        (r"\bFlashlights?\b", "ফ্ল্যাশলাইট"),
        (r"\bCameras?\b", "ক্যামেরা"),
        (r"\bPhotos?\b", "ছবি"),
        (r"\bPictures?\b", "ছবি"),
        (r"\bImages?\b", "ছবি"),
        (r"\bGaller(y|ies)\b", "গ্যালারি"),
        (r"\bNotifications?\b", "নোটিফিকেশন"),
        (r"\bAlarms?\b", "অ্যালার্ম"),
        (r"\bClocks?\b", "ঘড়ি"),
        (r"\bTimers?\b", "টাইমার"),
        (r"\bLinks?\b", "লিংক"),
        (r"\bWebsites?\b", "ওয়েবসাইট"),
        (r"\bSearch(es|ing)?\b", "সার্চ"),
        (r"\bModes?\b", "মোড"),
        (r"\bSilent\b", "সাইলেন্ট"),
        (r"\bNormals?\b", "নরমাল"),
        (r"\bVibrate\b", "ভাইব্রেট"),
        (r"\bDND\b", "ডু নট ডিস্টার্ব"),
        (r"\bPermissions?\b", "পারমিশন"),
        (r"\bAllows?\b", "অনুমতি"),
        (r"\bSystems?\b", "সিস্টেম"),
        (r"\bServers?\b", "সার্ভার"),
        (r"\bModels?\b", "মডেল"),
        (r"\bCommands?\b", "কমান্ড"),
        (r"\bTerminals?\b", "টার্মিনাল"),
        (r"\bBash\b", "ব্যাশ"),
        (r"\bTermux\b", "টারমাক্স"),
        (r"\bLinux\b", "লিনাক্স"),
        (r"\bCodes?\b", "কোড"),
        (r"\bErrors?\b", "এরর"),
        (r"\bBugs?\b", "বাগ"),
        (r"\bFix(es|ed)?\b", "ঠিক করা"),
        (r"\bDone\b", "সম্পন্ন হয়েছে"),
        (r"\bSuccess(ful)?\b", "সফল"),
        (r"\bFailed\b", "ব্যর্থ"),
        (r"\bHibban\b", "হিব্বান"),
        (r"\bAhmad\b", "আহমাদ"),
        (r"\bAhmed\b", "আহমেদ"),
        (r"\bSir\b", "স্যার"),
        (r"\bYes\b", "হ্যাঁ"),
        (r"\bNo\b", "না"),
        (r"\bHi\b", "হ্যালো"),
        (r"\bHello\b", "হ্যালো"),
        (r"\bPlease\b", "দয়া করে"),
        (r"\bThanks?\b", "ধন্যবাদ"),
        (r"\bThank you\b", "ধন্যবাদ"),
        (r"\bWelcome\b", "স্বাগতম"),
        (r"\bSorry\b", "দুঃখিত"),
        (r"\bMB\b", "এমবি"),
        (r"\bGB\b", "জিবি"),
        (r"\bKB\b", "কেবি"),
    ]
    for pattern, rep in replacements:
        text = re.sub(pattern, rep, text, flags=re.IGNORECASE)

    # Convert digits to Bengali numerals for native reading
    digit_map = str.maketrans("0123456789", "০১২৩৪৫৬৭৮৯")
    text = text.translate(digit_map)

    # Convert periods, exclamation marks, and question marks to natural Bengali pauses
    text = re.sub(r"[\.:;!\?]+", "। ", text)
    text = re.sub(r",+", ", ", text)
    text = re.sub(r"।+", "।", text)
    text = re.sub(r"।\s*", "। ", text)
    text = re.sub(r",\s*", ", ", text)
    return re.sub(r"\s+", " ", text).strip()

async def synthesize(text: str, output_path: str, voice: str, rate: str, pitch: str):
    clean_text = normalize_bengali_speech_text(text)
    if not clean_text:
        clean_text = text

    # Primary attempt with requested male voice (default: bn-BD-PradeepNeural)
    try:
        communicate = edge_tts.Communicate(clean_text, voice, rate=rate, pitch=pitch)
        await communicate.save(output_path)
        return
    except Exception as primary_err:
        sys.stderr.write(f"Primary voice ({voice}) warning: {primary_err}\n")

    # High-reliability fallback to secondary Bengali male voice (bn-IN-BashkarNeural)
    fallback_voice = "bn-IN-BashkarNeural" if voice != "bn-IN-BashkarNeural" else "bn-BD-PradeepNeural"
    communicate = edge_tts.Communicate(clean_text, fallback_voice, rate=rate, pitch=pitch)
    await communicate.save(output_path)

if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(1)
    text = sys.argv[1]
    out_file = sys.argv[2]
    voice = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3].strip() else "bn-BD-PradeepNeural"
    rate = sys.argv[4] if len(sys.argv) > 4 and sys.argv[4].strip() else "+0%"
    pitch = sys.argv[5] if len(sys.argv) > 5 and sys.argv[5].strip() else "+0Hz"
    try:
        asyncio.run(synthesize(text, out_file, voice, rate, pitch))
    except Exception as e:
        sys.stderr.write(f"TTS Error: {e}\n")
        sys.exit(2)
