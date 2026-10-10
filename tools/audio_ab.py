#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline owned-fixture preparation/analysis only. Never plays, records or opens a device."""
import argparse, array, hashlib, json, math, statistics, struct, tempfile, wave
from pathlib import Path

MARKERS=(10.0,11.7,13.6,16.0,18.8,21.0)

def fixture(path, rate=48000):
    data=bytearray()
    for i in range(30*rate):
        t=i/rate; left=right=0.0
        if 1<=t<3:left=.05*math.sin(2*math.pi*997*t)
        elif 3<=t<5:right=.05*math.sin(2*math.pi*1499*t)
        elif 5<=t<9:left=right=.05*math.sin(2*math.pi*1000*t)
        elif 23<=t<29:
            left=.05*math.sin(2*math.pi*997*t);right=.05*math.sin(2*math.pi*1499*t)
        else:
            for at in MARKERS:
                dt=t-at
                if 0<=dt<.1:
                    left=right=.05*math.sin(math.pi*dt/.1)**2*math.sin(2*math.pi*(600*dt+15000*dt*dt));break
        for value in (left,right):data.extend(int(round(value*8388607)).to_bytes(3,'little',signed=True))
    with wave.open(str(path),'wb') as out:
        out.setnchannels(2);out.setsampwidth(3);out.setframerate(rate);out.writeframes(data)
    meta={'ownedSynthetic':True,'played':False,'seconds':30,'rate':rate,'channels':2,'bits':24,
          'peakNominalDbFS':20*math.log10(.05),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
          'segments':{'leftOnly':[1,3],'rightOnly':[3,5],'tone1000Hz':[5,9],'markerOnsets':MARKERS,'continuity':[23,29]}}
    path.with_suffix('.json').write_text(json.dumps(meta,indent=2),encoding='utf-8');return meta

