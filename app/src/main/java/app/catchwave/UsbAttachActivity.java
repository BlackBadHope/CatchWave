// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

/** External lifecycle cannot override an explicit Disconnect or grant gesture authority. */
public final class UsbAttachActivity extends UsbConnectActivity {
    @Override protected boolean autoAllowed(){
        LanState state=LanState.INSTANCE;
        return state.started&&new LanSettings(this).resume();
    }
}
