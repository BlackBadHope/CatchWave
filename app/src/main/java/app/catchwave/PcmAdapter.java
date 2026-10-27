// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/**
 * Streaming downmix and linear resample to 16 kHz mono PCM16 for the library indexer.
 * Pure Java so conversion accuracy is tested without a device; phase carries across chunks.
 */
final class PcmAdapter {
    private final int channels;
    private final double step;
    private double position;  // Fractional source-frame index of the next output sample; -1 reads the carried frame.
    private double previous;  // Mono value of the prior chunk's last frame.
    PcmAdapter(int sourceRate,int channels){
        if(sourceRate<4000||sourceRate>384000||channels<1||channels>8)throw new IllegalArgumentException("source format");
        this.channels=channels;
        step=sourceRate/(double)LocalFingerprint.SAMPLE_RATE;
    }
    /** Interleaved source samples in, 16 kHz mono out. Returns the number of shorts written to out. */
    int convert(short[] interleaved,int samples,short[] out){
        int frames=samples/channels;
        if(frames<=0)return 0;
        int written=0;
        while(written<out.length){
            int index=(int)Math.floor(position);
            double fraction=position-index;
            double value;
            if(fraction==0){
                if(index>frames-1)break;
                value=mono(interleaved,index);
            }else{
                if(index+1>frames-1)break;
                double current=mono(interleaved,index);
                value=current+(mono(interleaved,index+1)-current)*fraction;
            }
            out[written++]=(short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(value)));
            position+=step;
        }
        previous=mono(interleaved,frames-1);
        position-=frames;
        return written;
    }
    private double mono(short[] interleaved,int frame){
        if(frame<0)return previous;
        long sum=0;
        for(int channel=0;channel<channels;channel++)sum+=interleaved[frame*channels+channel];
        return sum/(double)channels;
    }
    /** Output samples produced for a given input short count, for sizing buffers. */
    int outputCapacity(int samples){return (int)(samples/(double)channels/step)+2;}
}
