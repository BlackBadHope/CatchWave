// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Local history of caught songs. Reading it never starts the microphone or any network request. */
public final class MomentsActivity extends Activity {
    private MomentStore store;
    private LinearLayout list;
    private TextView empty;
    private Button exportAll,clearAll;
    @Override public void onCreate(Bundle saved){L.init(this);
        super.onCreate(saved);store=MomentStore.get(this);
        LinearLayout body=CatchWaveUi.column(this);CatchWaveUi.page(this,body);CatchWaveUi.header(this,body,()->CatchWaveUi.home(this));
        CatchWaveUi.add(body,CatchWaveUi.text(this,L.t("Music moments"),28,CatchWaveUi.TEXT,true),24);
        CatchWaveUi.add(body,CatchWaveUi.text(this,L.t("Every catch is saved here: the song, the moment inside it and the time. The list lives only on this phone."),14,CatchWaveUi.MUTED,false),8);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        exportAll=CatchWaveUi.button(this,L.t("Share list"),false);exportAll.setTextSize(13);exportAll.setOnClickListener(v->shareAll());
        clearAll=CatchWaveUi.button(this,L.t("Clear"),false);clearAll.setTextSize(13);clearAll.setOnClickListener(v->confirmClear());
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,-2,1);
        LinearLayout.LayoutParams halfRight=new LinearLayout.LayoutParams(0,-2,1);halfRight.leftMargin=CatchWaveUi.dp(this,8);
        actions.addView(exportAll,half);actions.addView(clearAll,halfRight);
        CatchWaveUi.add(body,actions,14);
        empty=CatchWaveUi.text(this,L.t("No moments yet. Catch a song on the «Music around you» screen and it will appear here."),14,CatchWaveUi.MUTED,false);
        CatchWaveUi.add(body,empty,18);
        list=CatchWaveUi.column(this);CatchWaveUi.add(body,list,6);
        render();
    }
    private void render(){
        List<Moment> all=store.all();
        empty.setVisibility(all.isEmpty()?View.VISIBLE:View.GONE);
        exportAll.setEnabled(!all.isEmpty());clearAll.setEnabled(!all.isEmpty());
        list.removeAllViews();
        DateFormat when=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
        for(Moment m:all)list.addView(card(m,when),new LinearLayout.LayoutParams(-1,-2){{topMargin=CatchWaveUi.dp(MomentsActivity.this,10);}});
    }
    private View card(Moment m,DateFormat when){
        LinearLayout panel=CatchWaveUi.column(this);
        panel.setPadding(dp(14),dp(12),dp(14),dp(12));
        panel.setBackground(CatchWaveUi.shape(this,CatchWaveUi.PANEL,16,0));
        TextView title=CatchWaveUi.text(this,m.title+"\n"+m.artist,16,CatchWaveUi.TEXT,true);panel.addView(title);
        TextView meta=CatchWaveUi.text(this,L.t("At ")+m.positionLabel()+" · "+when.format(new Date(m.caughtAtMs)),12,CatchWaveUi.MUTED,false);
        CatchWaveUi.add(panel,meta,4);
        if(!m.note.isEmpty()){
            TextView note=CatchWaveUi.text(this,m.note,13,CatchWaveUi.TEXT,false);note.setTypeface(null,Typeface.ITALIC);
            CatchWaveUi.add(panel,note,6);
        }
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(small(m.favorite?"★":"☆",L.t("Favorite"),v->{store.setFavorite(m.id,!m.favorite);render();}));
        row.addView(small(L.t("Open"),L.t("Open in YouTube Music"),v->open(m)));
        row.addView(small(L.t("Note"),L.t("Edit note"),v->editNote(m)));
        row.addView(small(L.t("Share"),L.t("Share this moment"),v->share(m.shareText())));
        row.addView(small("✕",L.t("Delete"),v->{store.delete(m.id);render();}));
        CatchWaveUi.add(panel,row,8);
        return panel;
    }
    private Button small(String label,String description,View.OnClickListener action){
        Button b=CatchWaveUi.button(this,label,false);b.setTextSize(12);b.setMinHeight(dp(40));b.setPadding(dp(10),dp(6),dp(10),dp(6));
        b.setContentDescription(description);b.setOnClickListener(action);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.rightMargin=dp(6);b.setLayoutParams(p);
        return b;
    }
    private void open(Moment m){
        Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(m.link()));
        try{startActivity(new Intent(intent).setPackage(MediaBridge.PACKAGE));}
        catch(ActivityNotFoundException noPlayer){
            try{startActivity(intent);}
            catch(ActivityNotFoundException noBrowser){Toast.makeText(this,L.t("No app can open this link."),Toast.LENGTH_SHORT).show();}
        }
    }
    private void editNote(Moment m){
        EditText input=new EditText(this);
        input.setText(m.note);input.setTextColor(CatchWaveUi.TEXT);input.setHint(L.t("Where and why you caught it"));
        input.setHintTextColor(CatchWaveUi.MUTED);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Moment.NOTE_LIMIT)});
        LinearLayout box=CatchWaveUi.column(this);box.setPadding(dp(20),dp(8),dp(20),dp(8));box.addView(input);
        CatchWaveUi.dialog(this).setTitle(L.t("Note"))
            .setView(box)
            .setPositiveButton(L.t("Save"),(d,w)->{store.setNote(m.id,input.getText().toString().trim());render();})
            .setNegativeButton(L.t("Cancel"),null).show();
    }
    private void confirmClear(){
        CatchWaveUi.dialog(this).setTitle(L.t("Clear the whole history?"))
            .setMessage(L.t("Every moment, favorite and note will be deleted from this phone. This cannot be undone."))
            .setPositiveButton(L.t("Clear"),(d,w)->{store.clear();render();})
            .setNegativeButton(L.t("Cancel"),null).show();
    }
    private void shareAll(){share(store.exportText());}
    private void share(String text){
        if(text.isEmpty())return;
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),L.t("Share moments")));
    }
    private int dp(float v){return CatchWaveUi.dp(this,v);}
}
