// Open Alpha2 — client logic (app-mic.js)
// Content: listen to the robot microphone (WAV chunk stream playback), camera fullscreen.
// All files share the window/global scope (no ES modules). Load order follows the
// <script src="..."> order in index.html - see the comment block at the top of index.html.

// ---------------- Mic: listen to the robot's microphone ----------------
//
// Unlike the camera's <img src="/stream/camera">, which lets the browser handle
// multipart/x-mixed-replace natively, audio has no equivalent built-in tag/MIME
// handling - so this reads the /stream/mic response manually via fetch()+
// ReadableStream, splits on the "--boundary" markers itself to recover each
// self-contained WAV chunk, strips the 44-byte WAV header off each one (see
// WAV_HEADER_BYTES below), resamples the raw 16kHz PCM samples to whatever sample
// rate this AudioContext actually runs at (see MIC_SOURCE_SAMPLE_RATE below), and
// feeds them into a ScriptProcessorNode's ring buffer for continuous playback.
//
// The target WebView (RK3288/Android 5.1, Chromium 39) has no AudioWorklet (Chrome 66 added it),
// and the AudioContext {sampleRate} option does nothing either (Chromium 39 ignores it and uses the native rate), so read
// ctx.sampleRate at runtime and resample the 16kHz source PCM to that rate before it enters the buffer.
//
// Final choice: ScriptProcessorNode. Official docs deprecated it (they suggest
// AudioWorklet), but the API existed since the first Web Audio releases (2011),
// and old WebViews like Chromium 39 support it widely. The target device of this
// project is a fixed Android 5.1 old WebView with no future upgrade, so "deprecated" does not
// apply to this machine. ScriptProcessorNode runs on the main thread (no separate audio
// thread with real-time guarantees like AudioWorklet), and it skips the async decodeAudioData() step entirely.

let micListening = false;
let micAbortController = null;
let micAudioContext = null;
let micScriptNode = null;
// micMuted is always false (mic-listen never mutes).
let micMuted = false;

// The ring buffer itself (Float32, already resampled to the AudioContext's actual sample rate),
// written by feedPcmToBuffer() and read by micAudioProcessCallback().
let micRingBuffer = null;
let micRingCapacity = 0;
let micRingWriteIdx = 0;
let micRingReadIdx = 0;
let micRingAvailable = 0; // count of samples in the buffer not yet played
// Fractional position for resampling - the source rate (16kHz) and target rate (native,
// e.g. 44100) are usually not whole multiples, so keep one fractional position across
// successive feedPcmToBuffer() calls. That stops the resample between consecutive chunks from
// building cumulative error or clicks because of the leftover fraction.
let micResampleFracPos = 0;

// The WAV header is a fixed 44 bytes (PCM, mono, 16-bit - matching the format
// AudioController.java sends), used to split the header from the real PCM data in each chunk.
const WAV_HEADER_BYTES = 44;

// The actual sample rate of the PCM the server side (AudioController.java) sends - this is the
// "source" rate, not the rate the AudioContext runs at (see the comment block above for why
// the two can differ). Keep it equal to AudioController.SAMPLE_RATE_HZ.
const MIC_SOURCE_SAMPLE_RATE = 16000;

// Ring buffer capacity (samples, at the AudioContext's actual sample rate) - this is the
// size of the buffer array itself (a write must never overflow it). 2 seconds is deliberately big to absorb
// sudden network bursts; it does not mean the audible delay may reach that long -
// MIC_MAX_LATENCY_SEC alone is the target for "how much delay you want to hear".
const MIC_RING_BUFFER_SEC = 2;

// The maximum audible delay - after each new chunk write, actively trim the buffer level down under
// this target (dropping the oldest samples), so the actual delay keeps to this level long-term
// instead of waiting to passively hit MIC_RING_BUFFER_SEC (the array size cap).
//
// This value must be bigger than one server chunk (CHUNK_MS=500ms, see
// AudioController.java). Otherwise a single normal chunk flooding into the buffer already pushes
// the level past the threshold, every chunk arrival mis-triggers the trim, and audio breaks up (verified in tests:
// 0.3 s trims on every chunk and breaks up several times a second). 1.2 s (2.4x CHUNK_MS) leaves enough
// slack for normal chunk-arrival jitter, at the cost of an audible delay around
// 1.2 s (plus the one-time ~0.7 s mic init at start, total delay about 1.8-2.2
// s). This is the trade-off between "no breaks" and "short delay"; tests proved a small threshold breaks up.
// To cut the delay further you need a different mechanism (e.g. check the level at the consumer, not the writer),
// not just a smaller value here.
const MIC_MAX_LATENCY_SEC = 1.2;

