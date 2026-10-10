// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
final class GestureGate {
    static boolean visibleSession(boolean connected,boolean enabled,boolean visible,boolean screenOff){return connected&&enabled&&(visible||screenOff);}
    static boolean freshSample(long now,long timestamp){return timestamp>=0&&timestamp<=now&&now-timestamp<=150;}
    private GestureGate(){}
}
