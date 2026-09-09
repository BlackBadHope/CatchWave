package app.catchwave;

/** GCC-PHAT compares two independent audio windows. It does not measure a muted speaker's latency. */
final class AudioAlignment {
    static final int RATE=16000,WINDOW=48000;
    static final class Estimate {
        final boolean valid;final double lagMs,confidence,windowDifferenceMs;
        Estimate(boolean valid,double lag,double confidence,double difference){this.valid=valid;lagMs=lag;this.confidence=confidence;windowDifferenceMs=difference;}
    }
    static Estimate compare(short[] reference,long referenceAnchor,short[] microphone,long microphoneAnchor){
        if(reference.length<WINDOW*2||microphone.length<WINDOW*2||Math.abs(microphoneAnchor-referenceAnchor)>500)return new Estimate(false,0,0,0);
        double[] first=window(reference,microphone,0),second=window(reference,microphone,WINDOW);
        double difference=Math.abs(first[0]-second[0]);
        // Positive lag means the phone's digital music is behind the external microphone signal.
        double lag=-(microphoneAnchor-referenceAnchor+(first[0]+second[0])/2);
        return new Estimate(first[1]>=1.45&&second[1]>=1.45&&difference<=12&&Math.abs(lag)<=1200,lag,Math.min(first[1],second[1]),difference);
    }
    private static double[] window(short[] x,short[] y,int start){
        int n=1;while(n<WINDOW*2)n<<=1;
        double[] xr=new double[n],xi=new double[n],yr=new double[n],yi=new double[n];double ex=0,ey=0;
        for(int i=0;i<WINDOW;i++){
            double a=x[start+i]/32768.0,b=y[start+i]/32768.0,h=.5-.5*Math.cos(2*Math.PI*i/(WINDOW-1));
            xr[i]=a*h;yr[i]=b*h;ex+=a*a;ey+=b*b;
        }
        if(Math.sqrt(ex/WINDOW)<.001||Math.sqrt(ey/WINDOW)<.0008)return new double[]{0,0};
        fft(xr,xi,false);fft(yr,yi,false);
        for(int i=0;i<n;i++){
            double frequency=Math.min(i,n-i)*(double)RATE/n;
            double real=yr[i]*xr[i]+yi[i]*xi[i],imag=yi[i]*xr[i]-yr[i]*xi[i],magnitude=Math.hypot(real,imag);
            if(frequency<150||frequency>6000||magnitude<1e-10){xr[i]=xi[i]=0;}
            else{xr[i]=real/magnitude;xi[i]=imag/magnitude;}
        }
        fft(xr,xi,true);
        int best=0,range=RATE;double peak=0,energy=0;
        for(int lag=-range;lag<=range;lag++){double v=Math.abs(xr[lag<0?n+lag:lag]);energy+=v*v;if(v>peak){peak=v;best=lag;}}
        double second=0;
        for(int lag=-range;lag<=range;lag++)if(Math.abs(lag-best)>800)second=Math.max(second,Math.abs(xr[lag<0?n+lag:lag]));
        double snr=peak/Math.sqrt(energy/(2*range+1)+1e-20);
        if(Math.abs(best)>=range-2||snr<8)return new double[]{0,0};
        int index=best<0?n+best:best;
        double left=Math.abs(xr[(index+n-1)%n]),right=Math.abs(xr[(index+1)%n]),denominator=left-2*peak+right;
        double fraction=Math.abs(denominator)<1e-12?0:Math.max(-.5,Math.min(.5,.5*(left-right)/denominator));
        return new double[]{(best+fraction)*1000/RATE,peak/(second+1e-20)};
    }
    private static void fft(double[] real,double[] imaginary,boolean inverse){
        int n=real.length;
        for(int i=1,j=0;i<n;i++){int bit=n>>>1;for(;(j&bit)!=0;bit>>>=1)j^=bit;j^=bit;if(i<j){double v=real[i];real[i]=real[j];real[j]=v;v=imaginary[i];imaginary[i]=imaginary[j];imaginary[j]=v;}}
        for(int length=2;length<=n;length<<=1){
            double angle=(inverse?2:-2)*Math.PI/length,baseR=Math.cos(angle),baseI=Math.sin(angle);
            for(int i=0;i<n;i+=length){double wr=1,wi=0;for(int j=0;j<length/2;j++){
                int a=i+j,b=a+length/2;double tr=wr*real[b]-wi*imaginary[b],ti=wr*imaginary[b]+wi*real[b];
                real[b]=real[a]-tr;imaginary[b]=imaginary[a]-ti;real[a]+=tr;imaginary[a]+=ti;
                double next=wr*baseR-wi*baseI;wi=wr*baseI+wi*baseR;wr=next;
            }}
        }
        if(inverse)for(int i=0;i<n;i++){real[i]/=n;imaginary[i]/=n;}
    }
}