function micElements() {
  return {
    btn: document.getElementById("micListenFab"),
  };
}

function toggleMicListen() {
  if (micListening) {
    stopMicListen();
  } else {
    startMicListen();
  }
}

function startMicListen() {
  micListening = true;
  const btn = micElements().btn;
  if (btn) btn.classList.add("listening");
  // Do not pass the {sampleRate: ...} option - it does nothing on the target WebView (Chromium 39),
  // and passing it only creates a misleading illusion. Use the browser/device native sample
  // rate; feedPcmToBuffer() below resamples to match it.
  const ctx = new (window.AudioContext || window.webkitAudioContext)();
  micAudioContext = ctx;

  const ringCapacity = Math.ceil(ctx.sampleRate * MIC_RING_BUFFER_SEC);
  micRingBuffer = new Float32Array(ringCapacity);
  micRingCapacity = ringCapacity;
  micRingWriteIdx = 0;
  micRingReadIdx = 0;
  micRingAvailable = 0;
  micResampleFracPos = 0;

  // bufferSize 4096: big enough that onaudioprocess does not interrupt the main thread too often, small
  // enough that the delay stays low. 0 input channels (playback only, no recording), 1 output
  // channel (mono, matching the format AudioController.java sends).
  micScriptNode = ctx.createScriptProcessor(4096, 0, 1);
  micScriptNode.onaudioprocess = micAudioProcessCallback;
  micScriptNode.connect(ctx.destination);

  micAbortController = new AbortController();
  runMicStreamLoop(micAbortController.signal);
  // The head/eye LED steady green moved to the server side (see the MainActivity#handleMicStream/
  // releaseMicForAudioIo javadoc). The LED command must wait until the device's own speech_SetMIC(true)
  // 300ms release flow finishes, otherwise it races the device's own setWakeState side effect
  // (the LED_ACTION broadcast that auto-switches the ear LED off), and the light comes on only sometimes.
  // Sending it here (front end) at the start would fight that broadcast again and reproduce the problem.
}

function stopMicListen() {
  micListening = false;
  const btn = micElements().btn;
  if (btn) btn.classList.remove("listening");
  if (micAbortController) {
    micAbortController.abort();
    micAbortController = null;
  }
  if (micScriptNode) {
    micScriptNode.disconnect();
    micScriptNode.onaudioprocess = null;
    micScriptNode = null;
  }
  if (micAudioContext) {
    micAudioContext.close();
    micAudioContext = null;
  }
  micRingBuffer = null;
  // No race here (setWakeState is not triggered again), so the front end still switches the light off on purpose - the server-side
  // handleMicStream() finally block keeps a safety stop as well (for a dropped connection that bypasses this button).
  setListenLed(false);
}

/** Switch the head/eye LED back off when listening to the robot (🎧) ends - alpha2-only, the same pattern as
 *  setRecordingLed()/tilt LED/flashCaptureLed(). The light-on side happens on the server (see the comment above). */
function setListenLed(on) {
  if (currentBackend !== "alpha2") return;
  if (on) {
    const headBrightness = document.getElementById("headBrightness").value;
    const eyeBrightness = document.getElementById("eyeBrightness").value;
    Alpha2Api.ledHeadSet( { preset: "long", color: 2, brightness: headBrightness });
    Alpha2Api.ledEyeSet( { preset: "long", color: 2, brightness: eyeBrightness });
  } else {
    Alpha2Api.ledHeadSet( { preset: "stop" });
    Alpha2Api.ledEyeSet( { preset: "stop" });
  }
}

/** Reads /stream/mic and plays each WAV chunk as it arrives; reconnects automatically
 *  (matching the camera stream's own reconnect-on-error behavior) unless the user has
 *  since stopped listening. */