def read(path,start,seconds):
    if start<0 or not 0<seconds<=300:raise ValueError('Use a bounded positive segment <=300 seconds')
    with wave.open(str(path),'rb') as source:
        rate=source.getframerate();channels=source.getnchannels();width=source.getsampwidth()
        if source.getcomptype()!='NONE' or width not in (1,2,3,4):raise ValueError('Need integer PCM WAV (8/16/24/32 bits)')
        source.setpos(min(source.getnframes(),round(start*rate)));raw=source.readframes(round(seconds*rate))
    if not raw:raise ValueError('Empty segment')
    result=[array.array('d') for _ in range(channels)];scale=float(1<<(width*8-1))
    for frame in range(len(raw)//(width*channels)):
        for c in range(channels):
            at=(frame*channels+c)*width
            value=raw[at]-128 if width==1 else int.from_bytes(raw[at:at+width],'little',signed=True)
            result[c].append(value/scale)
    return rate,width*8,result

def rms(values):return math.sqrt(sum(v*v for v in values)/len(values)) if values else 0.0
def db(value):return 20*math.log10(value) if value>0 else None
def percentile(values,p):
    if not values:return None
    values=sorted(values);at=(len(values)-1)*p;low=int(at)
    return values[low]+(values[min(low+1,len(values)-1)]-values[low])*(at-low)

def tone_fit(values,rate,nominal):
    # Joint sine/cosine/DC least-squares, with frequency search to avoid lab-clock drift bias.
    mean=statistics.fmean(values);y=[v-mean for v in values];energy=sum(v*v for v in y);n=len(y)
    def fit(freq):
        sy=cy=ss=cc=sc=smean=cmean=0.0
        for i,value in enumerate(y):
            a=2*math.pi*freq*i/rate;s=math.sin(a);c=math.cos(a)
            sy+=value*s;cy+=value*c;ss+=s*s;cc+=c*c;sc+=s*c;smean+=s;cmean+=c
        ss-=smean*smean/n;cc-=cmean*cmean/n;sc-=smean*cmean/n
        det=ss*cc-sc*sc
        if det<=0:return float('inf'),0.0
        a=(sy*cc-cy*sc)/det;b=(cy*ss-sy*sc)/det;fund=a*sy+b*cy
        return max(0.0,energy-fund),fund
    best=nominal;noise,fund=fit(best)
    for step in (.2,.02,.002,.0002,.00002,.000002,.0000002):
        center=best
        for k in range(-10,11):
            f=center+k*step;candidate,signal=fit(f)
            if candidate<noise:best,noise,fund=f,candidate,signal
    return {'fittedHz':best,'thdPlusNoiseDb':db(math.sqrt(noise/fund)) if fund>0 else None,
            'fitSeconds':n/rate,'includesNoiseAndHarmonics':True}

def stats(path,start,seconds,tone=None,continuous=False,expected_rms_dbfs=None):
    rate,bits,channels=read(path,start,seconds);result=[]
    for values in channels:
        item={'rmsDbFS':db(rms(values)),'peakDbFS':db(max(abs(v) for v in values)),
              'digitalFullScaleSamples':sum(v>=1-1/(1<<(bits-1)) or v<=-1 for v in values)}
        if tone:
            # The caller selects a steady tone interval; use at most 0.5 s to limit analysis time.
            item.update(tone_fit(values[:round(rate*.5)],rate,tone))
        if continuous:
            bins=[rms(values[i:i+rate//100]) for i in range(0,len(values),rate//100)]
            if expected_rms_dbfs is not None and not -140<=expected_rms_dbfs<=0:raise ValueError('Expected RMS dBFS must be in [-140,0]')
            normal=10**(expected_rms_dbfs/20) if expected_rms_dbfs is not None else percentile(bins,.9)
            invalid=normal<=1e-12;limit=normal*.01;durations=None if invalid else [];run=0
            if not invalid:
                for value in bins+[normal]:
                    if value<limit:run+=10
                    elif run:durations.append(run);run=0
            item.update({'nearSilenceThresholdDbFS':db(limit),'nearSilenceRunsMs':durations,
                         'invalidReferenceLevel':invalid,'signalMissing':max(bins)<=max(1e-12,limit),
                         'levelSource':'provided calibrated RMS' if expected_rms_dbfs is not None else 'inferred p90; not an acceptance reference',
                         'note':'Candidate gaps at 10ms resolution; acceptance needs calibrated expected level. Null/invalid is not zero dropouts.'})
        result.append(item)
    return {'rate':rate,'bits':bits,'channels':len(channels),'segmentStart':start,'analyzedSeconds':len(channels[0])/rate,'channelStats':result}

def onsets(values,rate):
    size=max(1,round(rate/1000));levels=[rms(values[i:i+size]) for i in range(0,len(values),size)]
    cut=max(levels)*.25
    if cut<=0:return []
    times=[];armed=True;last=-1.0
    for i,value in enumerate(levels):
        at=i*size/rate
        if armed and value>=cut and at-last>.4:times.append(at);last=at;armed=False
        elif value<cut/2:armed=True
    return times

def latency(path,start,seconds,reference,output,max_delay=1.0):
    rate,bits,channels=read(path,start,seconds)
    if reference==output or not (0<=reference<len(channels) and 0<=output<len(channels)):raise ValueError('Need distinct common-clock reference/output channels')
    a=onsets(channels[reference],rate);b=onsets(channels[output],rate);delays=[];missed=[];used=set()
    for at in a:
        candidates=[(j,t) for j,t in enumerate(b) if j not in used and 0<=t-at<=max_delay]
        if not candidates:missed.append(at+start);continue
        j,t=candidates[0];used.add(j);delays.append(1000*(t-at))
    return {'method':'RMS marker onset, common ADC clock; output minus reference',
            'resolutionMs':1000*round(rate/1000)/rate,'referenceMarkers':len(a),'outputMarkers':len(b),
            'matchedMarkers':len(delays),'missingReferenceMarkerTimes':missed,'extraOutputMarkers':len(b)-len(used),
            'delayMs':delays,'p50Ms':percentile(delays,.5),'p95Ms':percentile(delays,.95),'p99Ms':percentile(delays,.99),
            'maxMs':max(delays) if delays else None,'p95MinusP50Ms':(percentile(delays,.95)-percentile(delays,.5)) if delays else None,
            'warning':'Not RTT. Requires isolated owned marker window, common-clock acquisition and zero-path calibration. Threshold/noise adds uncertainty. Reject ambiguous/noisy matching.'}

def self_test():
    with tempfile.TemporaryDirectory(prefix='CatchWave-AB-') as temp:
        p=Path(temp)/'common.wav';rate=48000;n=rate*4;delay=rate//10
        reference=[0.0]*n
        for at in (1,2,3):
            for i in range(rate//10):reference[at*rate+i]=.05*math.sin(math.pi*i/(rate//10))**2*math.sin(2*math.pi*1000*i/rate)
        output=[0.0]*delay+reference[:-delay];pcm=bytearray()
        for a,b in zip(reference,output):pcm.extend(struct.pack('<hh',round(a*32767),round(b*32767)))
        with wave.open(str(p),'wb') as out:out.setnchannels(2);out.setsampwidth(2);out.setframerate(rate);out.writeframes(pcm)
        report=latency(p,0,4,0,1);assert report['matchedMarkers']==3 and abs(report['p50Ms']-100)<1.1
        clean=[.05*math.sin(2*math.pi*1000*i/rate) for i in range(rate//10)]
        bad=[v+.0005*math.sin(2*math.pi*2000*i/rate) for i,v in enumerate(clean)]
        assert tone_fit(clean,rate,1000)['thdPlusNoiseDb'] is None or tone_fit(clean,rate,1000)['thdPlusNoiseDb']<-100
        assert abs(tone_fit(bad,rate,1000)['thdPlusNoiseDb']+40)<.1
        # Missing output marker must be reported rather than matched to the next isolated burst.
        pcm=bytearray()
        for i,(a,b) in enumerate(zip(reference,output)):
            if 2.1<=i/rate<2.2:b=0
            pcm.extend(struct.pack('<hh',round(a*32767),round(b*32767)))
        with wave.open(str(p),'wb') as out:out.setnchannels(2);out.setsampwidth(2);out.setframerate(rate);out.writeframes(pcm)
        assert latency(p,0,4,0,1,.5)['matchedMarkers']==2
        # Total loss must not look like zero dropouts when no signal-derived level exists.
        with wave.open(str(p),'wb') as out:out.setnchannels(1);out.setsampwidth(2);out.setframerate(rate);out.writeframes(bytes(rate//4*2))
        item=stats(p,0,.25,continuous=True)['channelStats'][0]
        assert item['signalMissing'] and item['invalidReferenceLevel'] and item['nearSilenceRunsMs'] is None
        item=stats(p,0,.25,continuous=True,expected_rms_dbfs=-30)['channelStats'][0]
        assert item['signalMissing'] and not item['invalidReferenceLevel'] and item['nearSilenceRunsMs']==[250]
        off_grid=[.05*math.sin(2*math.pi*1000.0333*i/rate) for i in range(rate//2)]
        fitted=tone_fit(off_grid,rate,1000)
        assert abs(fitted['fittedHz']-1000.0333)<.00001
        assert fitted['thdPlusNoiseDb'] is None or fitted['thdPlusNoiseDb']<-110
    return {'selfTest':'pass','syntheticChecks':6,'played':False,'recorded':False,'qualityParity':'unmeasured'}

def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='action',required=True)
    create=sub.add_parser('fixture');create.add_argument('path',type=Path);create.add_argument('--rate',type=int,choices=(44100,48000,96000),default=48000)
    for action in ('stats','latency'):
        p=sub.add_parser(action);p.add_argument('path',type=Path);p.add_argument('--start',type=float,default=0);p.add_argument('--seconds',type=float,required=True)
        if action=='stats':p.add_argument('--tone',type=float);p.add_argument('--expect-continuous',action='store_true');p.add_argument('--expected-rms-dbfs',type=float)
        else:p.add_argument('--reference',type=int,default=0);p.add_argument('--output',type=int,default=1);p.add_argument('--max-delay',type=float,default=1.0)
    sub.add_parser('self-test');args=parser.parse_args()
    if args.action=='fixture':result=fixture(args.path,args.rate)
    elif args.action=='stats':result=stats(args.path,args.start,args.seconds,args.tone,args.expect_continuous,args.expected_rms_dbfs)
    elif args.action=='latency':result=latency(args.path,args.start,args.seconds,args.reference,args.output,args.max_delay)
    else:result=self_test()
    print(json.dumps(result,indent=2,allow_nan=False))

if __name__=='__main__':main()
