package com.open.alpha2;

import android.util.Log;

import java.util.Map;

/**
 * Vosk offline ASR endpoint layer: models/load/status/start/stop/unload/download/catalog.
 */
public final class VoskApi {
    private static final String TAG = "VoskApi";

    private final VoskController vosk;
    private final XiaozhiBridge xiaozhiBridge;
    private final SemanticCenter semanticCenter;

    public VoskApi(VoskController vosk, XiaozhiBridge xiaozhiBridge, SemanticCenter semanticCenter) {
        this.vosk = vosk;
        this.xiaozhiBridge = xiaozhiBridge;
        this.semanticCenter = semanticCenter;
    }

    /** Circuit breaker for the vosk/* endpoints: when vosk is null (API 19
     *  devices do not start the controller, or init failed on 21+), return a
     *  clear error instead of writing an if for every case.
     *  Return null = usable, go ahead. */
    private HttpServer.ApiResponse voskOrError() {
        if (vosk != null) return null;
        if (android.os.Build.VERSION.SDK_INT < 21) {
            return HttpServer.ApiResponse.error("Vosk needs Android 5.0+ (this device is API "
                    + android.os.Build.VERSION.SDK_INT + ")");
        }
        return HttpServer.ApiResponse.error("vosk not initialised");
    }