/** Reads /stream/mic and feeds each chunk's raw PCM samples into the ring buffer for
 *  playback; reconnects automatically (matching the camera stream's own
 *  reconnect-on-error behavior) unless the user has since stopped listening. */
async function runMicStreamLoop(signal) {
  const boundaryMarker = "--opensdktestpanelaudio";
  try {
    // When the panel token locks the device, verify it (Bearer header; see PanelAuth. fetch sets it in the header,
    // not as a query, so it never lands in a URL or log).
    const micHdrs = (typeof panelAuthHeaders === "function") ? panelAuthHeaders() : {};
    const resp = await fetch("/stream/mic?t=" + Date.now(), { signal: signal, headers: micHdrs });
    if (!resp.ok || !resp.body) {
      throw new Error("stream request failed: " + resp.status);
    }
    const reader = resp.body.getReader();
    let buffer = new Uint8Array(0);

    while (micListening) {
      const { value, done } = await reader.read();
      if (done) break;

      const combined = new Uint8Array(buffer.length + value.length);
      combined.set(buffer, 0);
      combined.set(value, buffer.length);
      buffer = combined;

      // Extract every complete part currently in the buffer; a part is
      // "--boundary\r\nheaders\r\n\r\n<wav bytes>\r\n" - find each header/body split by
      // the blank-line marker, and each part's end by the next boundary marker.
      while (true) {
        const text = bytesToLatin1String(buffer);
        const boundaryIdx = text.indexOf(boundaryMarker);
        if (boundaryIdx < 0) break;
        const headerEnd = text.indexOf("\r\n\r\n", boundaryIdx);
        if (headerEnd < 0) break; // headers not fully arrived yet
        const bodyStart = headerEnd + 4;
        const nextBoundaryIdx = text.indexOf(boundaryMarker, bodyStart);
        if (nextBoundaryIdx < 0) break; // body not fully arrived yet

        // Body ends 2 bytes before the next boundary marker (trailing "\r\n").
        const bodyEnd = nextBoundaryIdx - 2;
        const wavBytes = buffer.slice(bodyStart, Math.max(bodyStart, bodyEnd));
        buffer = buffer.slice(nextBoundaryIdx);

        if (wavBytes.length > WAV_HEADER_BYTES) {
          feedPcmToBuffer(wavBytes);
        }
      }
    }
  } catch (e) {
    if (signal.aborted) return; // user stopped listening - not an error
    console.warn("Mic stream ended: " + e.message);
  }
  if (micListening) {
    // Unexpected disconnect while the user still wants to listen - reconnect.
    setTimeout(function () {
      if (micListening) runMicStreamLoop(signal);
    }, 1000);
  }
}

function bytesToLatin1String(bytes) {
  // Latin-1 (not UTF-8) decoding: this is only used to *locate* the ASCII boundary
  // markers and header text by byte offset, not to interpret the binary WAV payload
  // as text - UTF-8 decoding could merge/split multi-byte sequences and throw off the
  // byte offsets used to slice the original buffer.
  let s = "";
  for (let i = 0; i < bytes.length; i++) {
    s += String.fromCharCode(bytes[i]);
  }
  return s;
}

/** Strips the 44-byte WAV header off a chunk, converts its 16-bit signed PCM samples
 *  to Float32 (Web Audio's native sample format, range -1..1), resamples from
 *  MIC_SOURCE_SAMPLE_RATE to the AudioContext's actual sampleRate (see the big comment
 *  at the top of this section for why that resample is necessary), and writes the
 *  result into the ring buffer for micAudioProcessCallback() to play out. Runs
 *  synchronously and cheaply (no decoding, just linear scans) - unlike
 *  decodeAudioData(), there's no async step here for timing to drift around. */
