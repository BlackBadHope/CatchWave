// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.util.function.Consumer;

/** Feedback follows the actual timed window, never the permission switch or sensor revisions. */
final class ActivationFeedback {
    private final Consumer<Boolean> player;private boolean active;
    ActivationFeedback(Consumer<Boolean> player){this.player=player;}
    void update(boolean value,boolean sound){if(value==active)return;active=value;if(sound)player.accept(value);}
}
