// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;

/** Native presentation only; session, permissions and gestures stay in their existing owners. */
final class LanAudioView extends ScrollView {
    static final int BG=CatchWaveUi.BG,TEXT=CatchWaveUi.TEXT,MUTED=CatchWaveUi.MUTED,ACCENT=CatchWaveUi.ACCENT,LINE=CatchWaveUi.LINE;
    final TextView status,modeStatus,modeDetail,volume,detail,statistics,sensors,controlStatus,action,guide,advanced;
    final Button mode,connect,scan,control,neutral,journal,export,forget,back,home,usb;
    final Switch gestures,sound,off;
    final ImageView tiltImage;
    private final LinearLayout body,help,diagnostics;
    private final TextView helpToggle;
    private boolean blockedBefore,connectedStyle=true;

    LanAudioView(Context context){
        super(context);setBackgroundColor(BG);setFillViewport(true);setVerticalScrollBarEnabled(false);
        body=column();body.setPadding(dp(22),dp(14),dp(22),dp(24));addView(body);
        setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;
        });requestApplyInsets();

        LinearLayout header=row();TextView brand=text("CatchWave",17,TEXT,true);
        SpannableString name=new SpannableString("CatchWave");name.setSpan(new ForegroundColorSpan(ACCENT),5,9,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);brand.setText(name);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        status=text(L.t("Not connected"),12,MUTED,false);status.setPadding(dp(10),dp(7),dp(10),dp(7));status.setBackground(shape(BG,24,LINE));
        home=button(L.t("Home"),false);home.setTextSize(12);header.addView(home,new LinearLayout.LayoutParams(dp(94),dp(44)));add(header,0);
        LinearLayout title=row();title.addView(text(L.t("PC audio"),26,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        iconText(status,R.drawable.ic_lan_volume_up,16,MUTED);title.addView(status);add(title,16);
        scan=button(L.t("Scan QR code"),false);connect=button(L.t("Connect"),false);
        LinearLayout pairingActions=row();pairingActions.addView(scan,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams connectionAction=new LinearLayout.LayoutParams(0,-2,1);connectionAction.leftMargin=dp(8);pairingActions.addView(connect,connectionAction);add(pairingActions,14);
        usb=button(L.t("Connect via USB"),false);add(usb,8);

        modeStatus=text(L.t("Connect your computer"),32,TEXT,true);modeStatus.setGravity(Gravity.CENTER);add(modeStatus,26);
        modeDetail=text("",14,MUTED,false);modeDetail.setGravity(Gravity.CENTER);modeDetail.setLineSpacing(dp(3),1);add(modeDetail,6);
        tiltImage=new ImageView(context);tiltImage.setImageResource(R.drawable.lan_tilt_guide);tiltImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tiltImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams illustration=new LinearLayout.LayoutParams(-1,dp(148));illustration.topMargin=dp(14);body.addView(tiltImage,illustration);
        LinearLayout directions=row();TextView left=text(L.t("Left — quieter"),14,TEXT,false),right=text(L.t("Right — louder"),14,TEXT,false);
        left.setGravity(Gravity.CENTER);right.setGravity(Gravity.CENTER);
        directions.addView(left,new LinearLayout.LayoutParams(0,-2,1));directions.addView(right,new LinearLayout.LayoutParams(0,-2,1));add(directions,0);
        mode=button(L.t("Activate control"),true);iconText(mode,R.drawable.ic_lan_power_settings_new,22,BG);add(mode,20);
        LinearLayout level=row();level.setGravity(Gravity.CENTER);
        TextView volumeLabel=text(L.t("Phone volume"),14,MUTED,false);iconText(volumeLabel,R.drawable.ic_lan_volume_up,20,MUTED);level.addView(volumeLabel);
        volume=text("",15,ACCENT,true);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.leftMargin=dp(8);level.addView(volume,vp);add(level,16);

        divider(body,18);
        LinearLayout first=row(),second=row();first.addView(tapCell("3",L.t("Pause")),new LinearLayout.LayoutParams(0,-2,1));first.addView(rule(true),new LinearLayout.LayoutParams(dp(1),dp(58)));
        first.addView(tapCell("4",L.t("Next")),new LinearLayout.LayoutParams(0,-2,1));add(first,0);
        divider(body,0);second.addView(tapCell("5",L.t("Previous")),new LinearLayout.LayoutParams(0,-2,1));second.addView(rule(true),new LinearLayout.LayoutParams(dp(1),dp(58)));
        second.addView(tapCell("6",L.t("Mute / restore")),new LinearLayout.LayoutParams(0,-2,1));add(second,0);divider(body,0);

        gestures=setting(body,L.t("Allow gestures"),L.t("Tilts and taps"),R.drawable.ic_lan_touch_app);
        sound=setting(body,L.t("Status sounds"),L.t("Sound on activation and deactivation"),R.drawable.ic_lan_notifications_none);
        divider(body,0);

        help=column();help.setVisibility(GONE);
        helpToggle=disclosure(body,L.t("How to enable control"),R.drawable.ic_lan_menu_book,help);
        guide=text("",14,MUTED,false);guide.setLineSpacing(dp(4),1);help.addView(guide);
        control=button(L.t("Allow control"),false);addTo(help,control,12);
        neutral=button(L.t("Reset activation and neutral"),false);addTo(help,neutral,8);
        off=setting(help,L.t("Gestures with the screen off"),L.t("Extra battery use"),R.drawable.ic_lan_touch_app);
        TextView toneHelp=text(L.t("Picking up and looking at the screen keep the mode and taps. After activation, one 1.5 s still setup is needed. When the pose changes, only the tilt direction is updated. A rising tone means on, a falling tone off. Sounds use media volume."),13,MUTED,false);
        toneHelp.setLineSpacing(dp(3),1);addTo(help,toneHelp,8);

        diagnostics=column();diagnostics.setVisibility(GONE);disclosure(body,L.t("Diagnostics"),R.drawable.ic_lan_graphic_eq,diagnostics);
        detail=diagnostic(diagnostics,L.t("Stream status"));statistics=diagnostic(diagnostics,L.t("Audio"));sensors=diagnostic(diagnostics,L.t("Sensors"));
        controlStatus=diagnostic(diagnostics,L.t("Permissions and activation"));action=diagnostic(diagnostics,L.t("Last action"));
        journal=button(L.t("Show log"),false);addTo(diagnostics,journal,14);
        export=button(L.t("Export diagnostics"),false);addTo(diagnostics,export,8);
        LinearLayout pairing=column();pairing.setVisibility(GONE);disclosure(diagnostics,L.t("Pairing with PC"),R.drawable.ic_lan_volume_up,pairing);
        advanced=diagnostic(pairing,L.t("Pairing details"));
        forget=button(L.t("Forget pairing"),false);addTo(pairing,forget,12);
        back=button(L.t("Back"),false);back.setTextSize(13);back.setTextColor(MUTED);back.setBackground(ripple(shape(BG,12,Color.TRANSPARENT)));add(back,12);
    }
    private LinearLayout tapCell(String count,String label){
        LinearLayout c=column();c.setPadding(dp(4),dp(10),dp(4),dp(10));c.setGravity(Gravity.CENTER);
        TextView n=text(count,28,TEXT,true),l=text(label,13,MUTED,false);n.setGravity(Gravity.CENTER);l.setGravity(Gravity.CENTER);
        c.addView(n);addTo(c,l,3);return c;
    }
    private Switch setting(LinearLayout parent,String title,String subtitle,int resource){
        LinearLayout r=row();r.setPadding(0,dp(12),0,dp(12));
        ImageView image=new ImageView(getContext());image.setImageDrawable(icon(resource,26,MUTED));image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(image,new LinearLayout.LayoutParams(dp(26),dp(26)));
        LinearLayout words=column();LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.leftMargin=dp(14);wp.rightMargin=dp(8);r.addView(words,wp);
        words.addView(text(title,15,TEXT,false));addTo(words,text(subtitle,12,MUTED,false),3);
        Switch toggle=new Switch(getContext());toggle.setContentDescription(title);toggle.setMinHeight(dp(48));toggle.setShowText(false);
        int[][] states={new int[]{android.R.attr.state_checked},new int[]{}};
        toggle.setThumbTintList(new ColorStateList(states,new int[]{ACCENT,MUTED}));
        toggle.setTrackTintList(new ColorStateList(states,new int[]{0xff4d6c2f,0xff465043}));
        r.addView(toggle);parent.addView(r);r.setOnClickListener(v->toggle.setChecked(!toggle.isChecked()));return toggle;
    }
    private TextView disclosure(LinearLayout parent,String title,int resource,LinearLayout content){
        divider(parent,18);TextView toggle=text(title,15,TEXT,false);toggle.setMinHeight(dp(52));toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(0,dp(12),0,dp(12));toggle.setCompoundDrawablePadding(dp(14));
        toggle.setCompoundDrawablesRelative(icon(resource,24,MUTED),null,icon(R.drawable.ic_lan_expand_more,22,MUTED),null);
        toggle.setBackground(ripple(shape(BG,8,Color.TRANSPARENT)));toggle.setFocusable(true);toggle.setContentDescription(title+L.t(", expand"));parent.addView(toggle);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(8);parent.addView(content,cp);
        toggle.setOnClickListener(v->{boolean open=content.getVisibility()!=VISIBLE;content.setVisibility(open?VISIBLE:GONE);toggle.setContentDescription(title+(open?L.t(", collapse"):L.t(", expand")));});return toggle;
    }
    private TextView diagnostic(LinearLayout parent,String title){
        addTo(parent,text(title,13,ACCENT,true),14);TextView value=text("",12,MUTED,false);value.setTypeface(Typeface.MONOSPACE);value.setTextIsSelectable(true);value.setLineSpacing(dp(3),1);addTo(parent,value,6);return value;
    }
    void showBlockedHelp(boolean blocked){
        if(blocked&&!blockedBefore){help.setVisibility(VISIBLE);helpToggle.setContentDescription(L.t("How to enable control, collapse"));}blockedBefore=blocked;
    }
    void connectionButtonStyle(boolean connected){
        if(connectedStyle==connected)return;connectedStyle=connected;
        connect.setBackground(ripple(shape(connected?BG:ACCENT,18,connected?MUTED:Color.TRANSPARENT)));connect.setTextColor(connected?TEXT:BG);
    }
    private TextView text(String value,float size,int color,boolean bold){
        TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return t;
    }
    private Button button(String value,boolean primary){
        return CatchWaveUi.button(getContext(),value,primary);
    }
    private Drawable icon(int resource,int size,int color){Drawable d=getContext().getDrawable(resource).mutate();d.setTint(color);d.setBounds(0,0,dp(size),dp(size));return d;}
    private void iconText(TextView t,int resource,int size,int color){t.setCompoundDrawablesRelative(icon(resource,size,color),null,null,null);t.setCompoundDrawablePadding(dp(8));}
    private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT)d.setStroke(dp(1),stroke);return d;}
    private Drawable ripple(Drawable content){return new RippleDrawable(ColorStateList.valueOf(0x33b9f66b),content,null);}
    private LinearLayout column(){LinearLayout c=new LinearLayout(getContext());c.setOrientation(LinearLayout.VERTICAL);return c;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(getContext());r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private View rule(boolean vertical){View v=new View(getContext());v.setBackgroundColor(LINE);v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);return v;}
    private void divider(LinearLayout parent,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.topMargin=dp(top);parent.addView(rule(false),p);}
    private void add(View view,int top){addTo(body,view,top);}
    private void addTo(LinearLayout parent,View view,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(view,p);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