function feedPcmToBuffer(wavBytes) {
  if (!micRingBuffer || micMuted) return;
  const pcmBytes = wavBytes.subarray(WAV_HEADER_BYTES);
  // pcmBytes.byteOffset is relative to the underlying ArrayBuffer, which may not be
  // 2-byte aligned after the .slice()/.subarray() calls above - DataView (unlike the
  // Int16Array constructor) works at any byte offset, so it's used here instead of
  // risking a RangeError/misaligned read on some browsers.
  const view = new DataView(pcmBytes.buffer, pcmBytes.byteOffset, pcmBytes.byteLength);
  const sampleCount = Math.floor(pcmBytes.byteLength / 2);
  if (sampleCount === 0) return;

  const ctx = micAudioContext;
  if (!ctx) return;
  const ratio = MIC_SOURCE_SAMPLE_RATE / ctx.sampleRate; // how many source samples equal one output sample
  // Resume the fractional position kept from the previous chunk (see the micResampleFracPos declaration
  // comment). Start it at -ratio so the very first output sample goes through the same logic,
  // with no special case for "how to compute the first sample".
  let srcPos = micResampleFracPos;
  const outSamples = [];
  while (srcPos < sampleCount) {
    const idx = Math.floor(srcPos);
    // Nearest-neighbor (take the sample at the integer position, no linear interpolation) -
    // good enough for speech, and far less code and math than a windowed-sinc resampler.
    const clampedIdx = Math.min(idx, sampleCount - 1);
    const int16 = view.getInt16(clampedIdx * 2, true); // true = little-endian
    outSamples.push(int16 / 32768);
    srcPos += ratio;
  }
  micResampleFracPos = srcPos - sampleCount; // carried into the next chunk

  for (let i = 0; i < outSamples.length; i++) {
    if (micRingAvailable >= micRingCapacity) {
      // The ring buffer array itself is full (must never happen - the active trim logic below should
      // have capped the level long before, so this is only the last line of defence) - sacrifice the oldest
      // sample so the array index never overflows.
      micRingReadIdx = (micRingReadIdx + 1) % micRingCapacity;
      micRingAvailable--;
    }
    micRingBuffer[micRingWriteIdx] = outSamples[i];
    micRingWriteIdx = (micRingWriteIdx + 1) % micRingCapacity;
    micRingAvailable++;
  }

  // Active backlog trim: when the level passes the MIC_MAX_LATENCY_SEC target, drop the oldest
  // samples right away back to the target level, instead of waiting for it to crawl up to MIC_RING_BUFFER_SEC
  // (the array size cap) and only then get passively capped. This is the mechanism that really decides the delay
  // the user hears - see the MIC_MAX_LATENCY_SEC declaration note (including why the threshold
  // must exceed CHUNK_MS, and the measured basis of this trade-off).
  const maxLatencySamples = Math.floor(ctx.sampleRate * MIC_MAX_LATENCY_SEC);
  if (micRingAvailable > maxLatencySamples) {
    const toDrop = micRingAvailable - maxLatencySamples;
    micRingReadIdx = (micRingReadIdx + toDrop) % micRingCapacity;
    micRingAvailable -= toDrop;
  }
}

/** The ScriptProcessorNode onaudioprocess callback - fired by the browser audio graph on a timer
 *  (bufferSize sets the rate, about once every bufferSize/sampleRate seconds,
 *  e.g. 4096/44100 ≈ 93ms), running on the main thread. Pull enough samples from the ring buffer
 *  to fill the output; with too few samples output silence (underrun). It never "skips ahead"
 *  the way the decodeAudioData() approach does.*/
function micAudioProcessCallback(event) {
  const output = event.outputBuffer.getChannelData(0); // mono, single channel
  for (let i = 0; i < output.length; i++) {
    if (micRingAvailable > 0) {
      output[i] = micRingBuffer[micRingReadIdx];
      micRingReadIdx = (micRingReadIdx + 1) % micRingCapacity;
      micRingAvailable--;
    } else {
      output[i] = 0; // underrun: play silence, better than stale/garbage data
    }
  }
}

/** Double-click/double-tap on the viewport toggles native fullscreen on that
 *  element, so the video fills the whole screen. */
function toggleCameraFullscreen() {
  const viewport = cameraElements().viewport;
  const fsElement = document.fullscreenElement || document.webkitFullscreenElement;
  if (fsElement) {
    (document.exitFullscreen || document.webkitExitFullscreen).call(document);
  } else {
    const request = viewport.requestFullscreen || viewport.webkitRequestFullscreen;
    if (request) {
      request.call(viewport);
    } else {
      showError(uiLang === "en" ? "Fullscreen" : "全螢幕", new Error(uiLang === "en" ? "This browser does not support the Fullscreen API" : "此瀏覽器不支援 Fullscreen API"));
    }
  }
}


