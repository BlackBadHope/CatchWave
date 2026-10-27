// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/**
 * Offline recognition against the user's own indexed library. No network, no catalog,
 * no fingerprint leaves the phone. A weak or ambiguous vote returns nothing: the caller
 * falls back to the online recognizer, and SourceTimeline still demands consistent
 * measurements before anything is trusted.
 */
final class LocalRecognizer {
    static final int MIN_SCORE=14;
    static final double MIN_ADVANTAGE=2.0;
    private final LibraryIndex index;
    LocalRecognizer(LibraryIndex index){this.index=index;}

    Track recognize(short[] audio,long anchorMs){
        if(index==null||index.songCount()==0)return null;
        LibraryIndex.Match match=index.query(audio);
        if(match==null||match.score<MIN_SCORE)return null;
        if(match.runnerUp>0&&match.score<match.runnerUp*MIN_ADVANTAGE)return null;
        if(match.song.durationMs>0&&match.offsetMs>match.song.durationMs)return null;
        Track track=new Track("local:"+match.song.mediaId,match.song.title,match.song.artist,"",
            match.offsetMs,anchorMs,0);
        if(audio!=null)track.sampleDurationMs=audio.length/16;
        return track;
    }
}
