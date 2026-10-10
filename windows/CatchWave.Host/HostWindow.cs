// SPDX-License-Identifier: GPL-3.0-or-later
using System.Security.Cryptography;
using QRCoder;
namespace CatchWave.Host;

internal static class PairingQr {
    internal static byte[] Png(string payload){
        using var data=QRCodeGenerator.GenerateQrCode(payload,QRCodeGenerator.ECCLevel.Q);
        using var qr=new PngByteQRCode(data);return qr.GetGraphic(6);
    }
    // Inert deterministic public fixture; never accepted by a HostIdentity. No listener/key/random token.
    internal const string Fixture="CW1/192.0.2.1/49372/__79_Pv6-fj39vX08_Lx8O_u7ezr6uno5-bl5OPi4eA/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
}
internal sealed class HostWindow:Form {
    private HostPermission permission=new(false,0);internal HostPermission Permission=>Volatile.Read(ref permission);internal bool MediaEnabled=>Permission.Enabled;
    private readonly object policyGate=new();
    private readonly HostStatus model;
    private readonly Label connection=new(){AutoSize=true},reason=new(){AutoSize=true},statistics=new(){AutoSize=true},phone=new(){AutoSize=true},controlStatus=new(){AutoSize=true};
    private readonly Button control=new(){Width=440,Height=36},revoke=new(){Text="Отозвать разрешение ПК",Width=440,Height=36};
    private readonly Label saving=new(){AutoSize=true,MaximumSize=new Size(455,0)};
    private readonly bool locked;private readonly Func<bool,bool> save;
    internal HostWindow(HostIdentity identity,string address,int port,bool synthetic,bool enabled,CancellationTokenSource stop,Func<bool,bool> save,bool lockedOff,HostStatus status){
        model=status;locked=lockedOff;this.save=save;permission=new(enabled&&!lockedOff,0);
        Text="CatchWave Host — соединение с телефоном";ClientSize=new Size(530,900);MinimumSize=new Size(530,780);StartPosition=FormStartPosition.CenterScreen;
        var header=new FlowLayoutPanel{Dock=DockStyle.Top,AutoSize=true,FlowDirection=FlowDirection.TopDown,WrapContents=false,Padding=new Padding(20,12,20,8)};
        foreach(var label in new[]{connection,reason,statistics,phone,controlStatus,saving}){label.MaximumSize=new Size(455,0);header.Controls.Add(label);}header.Controls.Add(control);
        var body=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(20)};
        var bottom=new Button{Text="Отключиться и закрыть Host",Dock=DockStyle.Bottom,Height=42};bottom.Click+=(_,_)=>Close();
        Controls.Add(body);Controls.Add(header);Controls.Add(bottom);
        body.Controls.Add(new Label{Text="Сканируйте этот QR в CatchWave на своём телефоне.",AutoSize=true});
        byte[] png=PairingQr.Png(identity.PairCode(address,port));using var bytes=new MemoryStream(png);using var image=Image.FromStream(bytes);
        var picture=new PictureBox{Width=400,Height=400,SizeMode=PictureBoxSizeMode.CenterImage,Image=new Bitmap(image),BackColor=Color.White};body.Controls.Add(picture);
        body.Controls.Add(new Label{Text=synthetic?"Источник: синтетическая тишина; захвата звука ПК нет":"Источник: основной аудиовыход Windows после аутентификации",AutoSize=true,MaximumSize=new Size(440,0)});
        var details=new Label{Text=$"{address}:{port}\nСертификат SHA-256:\n{Convert.ToHexString(SHA256.HashData(identity.Certificate.RawData))}",AutoSize=true,MaximumSize=new Size(440,0),Visible=false};
        revoke.Visible=false;revoke.Click+=(_,_)=>SetMedia(false);
        var advanced=new Button{Text="Дополнительно",Width=440,Height=36};advanced.Click+=(_,_)=>{details.Visible=!details.Visible;revoke.Visible=details.Visible;};body.Controls.Add(advanced);body.Controls.Add(details);body.Controls.Add(revoke);
        var logs=new Button{Text="Показать журнал диагностики",Width=440,Height=36};logs.Click+=(_,_)=>{using var dialog=new Form{Text="Последние 256 событий — без аудио и секретов",Width=900,Height=650};dialog.Controls.Add(new TextBox{Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Both,Dock=DockStyle.Fill,Text=model.Log.Text().Replace("\n",Environment.NewLine)});dialog.ShowDialog(this);};body.Controls.Add(logs);
        var export=new Button{Text="Экспорт диагностики",Width=440,Height=36};export.Click+=(_,_)=>{using var pick=new SaveFileDialog{Filter="Text|*.txt",FileName="CatchWave-diagnostics.txt"};if(pick.ShowDialog(this)==DialogResult.OK)try{File.WriteAllText(pick.FileName,model.Log.Text());}catch(IOException){MessageBox.Show(this,"Не удалось сохранить диагностику");}catch(UnauthorizedAccessException){MessageBox.Show(this,"Система запретила запись диагностики");}};body.Controls.Add(export);
        body.Controls.Add(new Label{Text="QR приватен. Не отправляйте его или скриншот. Закрытие Host отзывает сопряжение; после перезапуска нужен новый QR.",AutoSize=true,MaximumSize=new Size(440,0)});
        control.Click+=(_,_)=>{
            if(MediaEnabled)SetMedia(false);
            else if(!locked&&!MediaEnabled&&MessageBox.Show(this,"Разрешить командам play/pause, next и previous управлять проигрывателем этого ПК? Телефон также должен разрешить управление.","Включить управление хостом?",MessageBoxButtons.OKCancel,MessageBoxIcon.Question)==DialogResult.OK)SetMedia(true);
        };
        model.Changed+=RefreshState;var timer=new System.Windows.Forms.Timer{Interval=500};timer.Tick+=(_,_)=>Render();timer.Start();
        FormClosing+=(_,_)=>stop.Cancel();Disposed+=(_,_)=>{timer.Dispose();model.Changed-=RefreshState;picture.Image?.Dispose();picture.Image=null;};
        Shown+=(_,_)=>{Render();if(stop.IsCancellationRequested)Close();};
        stop.Token.Register(()=>{if(IsHandleCreated&&!IsDisposed)try{BeginInvoke(new Action(Close));}catch(InvalidOperationException){}});Render();
    }
    private void SetMedia(bool value){if(locked)return;model.Log.Event("CONTROL",value?"PERMISSION_ON":"PERMISSION_OFF");lock(policyGate){HostPermission old=Permission;if(old.Enabled!=value)Volatile.Write(ref permission,new(value,old.Generation+1));}
        saving.Text=save(value)?"":value?"Включение не сохранено; оно действует только в этом сеансе.":"Выключение не сохранено. После перезапуска проверьте разрешение ПК.";Render();}
    internal bool Execute(byte action,HostPermission observed){lock(policyGate){return Permission==observed&&observed.Enabled&&MediaKeys.Send(action);}}
    private void RefreshState(){if(IsDisposed)return;if(IsHandleCreated)try{BeginInvoke(new Action(Render));}catch(InvalidOperationException){}}
    internal void Render(){
        if(IsDisposed)return;HostSnapshot s=model.Snapshot;
        connection.Text=s.Stage switch {
            HostStage.Listening=>"Ожидание телефона — соединения нет",HostStage.Tcp=>"Телефон открыл TCP",HostStage.Tls=>"Проверка TLS…",HostStage.Auth=>"Ожидание подтверждения сопряжения…",
            HostStage.Authenticated=>"Сопряжение принято",HostStage.Format=>"Формат PCM отправлен",HostStage.SilenceSent=>"Соединение работает — отправляется тишина",HostStage.AudioSent=>"Соединение работает — отправляется звук",
            HostStage.Disconnected=>"Телефон отключён — ожидание нового подключения",_=>"Соединение остановлено — ошибка"
        };
        reason.Text=s.Error==HostError.None?"":$"Код: {s.Error} · этап: {s.FailedAt}. "+(s.Error switch {
            HostError.AuthRejected=>"Сканируйте QR текущего окна Host.",HostError.TlsHandshake=>"TLS не установлен. TCP-проверка без TLS также даёт этот код.",
            HostError.CaptureFailed=>"Не удалось открыть или читать аудиовыход Windows.",HostError.PeerClosed=>"Телефон закрыл соединение; проверьте его код ошибки.",
            HostError.Protocol=>"Несовместимые данные. Нужны Host и Android из одного комплекта.",_=>"Проверьте состояние на телефоне и повторите подключение."
        });
        statistics.Text=$"Сеанс: {s.Seconds} с · отправлено PCM: {s.Frames} · пропущено интервалов: {s.Skipped}\nПоследняя причина: {model.Log.LastReason} · ошибок записи журнала: {model.Log.StorageFailures}";
        bool fresh=s.ClientAgeMs>=0&&s.ClientAgeMs<1500&&s.Live;
        phone.Text=!fresh?"Вывод и датчики телефона: подтверждения нет":$"Телефон: {(s.ClientOutput==0?"вывод ещё не подтверждён":s.ClientOutput==1?"AudioTrack принял тишину":"AudioTrack принял звук")}; датчики: "+(s.Sensors switch {0=>"выключены",1=>"настройка нейтрали",2=>"нейтраль готова",3=>"недоступны",4=>"ожидание измерений",_=>"наклоны недоступны"});
        bool effective=s.Effective(MediaEnabled,Permission.Generation);
        string cause=locked?"Команды заблокированы флагом текущего пилота":!fresh||s.ClientOutput==0?"Нет свежего подтверждения вывода телефона":!MediaEnabled?"Разрешение ПК выключено":!s.ClientWants?"Разрешение ПК включено; включите управление на телефоне":!effective?"Телефон заблокировал команды: активация, нейтраль или разрешение ещё не готовы":"Телефон подтвердил окно команд; таймер виден на телефоне";
        controlStatus.Text=(effective?"Управление включено":"Управление выключено")+" — "+cause;
        control.Text=MediaEnabled?"Выключить управление хостом":"Включить управление хостом";control.Enabled=!locked&&(MediaEnabled||fresh&&s.ClientOutput>0);revoke.Enabled=!locked&&MediaEnabled;
    }
}
