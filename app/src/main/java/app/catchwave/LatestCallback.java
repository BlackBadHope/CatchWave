// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.os.Handler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** At most one pending UI callback; network updates replace the latest snapshot. */
final class LatestCallback<T> {
    private final java.util.function.Function<Runnable,Boolean> post;private final Consumer<T> consume;
    private final AtomicReference<T> latest=new AtomicReference<>();
    private final AtomicBoolean pending=new AtomicBoolean();
    LatestCallback(Handler main,Consumer<T> consume){this(main::post,consume);}
    LatestCallback(java.util.function.Function<Runnable,Boolean> post,Consumer<T> consume){this.post=post;this.consume=consume;}
    void accept(T value){latest.set(value);queue();}
    private void queue(){
        if(!pending.compareAndSet(false,true))return;
        if(!post.apply(()->{T value=latest.get();try{consume.accept(value);}finally{pending.set(false);if(latest.get()!=value)queue();}}))pending.set(false);
    }
}
