// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.content.Context;
import java.nio.file.Path;

/** Owns the on-phone library index file: lazy load, save, clear. All data stays in private storage. */
final class LibraryStore {
    private static volatile LibraryStore shared;
    private final Path file;
    private volatile LibraryIndex index;

    static LibraryStore get(Context context){
        LibraryStore current=shared;
        if(current==null)synchronized(LibraryStore.class){
            current=shared;
            if(current==null)shared=current=new LibraryStore(context.getFilesDir().toPath().resolve("library.idx"));
        }
        return current;
    }
    LibraryStore(Path file){this.file=file;}

    LibraryIndex index(){
        LibraryIndex current=index;
        if(current==null)synchronized(this){
            current=index;
            if(current==null)index=current=LibraryIndex.load(file);
        }
        return current;
    }
    Track recognize(short[] audio,long anchorMs){
        LibraryIndex current=index();
        if(current.songCount()==0)return null;
        return new LocalRecognizer(current).recognize(audio,anchorMs);
    }
    void save(){
        try{index().save(file);}catch(Exception ignored){/* The in-memory index keeps working. */}
    }
    void clear(){
        index().clear();
        try{java.nio.file.Files.deleteIfExists(file);}catch(Exception ignored){}
    }
    int songCount(){return index().songCount();}
}
