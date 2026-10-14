// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.app.*;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Shared native presentation. No network, player, sensor or permission side effects. */
final class CatchWaveUi {
    static final int BG=0xff101510,TEXT=0xffeef5e6,MUTED=0xffa4b19f,ACCENT=0xffb9f66b,LINE=0xff364333,PANEL=0xff1d241d;
    static int dp(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static TextView text(Context c,String value,float size,int color,boolean bold){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setLineSpacing(dp(c,3),1);return t;
    }
    static GradientDrawable shape(Context c,int color,int radius,int border){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(border!=0)d.setStroke(dp(c,1),border);return d;
    }
    static Button button(Context c,String value,boolean primary){
        Button b=new Button(c);b.setText(value);b.setAllCaps(false);b.setTextSize(16);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setIncludeFontPadding(false);b.setMinWidth(0);b.setMinHeight(dp(c,48));b.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));
        b.setTextColor(primary?BG:TEXT);b.setBackgroundTintList(null);b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33b9f66b),shape(c,primary?ACCENT:BG,primary?28:18,primary?0:LINE),null));return b;
    }
    static LinearLayout column(Context c){LinearLayout b=new LinearLayout(c);b.setOrientation(LinearLayout.VERTICAL);return b;}
    static void add(LinearLayout parent,View v,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(parent.getContext(),top);parent.addView(v,p);}
    static ScrollView page(Activity a,LinearLayout body){
        ScrollView s=new ScrollView(a);s.setBackgroundColor(BG);s.setFillViewport(true);s.setVerticalScrollBarEnabled(false);
        body.setPadding(dp(a,22),dp(a,14),dp(a,22),dp(a,24));s.addView(body);
        s.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());s.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
        a.setContentView(s);s.requestApplyInsets();return s;
    }
    static void header(Activity a,LinearLayout body,Runnable back){
        LinearLayout r=new LinearLayout(a);r.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text(a,"CatchWave",18,ACCENT,true);r.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        if(back!=null){Button home=button(a,L.t("Home"),false);home.setTextSize(12);home.setOnClickListener(v->back.run());r.addView(home,new LinearLayout.LayoutParams(dp(a,94),dp(a,44)));}
        add(body,r,0);
    }
    static void divider(LinearLayout body,int margin){View rule=new View(body.getContext());rule.setBackgroundColor(LINE);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(body.getContext(),1));p.topMargin=dp(body.getContext(),margin);body.addView(rule,p);}
    static AlertDialog.Builder dialog(Context c){return new AlertDialog.Builder(c,R.style.AppDialogTheme);}
    static void styleSwitch(Switch s){
        int[][] states={new int[]{android.R.attr.state_checked},new int[]{}};
        s.setThumbTintList(new ColorStateList(states,new int[]{ACCENT,MUTED}));
        s.setTrackTintList(new ColorStateList(states,new int[]{0xff4d6c2f,0xff465043}));s.setMinHeight(dp(s.getContext(),48));
    }
    static void home(Activity a){a.startActivity(new android.content.Intent(a,HomeActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));}
}
