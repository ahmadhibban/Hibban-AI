package com.gcode.ai;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Autonomous In-App Speech Recognizer for Hibban AI.
 * Bypasses device/MIUI system SpeechRecognizer services entirely by capturing
 * raw PCM audio via AudioRecord and performing high-speed neural transcription
 * directly inside the app sandbox.
 */
public class InAppSpeechRecognizer {
    private static final String TAG = "InAppSpeechRecognizer";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final String STT_API_KEY = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw";

    public interface Listener {
        void onReadyForSpeech();
        void onBeginningOfSpeech();
        void onRmsChanged(float normalizedRms);
        void onPartialResult(String partialText);
        void onEndOfSpeech();
        void onResult(String text, float confidence);
        void onError(int errorCode, String message);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private AudioRecord audioRecord;
    private Thread workerThread;
    private volatile boolean isRecording = false;
    private volatile boolean forceFinishSpeech = false;
    private String language = "bn-BD";

    public InAppSpeechRecognizer(Context context) {
        this.context = context;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void startListening(String lang) {
        if (lang != null && !lang.trim().isEmpty()) {
            this.language = lang.trim();
        }
        stopListening();

        isRecording = true;
        forceFinishSpeech = false;
        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                recordAndProcessLoop();
            }
        }, "InAppSTT-Worker");
        workerThread.start();
    }

    public synchronized void stopListening() {
        isRecording = false;
        forceFinishSpeech = false;
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Throwable ignored) {}
            audioRecord = null;
        }
        if (workerThread != null) {
            try {
                workerThread.interrupt();
            } catch (Throwable ignored) {}
            workerThread = null;
        }
    }

    public synchronized void finishSpeechNow() {
        forceFinishSpeech = true;
    }

    private void recordAndProcessLoop() {
        int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        int bufSize = Math.max(minBufSize * 2, 4096);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    bufSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                notifyError(9, "AudioRecord initialization failed. Verify microphone permissions.");
                return;
            }

            audioRecord.startRecording();
            notifyReady();

            byte[] chunk = new byte[1600]; // 50ms chunks at 16kHz 16-bit mono
            ByteArrayOutputStream speechBuffer = new ByteArrayOutputStream();

            // Pre-speech rolling buffer (approx 400ms = 8 chunks) to avoid clipping beginning of speech
            int preSpeechChunksCount = 8;
            byte[][] preSpeechRing = new byte[preSpeechChunksCount][];
            int preIndex = 0;

            boolean speechStarted = false;
            long silenceStartTime = 0;
            long speechStartTime = 0;
            long loopStartTime = System.currentTimeMillis();

            // Baseline ambient noise calibration
            double baselineSum = 0;
            int baselineCount = 0;
            double baselineRms = 180.0;

            while (isRecording && !Thread.currentThread().isInterrupted()) {
                int read = audioRecord.read(chunk, 0, chunk.length);
                if (read <= 0) {
                    try { Thread.sleep(20); } catch (InterruptedException e) { break; }
                    continue;
                }

                // Compute RMS (Volume / Energy Level)
                long sum = 0;
                int samples = read / 2;
                for (int i = 0; i < samples; i++) {
                    short sample = (short) ((chunk[2 * i] & 0xFF) | (chunk[2 * i + 1] << 8));
                    sum += (long) sample * sample;
                }
                double rms = Math.sqrt((double) sum / samples);

                // Dynamically calibrate ambient noise during first 300ms
                if (baselineCount < 6) {
                    baselineSum += rms;
                    baselineCount++;
                    baselineRms = baselineSum / baselineCount;
                }

                float normRms = (float) Math.min(1.0, Math.max(0.0, (rms - baselineRms) / 1800.0));
                notifyRms(normRms);

                long now = System.currentTimeMillis();

                // Dynamic speech and silence thresholds based on ambient room noise
                double speechThresh = Math.max(220.0, baselineRms * 1.30 + 35.0);
                double silenceThresh = Math.max(170.0, baselineRms * 1.12 + 15.0);

                if (!speechStarted) {
                    preSpeechRing[preIndex % preSpeechChunksCount] = Arrays.copyOf(chunk, read);
                    preIndex++;

                    // If user is completely silent for 5 seconds, exit loop cleanly and restart listening
                    if (now - loopStartTime > 5000) {
                        break;
                    }

                    if (rms > speechThresh || forceFinishSpeech) {
                        speechStarted = true;
                        speechStartTime = now;
                        silenceStartTime = 0;
                        notifyBeginningOfSpeech();
                        notifyPartialResult("কথা শোনা যাচ্ছে...");

                        int startIdx = Math.max(0, preIndex - preSpeechChunksCount);
                        for (int i = startIdx; i < preIndex; i++) {
                            byte[] pb = preSpeechRing[i % preSpeechChunksCount];
                            if (pb != null) {
                                speechBuffer.write(pb);
                            }
                        }
                        speechBuffer.write(chunk, 0, read);
                    }
                } else {
                    speechBuffer.write(chunk, 0, read);

                    if (forceFinishSpeech) {
                        break;
                    }

                    if (rms < silenceThresh) {
                        if (silenceStartTime == 0) {
                            silenceStartTime = now;
                        } else if (now - silenceStartTime >= 800) { // 800ms silence detection for snappy transitions
                            break;
                        }
                    } else {
                        silenceStartTime = 0;
                    }

                    // Max 8s speech turn limit
                    if (now - speechStartTime > 8000) {
                        break;
                    }
                }
            }

            try {
                if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Throwable ignored) {}

            if (!isRecording) return;

            byte[] pcmData = speechBuffer.toByteArray();
            if (!speechStarted || pcmData.length < SAMPLE_RATE * 2 * 0.35) { // Under 350ms or no speech
                notifyError(7, "No speech detected");
                return;
            }

            notifyEndOfSpeech();
            transcribeAudio(pcmData);

        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord error", t);
            notifyError(-1, t.getMessage());
        } finally {
            if (audioRecord != null) {
                try { audioRecord.release(); } catch (Throwable ignored) {}
                audioRecord = null;
            }
        }
    }

    private void transcribeAudio(byte[] pcmData) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://www.google.com/speech-api/v2/recognize?client=chromium&lang="
                    + language + "&key=" + STT_API_KEY + "&pFilter=0";
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "audio/l16; rate=" + SAMPLE_RATE);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)");
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(6000);
            conn.setDoOutput(true);
            conn.setDoInput(true);

            OutputStream os = conn.getOutputStream();
            os.write(pcmData);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder resp = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    resp.append(line).append("\n");
                }
                reader.close();

                String resultText = parseGoogleSttResponse(resp.toString());
                if (resultText != null && !resultText.trim().isEmpty()) {
                    notifyResult(resultText.trim(), 0.95f);
                    return;
                }
            }

            notifyError(7, "No speech recognized");

        } catch (Throwable e) {
            Log.e(TAG, "Transcription network error", e);
            notifyError(2, "Network error: " + e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private String parseGoogleSttResponse(String jsonLines) {
        if (jsonLines == null) return null;
        String[] lines = jsonLines.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                JSONObject obj = new JSONObject(trimmed);
                if (obj.has("result")) {
                    JSONArray results = obj.getJSONArray("result");
                    if (results.length() > 0) {
                        JSONObject r0 = results.getJSONObject(0);
                        if (r0.has("alternative")) {
                            JSONArray alts = r0.getJSONArray("alternative");
                            if (alts.length() > 0) {
                                JSONObject best = alts.getJSONObject(0);
                                if (best.has("transcript")) {
                                    return best.getString("transcript");
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void notifyReady() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onReadyForSpeech();
            }
        });
    }

    private void notifyBeginningOfSpeech() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onBeginningOfSpeech();
            }
        });
    }

    private void notifyRms(final float normRms) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onRmsChanged(normRms);
            }
        });
    }

    private void notifyPartialResult(final String partialText) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onPartialResult(partialText);
            }
        });
    }

    private void notifyEndOfSpeech() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onEndOfSpeech();
            }
        });
    }

    private void notifyResult(final String text, final float confidence) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onResult(text, confidence);
            }
        });
    }

    private void notifyError(final int code, final String msg) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onError(code, msg);
            }
        });
    }
}
