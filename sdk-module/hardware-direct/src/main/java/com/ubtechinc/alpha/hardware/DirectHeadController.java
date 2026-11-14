package com.ubtechinc.alpha.hardware;

// 2026-09: StaticValue moved in from the ubtechalpha2robot module into this package (RobotWire).

/**
 * Direct drive of the head board MCU: noise reduction, LED (via MCU command),
 * and the JNI direct-drive branch of the 5-mic LED.
 * On the 1.1.7.3 head board generation, the 5-mic must go over the
 * LedControl JNI (libhead_led.so). LED_EAR/LED_EYE (1/2) of sendCommand are
 * dead on the 5-mic, see AIDL_REFERENCE 3.1.
 */
public final class DirectHeadController {
    private final DirectSerialPort port;

    public DirectHeadController(DirectSerialPort port) { this.port = port; }

    public boolean open() { return port.open(); }
    public void close() { port.close(); }
    public boolean isAvailable() { return port.isAvailable(); }
    public void setFrameListener(DirectSerialPort.OnFrameListener l) { port.addListener(l); }

    /** Noise reduction: cmd 39, 0=on 1=off (the opposite of what you would guess) */
    public boolean setNoiseReduction(boolean open) {
        return port.send(RobotWire.HEAD_CONTROL_BYPASS, new byte[]{(byte) (open ? 0 : 1)});
    }

    /** Raw frame pass-through (used by debug/serial/send). Under pure-direct this replaces robot.header_sendRawData. */
    public boolean sendRaw(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length == 0) return false;
        return port.sendRaw(rawFrame);
    }
}
