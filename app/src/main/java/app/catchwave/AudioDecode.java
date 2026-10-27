// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Decodes one local audio file to 16 kHz mono PCM chunks for indexing. Bounded, cancellable, device-only. */
final class AudioDecode {
    interface Sink { boolean pcm(short[] mono16k,int length); }
    private static final long SONG_DEADLINE_MS=180_000;
    private AudioDecode(){}

    /** True when at least some audio was decoded and the sink never cancelled. */
    static boolean decode(Context context,Uri uri,Sink sink){
        MediaExtractor extractor=new MediaExtractor();
        MediaCodec codec=null;
        try{
            extractor.setDataSource(context,uri,null);
            MediaFormat format=null;String mime=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat candidate=extractor.getTrackFormat(i);
                String type=candidate.getString(MediaFormat.KEY_MIME);
                if(type!=null&&type.startsWith("audio/")){extractor.selectTrack(i);format=candidate;mime=type;break;}
            }
            if(format==null)return false;
            codec=MediaCodec.createDecoderByType(mime);
            codec.configure(format,null,null,0);
            codec.start();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            PcmAdapter adapter=null;short[] out=null;
            boolean inputDone=false,delivered=false;
            long deadline=SystemClock.elapsedRealtime()+SONG_DEADLINE_MS;
            while(true){
                if(SystemClock.elapsedRealtime()>deadline)return false;
                if(!inputDone){
                    int in=codec.dequeueInputBuffer(10_000);
                    if(in>=0){
                        java.nio.ByteBuffer buffer=codec.getInputBuffer(in);
                        int size=buffer==null?-1:extractor.readSampleData(buffer,0);
                        if(size<0){codec.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
                        else{codec.queueInputBuffer(in,0,size,extractor.getSampleTime(),0);extractor.advance();}
                    }
                }
                int index=codec.dequeueOutputBuffer(info,10_000);
                if(index==MediaCodec.INFO_TRY_AGAIN_LATER)continue;
                if(index<0)continue;
                if(info.size>0){
                    MediaFormat current=codec.getOutputFormat(index);
                    int encoding=current.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ?current.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    int rate=current.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int channels=current.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if(encoding!=AudioFormat.ENCODING_PCM_16BIT){codec.releaseOutputBuffer(index,false);return false;}
                    if(adapter==null)adapter=new PcmAdapter(rate,channels);
                    java.nio.ByteBuffer buffer=codec.getOutputBuffer(index);
                    if(buffer!=null){
                        buffer.position(info.offset);buffer.limit(info.offset+info.size);
                        ShortBuffer shorts=buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                        short[] interleaved=new short[shorts.remaining()];
                        shorts.get(interleaved);
                        if(out==null||out.length<adapter.outputCapacity(interleaved.length))out=new short[adapter.outputCapacity(interleaved.length)];
                        int produced=adapter.convert(interleaved,interleaved.length,out);
                        if(produced>0){
                            delivered=true;
                            if(!sink.pcm(out,produced)){codec.releaseOutputBuffer(index,false);return false;}
                        }
                    }
                }
                boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                codec.releaseOutputBuffer(index,false);
                if(end)return delivered;
            }
        }catch(IOException|RuntimeException broken){return false;}
        finally{
            if(codec!=null){try{codec.stop();}catch(RuntimeException ignored){}codec.release();}
            extractor.release();
        }
    }
}
