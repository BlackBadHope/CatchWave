package app.catchwave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Confirms a source clock, not acoustic alignment. All times use the microphone's boot clock. */
final class SourceTimeline {
    static final int REQUIRED=3;
    static final long SPREAD_MS=120, MAX_AGE_MS=60000;
    enum State { WAITING, CONFIRMED, IGNORED, RATE_MISMATCH }
    static final class Result {
        final State state;
        final Track track;
        final int count;
        final long spreadMs,spanMs;
        final double rate;
        Result(State state,Track track,int count,long spread,long span,double rate){
            this.state=state;this.track=track;this.count=count;spreadMs=spread;spanMs=span;this.rate=rate;
        }
    }
    private final List<Track> history=new ArrayList<>();
    private String key="";
    private long lastAnchor=Long.MIN_VALUE,lastEnd=Long.MIN_VALUE;
    private Long confirmedBias;
    void reset(){history.clear();key="";lastAnchor=lastEnd=Long.MIN_VALUE;confirmedBias=null;}
    boolean hasSamples(){return !history.isEmpty();}
    boolean isTracking(String value){return key.equals(value);}
    private static long bias(Track track){return track.offsetMs-track.anchorMs;}
    Result offer(Track sample){
        // Repeated/overlapping audio cannot count as independent corroboration.
        if(!sample.hasOffset()||sample.sampleDurationMs<3000||sample.anchorMs<0||
            sample.anchorMs<=lastAnchor||lastEnd!=Long.MIN_VALUE&&sample.anchorMs<lastEnd-20)
            return new Result(State.IGNORED,null,0,0,0,Double.NaN);
        if(!key.equals(sample.key)){history.clear();confirmedBias=null;key=sample.key;}
        else if(!history.isEmpty()){
            Track prev=history.get(history.size()-1);
            long elapsed=sample.anchorMs-prev.anchorMs;
            if(elapsed>0&&Math.abs(sample.offsetMs-(prev.offsetMs+elapsed))>2000){
                history.clear();confirmedBias=null;
            }
        }
        lastAnchor=sample.anchorMs;lastEnd=sample.anchorMs+sample.sampleDurationMs;
        history.removeIf(t->sample.anchorMs-t.anchorMs>MAX_AGE_MS);
        history.add(sample);if(history.size()>16)history.remove(0);
        List<Track> recent=new ArrayList<>(history.subList(Math.max(0,history.size()-5),history.size()));
        recent.sort(Comparator.comparingLong(SourceTimeline::bias));
        List<Track> best=new ArrayList<>();long bestSpread=Long.MAX_VALUE;
        for(Track lower:recent){
            List<Track> cluster=new ArrayList<>();
            for(Track t:recent)if(bias(t)>=bias(lower)&&bias(t)-bias(lower)<=SPREAD_MS)cluster.add(t);
            if(!cluster.contains(sample))continue; // Old agreement must never mask a new source position.
            long spread=bias(cluster.get(cluster.size()-1))-bias(cluster.get(0));
            if(cluster.size()>best.size()||cluster.size()==best.size()&&spread<bestSpread){best=cluster;bestSpread=spread;}
        }
        long earliest=sample.anchorMs;
        for(Track t:best)earliest=Math.min(earliest,t.anchorMs);
        long span=sample.anchorMs-earliest;
        if(best.size()<REQUIRED||span<5980)
            return new Result(State.WAITING,null,best.size(),bestSpread,span,Double.NaN);
        int middle=best.size()/2;
        long medianBias=best.size()%2==1?bias(best.get(middle)):
            Math.round((bias(best.get(middle-1))+bias(best.get(middle)))/2.0);
        // A stable new clock after a seek is different from a gradual change of tempo.
        if(confirmedBias!=null&&Math.abs(medianBias-confirmedBias)>250){
            history.retainAll(best);
        }
        double rate=rate(history);
        long rateSpan=sample.anchorMs-history.get(0).anchorMs;
        // Short clips cannot establish a small speed error: allow 60 ms of rate-estimation uncertainty.
        if(history.size()>=8&&rateSpan>=21000&&Math.abs(rate-1)>0.005+60.0/rateSpan)
            return new Result(State.RATE_MISMATCH,null,best.size(),bestSpread,rateSpan,rate);
        confirmedBias=medianBias;
        Track confirmed=new Track(sample.key,sample.title,sample.artist,sample.appleId,
            sample.anchorMs+medianBias,sample.anchorMs,sample.timeSkew);
        confirmed.sampleDurationMs=sample.sampleDurationMs;
        confirmed.youtubeUrl=sample.youtubeUrl;confirmed.playbackTitle=sample.playbackTitle;confirmed.playbackArtist=sample.playbackArtist;
        return new Result(State.CONFIRMED,confirmed,best.size(),bestSpread,span,rate);
    }
    private static double rate(List<Track> samples){
        List<Double> slopes=new ArrayList<>();
        for(int i=0;i<samples.size();i++)for(int j=i+1;j<samples.size();j++){
            Track a=samples.get(i),b=samples.get(j);long elapsed=b.anchorMs-a.anchorMs;
            if(elapsed>=5980)slopes.add((b.offsetMs-a.offsetMs)/(double)elapsed);
        }
        if(slopes.isEmpty())return Double.NaN;
        slopes.sort(Double::compare);int middle=slopes.size()/2;
        return slopes.size()%2==1?slopes.get(middle):(slopes.get(middle-1)+slopes.get(middle))/2;
    }
}
