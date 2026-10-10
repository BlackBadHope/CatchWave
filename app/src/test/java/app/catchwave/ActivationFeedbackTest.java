package app.catchwave;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ActivationFeedbackTest {
    @Test public void permissionAndRepeatedSamplesDoNotProduceSounds(){List<Boolean> tones=new ArrayList<>();ActivationFeedback feedback=new ActivationFeedback(tones::add);feedback.update(false,true);feedback.update(false,true);feedback.update(true,true);feedback.update(true,true);feedback.update(false,true);feedback.update(false,true);assertEquals(List.of(true,false),tones);}
    @Test public void mutingOrUnmutingDoesNotInventAnEdge(){List<Boolean> tones=new ArrayList<>();ActivationFeedback feedback=new ActivationFeedback(tones::add);feedback.update(true,false);feedback.update(true,true);feedback.update(false,false);feedback.update(false,true);assertTrue(tones.isEmpty());feedback.update(true,true);feedback.update(false,true);assertEquals(List.of(true,false),tones);}
    @Test public void waveformIsShortQuietMonoAndFadesAtBothEnds(){for(boolean entering:new boolean[]{true,false}){short[] pcm=ActivationTone.samples(entering);assertEquals(5760,pcm.length);assertEquals(0,pcm[0]);assertEquals(0,pcm[pcm.length-1]);int peak=0;for(short s:pcm)peak=Math.max(peak,Math.abs((int)s));assertTrue(peak>1000);assertTrue(peak<=1311);}}
}
