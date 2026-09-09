package app.catchwave;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class AudioAlignmentTest {
    private static short[] signal(int length,int seed){Random random=new Random(seed);short[] data=new short[length];double previous=0;for(int i=0;i<length;i++){previous=.45*previous+random.nextGaussian()*6000;data[i]=(short)Math.max(-30000,Math.min(30000,previous));}return data;}
    private static short[] shifted(short[] source,int lag,boolean filtered){short[] data=new short[source.length];Random noise=new Random(7);for(int i=0;i<data.length;i++){int j=i-lag;double value=j>=0&&j<source.length?source[j]:0;if(filtered&&j>0&&j<source.length)value=.6*value+.2*source[j-1]+noise.nextGaussian()*200;data[i]=(short)value;}return data;}
    @Test public void measuresPhoneBehindBy300ms(){short[] x=signal(96000,1);var result=AudioAlignment.compare(x,1000,shifted(x,-4800,false),1000);assertTrue(result.valid);assertEquals(300,result.lagMs,1);}
    @Test public void measuresPhoneAheadAndHandlesSpeakerFiltering(){short[] x=signal(96000,2);var result=AudioAlignment.compare(x,1000,shifted(x,4800,true),1000);assertTrue(result.valid);assertEquals(-300,result.lagMs,1);}
    @Test public void alignsDifferentCaptureStartTimes(){short[] x=signal(96000,3);var result=AudioAlignment.compare(x,1000,shifted(x,-5600,true),1050);assertTrue(result.valid);assertEquals(300,result.lagMs,1);}
    @Test public void nearZeroAudioLagDoesNotInventDeviceCorrection(){short[] x=signal(96000,4);var result=AudioAlignment.compare(x,1000,shifted(x,0,true),1000);assertTrue(result.valid);assertEquals(0,result.lagMs,1);}
    @Test public void silenceCannotAuthorizeASeek(){assertFalse(AudioAlignment.compare(new short[96000],1000,signal(96000,5),1000).valid);}
    @Test public void unrelatedSongsCannotAuthorizeASeek(){assertFalse(AudioAlignment.compare(signal(96000,1),1000,signal(96000,2),1000).valid);}
    @Test public void ambiguousPeriodicToneCannotAuthorizeASeek(){short[] tone=new short[96000];for(int i=0;i<tone.length;i++)tone[i]=(short)(10000*Math.sin(2*Math.PI*440*i/16000));assertFalse(AudioAlignment.compare(tone,1000,shifted(tone,-4800,false),1000).valid);}
    @Test public void changingLagBetweenWindowsIsRejected(){short[] x=signal(96000,6),y=shifted(x,-4800,false),other=shifted(x,-5600,false);System.arraycopy(other,48000,y,48000,48000);assertFalse(AudioAlignment.compare(x,1000,y,1000).valid);}
    @Test public void seekLatencyIsLearnedFromMeasuredResidual(){AudioCorrection c=new AudioCorrection();assertEquals(300,c.nextAdvance(300));assertEquals(120,c.nextAdvance(60));}
    @Test public void earlyPhoneCanAlsoBeCorrected(){AudioCorrection c=new AudioCorrection();assertEquals(-300,c.nextAdvance(-300));assertEquals(120,c.nextAdvance(60));}
}
