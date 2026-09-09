package app.catchwave;

/** Learns seek-command delay from measured audio feedback, without a fixed device offset. */
final class AudioCorrection {
    private double previousLag,previousAdvance;
    private boolean requested;
    long nextAdvance(double lag){
        double lead=requested?Math.max(0,Math.min(250,lag-previousLag+previousAdvance)):0;
        long advance=Math.round(Math.max(-1200,Math.min(1450,lag+lead)));
        previousLag=lag;previousAdvance=advance;requested=true;return advance;
    }
}