    // -- Vosk offline ASR --------------------------------------
    // Models on sdcard are auto-detected (see VoskController.scanModels), one at a time.
    // Full-sentence results reuse the asr_result event (the frontend uses the same
    // pipeline as typed input: bubble + semantic pairing + Android TTS + direct actions).
    // API 19 circuit breaker: apart from models (a pure file scan, static, works on
    // any API level), the others return a clear error via voskOrError(); no if per case.
    public HttpServer.ApiResponse voskModels() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"models\":[");
        boolean first = true;
        for (VoskController.VoskModelInfo m : VoskController.scanModels()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(MainActivity.jsonSafe(m.id))
              .append("\",\"lang\":\"").append(MainActivity.jsonSafe(m.langHint))
              .append("\",\"langEn\":\"").append(MainActivity.jsonSafe(m.langEn))
              .append("\",\"sizeMb\":").append(m.sizeBytes / 1048576).append('}');
        }
        sb.append("],\"download\":").append(downloadJsonInner());
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    private String downloadJsonInner() {
        if (vosk == null) return "null";
        try {
            // downloadStatusJson is {"ok":true,...}; strip the ok layer so the inner text is easy to embed.
            String full = vosk.downloadStatusJson();
            int i = full.indexOf("\"state\"");
            if (i < 0) return "null";
            return "{" + full.substring(i);
        } catch (Throwable e) {
            return "null";
        }
    }

    public HttpServer.ApiResponse voskLoad(Map<String, String> query) {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String id = ApiValidator.require(query, "model");
        String err = vosk.loadModel(id);
        if (err != null) return HttpServer.ApiResponse.error(err);
        // A direct model switch via the model key also switches the paired
        // language (an unrecognized language is left untouched).
        // Load runs in the background; this switches optimistically and
        // synchronously -- same behavior as the frontend's instant switch.
        String mapped = VoskController.langOfModelId(id);
        if (mapped != null) semanticCenter.setDialogueLang(mapped);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"loading\":\""
                + MainActivity.jsonSafe(id) + "\"}");
    }

    /** vosk/grammar: no words = read. words = comma list (for example alpha,robot,[unk]).
     *  words=none clears it (open vocabulary). Used at the next vosk/start. */
    public HttpServer.ApiResponse voskGrammar(Map<String, String> query) {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String words = query == null ? null : query.get("words");
        if (words != null) {
            if ("none".equals(words.trim()) || words.trim().isEmpty()) {
                vosk.setGrammar("");
            } else {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String w : words.split(",")) {
                    String t = w.trim().toLowerCase(java.util.Locale.US);
                    if (!t.isEmpty()) arr.put(t);
                }
                vosk.setGrammar(arr.toString());
            }
        }
        String g = vosk.getGrammar();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"grammar\":" + (g.isEmpty() ? "null" : g) + "}");
    }

    public HttpServer.ApiResponse voskStatus() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String mid = vosk.getModelId();
        String msg = vosk.getLastError();
        return HttpServer.ApiResponse.ok("{\"ok\":true"
                + ",\"state\":\"" + vosk.getState().name().toLowerCase(java.util.Locale.US) + "\""
                + ",\"model\":" + (mid == null ? "null" : "\"" + MainActivity.jsonSafe(mid) + "\"")
                + ",\"lang\":\"" + vosk.getDialogueLang() + "\""
                + ",\"listening\":" + vosk.isListening()
                + (msg == null ? "" : ",\"message\":\"" + MainActivity.jsonSafe(msg) + "\"")
                + "}");
    }

    public HttpServer.ApiResponse voskStart() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        // Whoever opens later gets the mic: if Xiaozhi is opening, cut the
        // whole session (disconnect + mute lamp off, the same order as the
        // "disconnect" case), then start the recorder (symmetric with
        // startXiaozhiMic stopping vosk; do not auto-reopen for Xiaozhi --
        // that side does not auto-reopen vosk either).
        xiaozhiBridge.yieldMicToVosk();
        String err = vosk.startListening();
        if (err != null) return HttpServer.ApiResponse.error(err);
        return HttpServer.ApiResponse.okTrue();
    }

    public HttpServer.ApiResponse voskStop() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        vosk.stopListening();
        return HttpServer.ApiResponse.okTrue();
    }

    /** Model-switch waiting generation (a new press supersedes the old one
     *  that is still waiting; do not pile up twenty or thirty threads waiting
     *  for the lock, see logcat "Long monitor contention waiters=28"). */
    private final java.util.concurrent.atomic.AtomicInteger switchGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** Is the caller the main thread (the mapped physical button is; the HTTP worker is not). */
    private static boolean onMainThread() {
        try {
            return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shared by the GamepadCenter mapping: single-key toggle for Vosk
     *  listening (stop if listening, grab the mic and start listening if idle).
     *  main thread via the physical button: always run in the background,
     *  even the isListening() read lock is not touched (waiting on a
     *  background op means ANR death, see traces); return true = stopped /
     *  accepted. The HTTP worker runs the synchronous version (the return
     *  value is factual). Background failures are only logged and visible in
     *  status. */
    public boolean toggleListening() {
        if (voskOrError() != null) return false;
        if (onMainThread()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    toggleListeningSync();
                }
            }, "VoskToggle").start();
            return true;
        }
        return toggleListeningSync();
    }

    private boolean toggleListeningSync() {
        try {
            boolean listening = false;
            try {
                listening = vosk.isListening();
            } catch (Throwable ignored) {
            }
            if (listening) {
                try {
                    vosk.stopListening();
                } catch (Throwable t) {
                    return false;
                }
                return true;
            }
            startListeningAsync();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Start listening in the background (yield the mic + start; on failure Log + do not throw at the caller). */
    private void startListeningAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    xiaozhiBridge.yieldMicToVosk();
                    String err = vosk.startListening();
                    if (err != null) Log.w(TAG, "async startListening failed: " + err);
                } catch (Throwable t) {
                    Log.w(TAG, "async startListening failed", t);
                }
            }
        }, "VoskStart").start();
    }

    /** Shared by the GamepadCenter mapping: switch to a given language pack
     *  (stop if listening on the same pack; otherwise load and start
     *  listening once it is ready).
     *  main thread via the physical button: always run in the background,
     *  even the getModelId() read lock is not touched (waiting on a
     *  background op means ANR death, see traces); return true = stopped /
     *  switch accepted. The HTTP worker runs the synchronous version (the
     *  return value is factual). */
    public boolean switchModel(String id) {
        if (voskOrError() != null || id == null || id.trim().isEmpty()) return false;
        final String tid = id.trim();
        if (onMainThread()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    switchModelSync(tid);
                }
            }, "VoskSwitchMap").start();
            return true;
        }
        return switchModelSync(tid);
    }

    private boolean switchModelSync(String tid) {
        try {
            String cur = null;
            boolean listening = false;
            try {
                cur = vosk.getModelId();
            } catch (Throwable ignored) {
            }
            try {
                listening = vosk.isListening();
            } catch (Throwable ignored) {
            }
            if (listening && tid.equals(cur)) {
                try {
                    vosk.stopListening();
                } catch (Throwable t) {
                    return false;
                }
                return true;
            }
            final String err = vosk.loadModel(tid);
            if (err != null) {
                // Steering change while a load is in flight: interrupt the
                // old load and load the new one (not a pile of silent
                // false returns; unload makes the old bg thread clean up
                // by itself, see loadModel).
                boolean loading = false;
                try {
                    loading = vosk.getState() == VoskController.State.LOADING;
                } catch (Throwable ignored) {
                }
                if (loading) {
                    try {
                        vosk.unload();
                    } catch (Throwable ignored) {
                    }
                    String err2 = null;
                    try {
                        err2 = vosk.loadModel(tid);
                    } catch (Throwable t) {
                        return false;
                    }
                    if (err2 != null) return false;
                } else {
                    return false;
                }
            }
            String mapped = VoskController.langOfModelId(tid);
            if (mapped != null) {
                try {
                    semanticCenter.setDialogueLang(mapped);
                } catch (Throwable ignored) {
                }
            }
            final int gen = switchGen.incrementAndGet();
            new Thread(new Runnable() {
                @Override public void run() {
                    // Wait for the load to finish before starting to listen
                    // (a load takes a few seconds to a few tens of seconds;
                    // check once per second, wait up to 60 seconds; a new
                    // press supersedes -- leave immediately, do not pile up
                    // threads waiting for the lock).
                    for (int i = 0; i < 60; i++) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (gen != switchGen.get()) return;
                        boolean ready = false;
                        try {
                            ready = !vosk.isListening()
                                    && tid.equals(vosk.getModelId())
                                    && vosk.getState() == VoskController.State.READY;
                        } catch (Throwable ignored) {
                        }
                        if (ready) break;
                        if (i == 59) return;
                    }
                    // Wake up and re-check the generation: if someone pressed a newer pack meanwhile, back off; do not start the old one.
                    if (gen != switchGen.get()) return;
                    try {
                        xiaozhiBridge.yieldMicToVosk();
                        String e2 = vosk.startListening();
                        if (e2 != null) Log.w(TAG, "async switch start failed: " + e2);
                    } catch (Throwable t) {
                        Log.w(TAG, "async switch start failed", t);
                    }
                }
            }, "VoskSwitch").start();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public HttpServer.ApiResponse voskUnload() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        vosk.unload();
        return HttpServer.ApiResponse.okTrue();
    }

    // Model download + auto unzip (for the download card on the experiments tab).
    // model = directory name (see vosk/catalog; a fixed official URL allowlist, no arbitrary URLs).
    // The zip is downloaded in the background and then unzipped to the top level of
    // sdcard by itself; when done it loads automatically. Progress goes via the
    // vosk_download event + download_status query; no need to poll models.
    public HttpServer.ApiResponse voskDownload(Map<String, String> query) {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String id = ApiValidator.require(query, "model");
        if (!VoskController.isDownloadable(id)) {
            return HttpServer.ApiResponse.error("unknown downloadable model: " + id);
        }
        String err = vosk.startDownload(id);
        if (err != null) {
            // An existing model is not an error (the frontend may arrive after
            // asking; the other side may already have finished): return
            // ok+exists so the frontend refreshes directly. "already
            // downloading" returns the status the same way, so the frontend
            // follows the progress.
            if (err.startsWith("already exists") || err.startsWith("already downloading")) {
                return HttpServer.ApiResponse.ok(vosk.downloadStatusJson());
            }
            return HttpServer.ApiResponse.error(err);
        }
        return HttpServer.ApiResponse.ok(vosk.downloadStatusJson());
    }

    public HttpServer.ApiResponse voskDownloadStatus() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        return HttpServer.ApiResponse.ok(vosk.downloadStatusJson());
    }

    public HttpServer.ApiResponse voskDownloadCancel() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String err = vosk.cancelDownload();
        if (err != null) return HttpServer.ApiResponse.error(err);
        return HttpServer.ApiResponse.ok(vosk.downloadStatusJson());
    }

    // Catalog of all downloadable models (id/lang/sizeMb/downloaded; a pure
    // file scan, static, works on API 19 too; like models, it does not go
    // through the voskOrError circuit breaker).
    public HttpServer.ApiResponse voskCatalog() {
        return HttpServer.ApiResponse.ok(VoskController.catalogJson());
    }
}


