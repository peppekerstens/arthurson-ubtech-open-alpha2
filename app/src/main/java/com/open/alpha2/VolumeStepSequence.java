package com.open.alpha2;

/**
 * The order of the actions for one head-key volume step (alpha2-brain#53).
 *
 * Pure Java, no Android types, so a host test can run it.
 *
 * Early feedback off (default): volume binder call, then tick, then LED meter.
 * This is the exact order of the build before alpha2-brain#53.
 *
 * Early feedback on: tick first, then the volume binder call, then LED meter.
 * The tick does not wait for the system audio binder. The volume step itself
 * is not faster. The binder call runs on the same thread and takes the same time.
 */
public final class VolumeStepSequence {

    /** The three actions. The caller does the real work. */
    public interface Actions {
        /** Play the preloaded tick. Must not throw. */
        void tick();

        /**
         * Do the volume step with the system audio service.
         *
         * @param playSoundFlag true when the system must play its own click
         *                      (the tick is not loaded yet)
         */
        void adjustVolume(boolean playSoundFlag);

        /** Show the LED volume meter. */
        void meter();
    }

    private VolumeStepSequence() {
    }

    /**
     * Runs one step.
     *
     * @param earlyFeedback true: tick before the volume binder call
     * @param tickReady     true when the tick sound is loaded
     * @param actions       the actions
     */
    public static void run(boolean earlyFeedback, boolean tickReady, Actions actions) {
        boolean playSoundFlag = !tickReady;
        if (earlyFeedback) {
            actions.tick();
            actions.adjustVolume(playSoundFlag);
        } else {
            actions.adjustVolume(playSoundFlag);
            actions.tick();
        }
        actions.meter();
    }
}
