/* SPDX-License-Identifier: GPL-3.0-or-later
 * Java adaptation of SongRec fingerprinting by Marin M. and Audile contributors.
 * Upstream sources and changes are documented in THIRD_PARTY_NOTICES.md.
 * FFT implementation is an iterative radix-2 transform, implemented here.
 */
package app.catchwave;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

public final class Fingerprint {
    private final short[] ring = new short[2048];
    private final double[] re = new double[2048], im = new double[2048];
    private final float[][] fft = new float[256][1025], spread = new float[256][1025];
    private final List<int[]>[] peaks;
    private int ringIndex, fftIndex, spreadIndex, passes;
    @SuppressWarnings("unchecked") private Fingerprint() {
        peaks = new List[4]; for (int i=0;i<4;i++) peaks[i] = new ArrayList<>();
    }
    public static String generate(short[] samples) {
        if (samples.length < 16000 || samples.length > 192000) throw new IllegalArgumentException("Expected 1–12 seconds of mono PCM16 at 16 kHz");
        Fingerprint f = new Fingerprint();
        for (int pos=0; pos+128<=samples.length; pos+=128) f.process(samples,pos);
        return "data:audio/vnd.shazam.sig;base64,"+Base64.getEncoder().encodeToString(f.encode(samples.length));
    }
    private void process(short[] input, int pos) {
        System.arraycopy(input,pos,ring,ringIndex,128); ringIndex=(ringIndex+128)&2047;
        for (int i=0;i<2048;i++) { re[i]=ring[(i+ringIndex)&2047]*Hanning.VALUES[i]; im[i]=0; }
        transform(re,im);
        for (int i=0;i<=1024;i++) fft[fftIndex][i]=(float)Math.max((re[i]*re[i]+im[i]*im[i])/131072.0,1e-10);
        float[] current=spread[spreadIndex];
        System.arraycopy(fft[fftIndex],0,current,0,1025);
        fftIndex=(fftIndex+1)&255;
        for(int i=0;i<=1022;i++) current[i]=Math.max(current[i],Math.max(current[i+1],current[i+2]));
        for(int prev:new int[]{1,3,6}) {
            float[] old=spread[(spreadIndex-prev)&255];
            for(int i=0;i<=1024;i++) old[i]=Math.max(old[i],current[i]);
        }
        spreadIndex=(spreadIndex+1)&255;
        if(++passes>=46) findPeaks();
    }
    private void findPeaks() {
        float[] a=fft[(fftIndex-46)&255], b=spread[(spreadIndex-49)&255];
        for(int bin=10;bin<=1014;bin++) {
            if(a[bin]<1f/64 || a[bin]<b[bin-1]) continue;
            float maximum=0;
            for(int n:new int[]{-10,-7,-4,-3,1,2,5,8}) maximum=Math.max(maximum,b[bin+n]);
            if(a[bin]<=maximum) continue;
            for(int n:new int[]{-53,-45,165,172,179,186,193,200,214,221,228,235,242,249}) maximum=Math.max(maximum,spread[(spreadIndex+n)&255][bin-1]);
            if(a[bin]<=maximum) continue;
            float mag=logMagnitude(a[bin]), before=logMagnitude(a[bin-1]), after=logMagnitude(a[bin+1]);
            float curvature=mag*2-before-after;
            if(curvature<=0) continue;
            int corrected=bin*64+(int)((after-before)*32/curvature);
            int hz=(int)(corrected*(16000.0/2/1024/64));
            int band=hz>=250 && hz<520?0:hz>=520 && hz<1450?1:hz>=1450 && hz<3500?2:hz>=3500 && hz<=5500?3:-1;
            if(band>=0) peaks[band].add(new int[]{passes-46,(int)mag,corrected});
        }
    }
    private static float logMagnitude(float v) { return (float)Math.max(Math.log(v),1.0/64)*1477.3f+6144f; }
    private byte[] encode(int samples) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        int[] header={0xcafe2580,0,0,0x94119c00,0,0,0,3<<27,0,0,samples+3840,0x7c0000,0x40000000,0};
        for(int n:header) putInt(out,n);
        for(int band=0;band<4;band++) {
            if(peaks[band].isEmpty()) continue;
            ByteArrayOutputStream data=new ByteArrayOutputStream(); int previous=0;
            for(int[] p:peaks[band]) {
                if(p[0]-previous>=255) { data.write(255); putInt(data,p[0]); previous=p[0]; }
                data.write(p[0]-previous); putShort(data,p[1]); putShort(data,p[2]); previous=p[0];
            }
            putInt(out,0x60030040+band); putInt(out,data.size());
            byte[] bytes=data.toByteArray(); out.write(bytes,0,bytes.length);
            while(out.size()%4!=0) out.write(0);
        }
        byte[] result=out.toByteArray(); ByteBuffer b=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(8,result.length-48); b.putInt(52,result.length-48);
        CRC32 crc=new CRC32(); crc.update(result,8,result.length-8); b.putInt(4,(int)crc.getValue()); return result;
    }
    private static void putInt(ByteArrayOutputStream o,int n) { for(int i=0;i<4;i++) o.write(n>>>(8*i)); }
    private static void putShort(ByteArrayOutputStream o,int n) { o.write(n); o.write(n>>>8); }
    private static void transform(double[] real,double[] imaginary) {
        int n=real.length;
        for(int i=1,j=0;i<n;i++) {
            int bit=n>>>1; for(; (j&bit)!=0;bit>>>=1) j^=bit; j^=bit;
            if(i<j) { double t=real[i];real[i]=real[j];real[j]=t; }
        }
        for(int len=2;len<=n;len<<=1) {
            double angle=-2*Math.PI/len, wr0=Math.cos(angle),wi0=Math.sin(angle);
            for(int i=0;i<n;i+=len) {
                double wr=1,wi=0;
                for(int j=0;j<len/2;j++) {
                    int a=i+j,b=a+len/2;
                    double vr=real[b]*wr-imaginary[b]*wi,vi=real[b]*wi+imaginary[b]*wr;
                    real[b]=real[a]-vr;imaginary[b]=imaginary[a]-vi;real[a]+=vr;imaginary[a]+=vi;
                    double next=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=next;
                }
            }
        }
    }
}
