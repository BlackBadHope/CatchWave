// SPDX-License-Identifier: GPL-3.0-or-later
using System.Security.Cryptography;
using System.Reflection;
using QRCoder;
namespace CatchWave.Host;

internal static class PairingQr {
    internal static byte[] Png(string payload,int scale=6){
        using var data=QRCodeGenerator.GenerateQrCode(payload,QRCodeGenerator.ECCLevel.Q);
        using var qr=new PngByteQRCode(data);return qr.GetGraphic(scale);
    }
    // Inert public fixture, never a HostIdentity or listener credential.
    internal const string Fixture="CW1/192.0.2.1/49372/__79_Pv6-fj39vX08_Lx8O_u7ezr6uno5-bl5OPi4eA/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
}

internal sealed class HostWindow:Form {
    internal static readonly Color Surface=Color.FromArgb(16,21,16),TextColor=Color.FromArgb(238,245,230),
        Muted=Color.FromArgb(164,177,159),Accent=Color.FromArgb(185,246,107),Line=Color.FromArgb(55,67,51);
    private HostPermission permission=new(false,0);
    internal HostPermission Permission=>Volatile.Read(ref permission);
    internal bool MediaEnabled=>Permission.Enabled;
    private readonly object policyGate=new();
    private HostStatus model;
    private string transport="Wi-Fi";
    private string usbNotice="";
    private readonly Label connection=Label(28,true),reason=Label(11),statistics=Label(10),phone=Label(11),controlStatus=Label(17,true),controlHint=Label(11),saving=Label(10),badge=Label(11,true);
    private readonly Button control=Button(""),revoke=Button("Отозвать разрешение ПК");
    private readonly bool locked;private readonly Func<bool,bool> save;
    private readonly PictureBox picture=new(),guide=new();
    private readonly Panel pairing=new(),diagnostics=new(),commands=new(),content=new();
    private readonly Button network=Button(""),usb=Button("Подключить по USB");
    // Windows still uses a dropdown after Closed fires. Keep it alive until the form is disposed.
    private readonly ContextMenuStrip networkMenu=new();
    private readonly Label privacy=Label(9);
    private readonly Button pairAction=Button(""),diagnosticAction=Button("Диагностика");
    private readonly Label details=Label(10);
    private string endpoint;private readonly string fingerprint;
    private bool pairOpen=true,diagnosticOpen;
    private bool? wasLive;
    private readonly System.Windows.Forms.Timer timer=new(){Interval=500};
    private readonly CancellationTokenRegistration cancellation;
    private readonly List<IDisposable> assets=[];

    internal HostWindow(HostIdentity identity,string address,int port,bool synthetic,bool enabled,CancellationTokenSource stop,Func<bool,bool> save,bool lockedOff,HostStatus status)
        :this(identity.PairCode(address,port),Convert.ToHexString(SHA256.HashData(identity.Certificate.RawData)),address,port,synthetic,enabled,stop,save,lockedOff,status){}

    // Native rendering/test entry uses only an inert public fixture, never a live pairing identity.
    internal HostWindow(string pairCode,string certificate,string address,int port,bool synthetic,bool enabled,CancellationTokenSource stop,Func<bool,bool> save,bool lockedOff,HostStatus status){
        model=status;locked=lockedOff;this.save=save;permission=new(enabled&&!lockedOff,0);
        endpoint=$"{address}:{port}";fingerprint=certificate;
        Text="CatchWave Host";ClientSize=new Size(1000,730);MinimumSize=SizeFromClientSize(new Size(980,730));
        StartPosition=FormStartPosition.CenterScreen;BackColor=Surface;ForeColor=TextColor;
        Font=new Font("Segoe UI",11);AutoScaleMode=AutoScaleMode.Dpi;DoubleBuffered=true;
        using(var brandIcon=Assembly.GetExecutingAssembly().GetManifestResourceStream("CatchWave.Host.brand.ico")!){var icon=new Icon(brandIcon);Icon=icon;assets.Add(icon);}

        var page=new TableLayoutPanel{Padding=new Padding(28,20,28,20),ColumnCount=1,RowCount=3,BackColor=Surface};
        page.RowStyles.Add(new RowStyle(SizeType.Absolute,54));page.RowStyles.Add(new RowStyle(SizeType.Percent,100));page.RowStyles.Add(new RowStyle(SizeType.Absolute,50));
        Controls.Add(page);
        void FitPage(){float scale=DeviceDpi/96f;page.Size=new Size(Math.Min(ClientSize.Width,(int)(1040*scale)),Math.Min(ClientSize.Height,(int)(740*scale)));page.Location=new Point((ClientSize.Width-page.Width)/2,(ClientSize.Height-page.Height)/2);}
        ClientSizeChanged+=(_,_)=>FitPage();Shown+=(_,_)=>FitPage();FitPage();
        var top=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,Margin=Padding.Empty};
        top.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));top.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,240));
        var brand=Label(19,true);brand.Text="CatchWave  Host";brand.ForeColor=Accent;
        badge.TextAlign=ContentAlignment.MiddleRight;badge.Dock=DockStyle.Fill;
        top.Controls.Add(brand,0,0);top.Controls.Add(badge,1,0);page.Controls.Add(top,0,0);

        var columns=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,Margin=Padding.Empty,BackColor=Surface};
        columns.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));columns.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,350));
        page.Controls.Add(columns,0,1);
        var left=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=7,Margin=Padding.Empty,BackColor=HostCard.Fill};
        left.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
        left.RowStyles.Add(new RowStyle(SizeType.Absolute,48));
        left.RowStyles.Add(new RowStyle(SizeType.Absolute,110));
        left.RowStyles.Add(new RowStyle(SizeType.Absolute,44));
        left.RowStyles.Add(new RowStyle(SizeType.Absolute,158));
        left.RowStyles.Add(new RowStyle(SizeType.Absolute,48));
        left.RowStyles.Add(new RowStyle(SizeType.Absolute,66));
        left.RowStyles.Add(new RowStyle(SizeType.Percent,100));
        connection.AutoSize=false;connection.Dock=DockStyle.Fill;left.Controls.Add(connection,0,0);
        var source=Label(11);source.Text=synthetic?"Тестовый источник: тишина":"Подключение к телефону";
        var sourcePanel=new Panel{Dock=DockStyle.Fill};source.AutoSize=false;source.Height=24;source.Dock=DockStyle.Top;
        network.Dock=DockStyle.Top;network.Text=new HostEndpoint(address,"Current").ToString();
        network.AccessibleName="Подключение через USB-модем или локальную сеть";
        usb.Dock=DockStyle.Bottom;sourcePanel.Controls.Add(network);sourcePanel.Controls.Add(source);sourcePanel.Controls.Add(usb);left.Controls.Add(sourcePanel,0,1);
        controlStatus.AutoSize=false;controlStatus.Dock=DockStyle.Fill;left.Controls.Add(controlStatus,0,2);
        guide.Dock=DockStyle.Fill;guide.SizeMode=PictureBoxSizeMode.Zoom;guide.BackColor=Color.Transparent;
        using(var asset=Assembly.GetExecutingAssembly().GetManifestResourceStream("CatchWave.Host.tilt.png")!){using var img=Image.FromStream(asset);guide.Image=new Bitmap(img);assets.Add(guide.Image);}
        left.Controls.Add(guide,0,3);
        control.Dock=DockStyle.Fill;left.Controls.Add(control,0,4);
        controlHint.AutoSize=false;controlHint.Dock=DockStyle.Fill;left.Controls.Add(controlHint,0,5);
        var warning=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true};
        warning.SizeChanged+=(_,_)=>{saving.MaximumSize=reason.MaximumSize=new Size(Math.Max(1,warning.ClientSize.Width-24),0);};
        warning.Controls.Add(saving);warning.Controls.Add(reason);left.Controls.Add(warning,0,6);
        var leftCard=new HostCard{Dock=DockStyle.Fill,Margin=new Padding(0,8,20,0)};leftCard.Controls.Add(left);columns.Controls.Add(leftCard,0,0);

        var right=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=4,Margin=Padding.Empty,BackColor=HostCard.Fill};
        right.RowStyles.Add(new RowStyle(SizeType.Absolute,38));right.RowStyles.Add(new RowStyle(SizeType.Percent,100));
        right.RowStyles.Add(new RowStyle(SizeType.Absolute,42));right.RowStyles.Add(new RowStyle(SizeType.Absolute,44));
        pairAction.Dock=DockStyle.Fill;pairAction.Click+=(_,_)=>{pairOpen=!pairOpen;RenderPanels();};right.Controls.Add(pairAction,0,0);
        pairing.Dock=DockStyle.Fill;
        var pairLayout=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=3,Padding=new Padding(4,12,4,4)};
        pairLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,32));pairLayout.RowStyles.Add(new RowStyle(SizeType.Percent,100));pairLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,62));
        var scan=Label(11);scan.Text="Сканируйте QR в CatchWave";scan.TextAlign=ContentAlignment.MiddleCenter;scan.Dock=DockStyle.Fill;
        using(var bytes=new MemoryStream(PairingQr.Png(pairCode,4)))using(var img=Image.FromStream(bytes)){picture.Image=new Bitmap(img);assets.Add(picture.Image);}
        picture.Dock=DockStyle.Fill;picture.SizeMode=PictureBoxSizeMode.CenterImage;picture.BackColor=Color.White;
        privacy.Text="QR приватен. После перезапуска Host\nотсканируйте новый QR на телефоне.";privacy.Dock=DockStyle.Fill;privacy.Padding=new Padding(0,8,0,0);
        pairLayout.Controls.Add(scan,0,0);pairLayout.Controls.Add(picture,0,1);pairLayout.Controls.Add(privacy,0,2);pairing.Controls.Add(pairLayout);
        content.Dock=DockStyle.Fill;content.Controls.Add(pairing);right.Controls.Add(content,0,1);
        phone.Dock=DockStyle.Fill;phone.MaximumSize=new Size(300,0);right.Controls.Add(phone,0,2);
        diagnosticAction.Dock=DockStyle.Fill;diagnosticAction.Click+=(_,_)=>{diagnosticOpen=!diagnosticOpen;RenderPanels();};right.Controls.Add(diagnosticAction,0,3);
        var rightCard=new HostCard{Dock=DockStyle.Fill,Margin=new Padding(0,8,0,0)};rightCard.Controls.Add(right);columns.Controls.Add(rightCard,1,0);

        // Diagnostics replace the QR area, avoiding an ever-growing main window.
        diagnostics.Dock=DockStyle.Fill;
        var diagnosticLayout=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(4,12,4,0)};
        statistics.MaximumSize=details.MaximumSize=new Size(288,0);
        details.Text=$"Адрес: {endpoint}\nСертификат SHA-256:\n{fingerprint}";details.AutoSize=true;
        var logs=Button("Открыть журнал");logs.Width=284;logs.Click+=(_,_)=>ShowLog();
        var export=Button("Экспорт диагностики");export.Width=284;export.Click+=(_,_)=>ExportLog();
        revoke.Width=284;revoke.Click+=(_,_)=>SetMedia(false);
        foreach(Control item in new Control[]{statistics,details,logs,export,revoke})diagnosticLayout.Controls.Add(item);
        diagnostics.Controls.Add(diagnosticLayout);content.Controls.Add(diagnostics);
        commands.Dock=DockStyle.Fill;
        var commandLayout=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=3,Padding=new Padding(10,40,10,30)};
        commandLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,50));commandLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,50));
        commandLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,60));commandLayout.RowStyles.Add(new RowStyle(SizeType.Percent,50));commandLayout.RowStyles.Add(new RowStyle(SizeType.Percent,50));
        var commandTitle=Label(14,true);commandTitle.Text="Постукивания по телефону";commandTitle.Dock=DockStyle.Fill;commandLayout.Controls.Add(commandTitle,0,0);commandLayout.SetColumnSpan(commandTitle,2);
        string[] captions={"Пауза / старт","Следующий","Предыдущий","Звук телефона"};
        for(int i=0;i<4;i++){var cell=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,Padding=new Padding(4,6,4,6)};
            var count=Label(32,true);count.Text=(i+3).ToString();count.ForeColor=Accent;var caption=Label(10);caption.Text=captions[i];cell.Controls.Add(count);cell.Controls.Add(caption);commandLayout.Controls.Add(cell,i%2,1+i/2);}
        commands.Controls.Add(commandLayout);content.Controls.Add(commands);

        var footer=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,Margin=new Padding(0,8,0,0)};
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));footer.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,270));
        var note=Label(9);note.Text=synthetic?"Тестовый источник: тишина":"Звук: основной аудиовыход Windows";note.Dock=DockStyle.Fill;note.TextAlign=ContentAlignment.MiddleLeft;
        var close=Button("Отключить и закрыть Host");close.Dock=DockStyle.Fill;close.Click+=(_,_)=>Close();
        footer.Controls.Add(note,0,0);footer.Controls.Add(close,1,0);page.Controls.Add(footer,0,2);

        control.Click+=(_,_)=>{
            if(MediaEnabled)SetMedia(false);
            else if(!locked&&MessageBox.Show(this,"Разрешить телефону управлять проигрывателем этого ПК? На телефоне управление также нужно разрешить и активировать.","Управление с телефона",MessageBoxButtons.OKCancel,MessageBoxIcon.Question)==DialogResult.OK)SetMedia(true);
        };
        model.Changed+=RefreshState;timer.Tick+=(_,_)=>Render();timer.Start();
        FormClosing+=(_,_)=>stop.Cancel();
        Shown+=(_,_)=>{Render();if(stop.IsCancellationRequested)Close();};
        cancellation=stop.Token.Register(()=>{if(IsHandleCreated&&!IsDisposed)try{BeginInvoke(new Action(Close));}catch(InvalidOperationException){}});
        Render();
    }

    private static Label Label(float size,bool bold=false)=>new(){AutoSize=true,ForeColor=bold?TextColor:Muted,BackColor=Color.Transparent,Font=new Font("Segoe UI",size,bold?FontStyle.Bold:FontStyle.Regular),Margin=new Padding(0,3,0,6)};
    private static Button Button(string text)=>new HostButton(){Text=text,Height=38,ForeColor=TextColor,BackColor=Surface,Font=new Font("Segoe UI",11),Cursor=Cursors.Hand,Margin=new Padding(0,3,0,3)};
    private void RenderPanels(){
        pairing.Visible=pairOpen&&!diagnosticOpen;diagnostics.Visible=diagnosticOpen;
        commands.Visible=!pairOpen&&!diagnosticOpen;
        (diagnosticOpen?diagnostics:pairOpen?pairing:commands).BringToFront();
        pairAction.Text=pairOpen?"Скрыть QR":"Подключить телефон";
        diagnosticAction.Text=diagnosticOpen?"Закрыть диагностику":"Диагностика";
    }
    private void ShowLog(){
        using var dialog=new Form{Text="CatchWave — последние 256 событий",Width=940,Height=650,BackColor=Surface,StartPosition=FormStartPosition.CenterParent};
        dialog.Controls.Add(new TextBox{Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Both,Dock=DockStyle.Fill,BackColor=Surface,ForeColor=TextColor,Font=new Font("Consolas",10),Text=model.Log.Text().Replace("\n",Environment.NewLine)});
        dialog.ShowDialog(this);
    }
    private void ExportLog(){using var pick=new SaveFileDialog{Filter="Text|*.txt",FileName="CatchWave-diagnostics.txt"};
        if(pick.ShowDialog(this)==DialogResult.OK)try{File.WriteAllText(pick.FileName,model.Log.Text());}catch(IOException){MessageBox.Show(this,"Не удалось сохранить диагностику");}catch(UnauthorizedAccessException){MessageBox.Show(this,"Система запретила запись диагностики");}}
    private void SetMedia(bool value){if(locked)return;model.Log.Event("CONTROL",value?"PERMISSION_ON":"PERMISSION_OFF");
        lock(policyGate){HostPermission old=Permission;if(old.Enabled!=value)Volatile.Write(ref permission,new(value,old.Generation+1));}
        saving.Text=save(value)?"":value?"Включение не сохранено; оно действует только в этом сеансе.":"Выключение не сохранено. После перезапуска проверьте разрешение ПК.";Render();}
    internal bool Execute(byte action,HostPermission observed){lock(policyGate){return Permission==observed&&observed.Enabled&&MediaKeys.Send(action);}}
    private void RefreshState(){if(IsDisposed)return;if(IsHandleCreated)try{BeginInvoke(new Action(Render));}catch(InvalidOperationException){}}
    internal void Render(){
        if(IsDisposed)return;HostSnapshot s=model.Snapshot;
        connection.Text=s.Stage switch {
            HostStage.Listening=>"Подключите телефон",HostStage.Tcp or HostStage.Tls=>"Подключение…",HostStage.Auth=>"Проверка сопряжения…",
            HostStage.Authenticated or HostStage.Format=>"Запуск звука…",HostStage.SilenceSent=>"Тишина с ПК",HostStage.AudioSent=>"Звук отправляется",
            HostStage.Disconnected=>"Телефон отключён",_=>"Связь прервалась"
        };
        bool fresh=s.ClientAgeMs>=0&&s.ClientAgeMs<1500&&s.Live;
        badge.Text=s.Live?"Подключено · "+transport:s.Stage==HostStage.Error?"Ошибка соединения":"Ожидание телефона";
        badge.ForeColor=s.Live?Accent:Muted;
        reason.Text=s.Error==HostError.None?"":s.Error switch {
            HostError.AuthRejected=>"Сканируйте QR текущего Host.",HostError.TlsHandshake=>"Не удалось установить защищённое соединение.",
            HostError.CaptureFailed=>"Аудиовыход Windows недоступен. Откройте диагностику.",
            HostError.PeerClosed=>"Можно подключить телефон снова.",HostError.Protocol=>"Проверьте совместимость версий Host и Android.",_=>"Проверьте состояние на телефоне и откройте диагностику."
        };
        if(s.Error==HostError.None&&!string.IsNullOrEmpty(usbNotice))reason.Text=usbNotice;
        statistics.Text=$"Сеанс: {s.Seconds} с\nОтправлено кадров: {s.Frames}\nПропущено интервалов: {s.Skipped}\nКод: {s.Error} · этап: {s.FailedAt}\nПоследняя причина: {model.Log.LastReason}\nОшибок журнала: {model.Log.StorageFailures}";
        phone.Text=!fresh?"Вывод телефона не подтверждён":s.ClientOutput switch {0=>"Ожидаем вывод телефона",1=>"Телефон принимает тишину",_=>"Телефон принимает звук"};
        bool effective=s.Effective(MediaEnabled,Permission.Generation);
        controlStatus.Text=effective?"Жесты готовы":!s.Live?"Ожидаем телефон":!MediaEnabled?"Нет разрешения ПК":"Активируйте на телефоне";
        controlStatus.ForeColor=effective?Accent:TextColor;
        string cause=locked?"Команды заблокированы флагом запуска":!MediaEnabled?"Разрешение ПК выключено":!fresh||s.ClientOutput==0?"Разрешение ПК включено. Подключите телефон.":!s.ClientWants?"Разрешение ПК включено; включите управление на телефоне":!effective?"Телефон заблокировал команды: активируйте управление кнопкой и держите спокойно 1,5 секунды":"Жесты готовы на телефоне. Наклон меняет громкость телефона.";
        controlHint.Text=cause;
        control.Text=MediaEnabled?"Разрешение ПК включено · выключить":"Разрешить управление на ПК";
        control.Enabled=!locked&&(MediaEnabled||fresh&&s.ClientOutput>0);revoke.Enabled=!locked&&MediaEnabled;
        control.BackColor=MediaEnabled?Accent:Surface;control.ForeColor=MediaEnabled?Surface:TextColor;
        if(wasLive!=s.Live){pairOpen=!s.Live;wasLive=s.Live;}
        RenderPanels();
    }
    internal void SetDiagnosticsForPreview(bool value){diagnosticOpen=value;RenderPanels();}
    internal void UseStatus(HostStatus next,string route){
        if(IsDisposed||!IsHandleCreated)return;
        if(InvokeRequired){BeginInvoke(()=>UseStatus(next,route));return;}
        model.Changed-=RefreshState;model=next;model.Changed+=RefreshState;transport=route;if(route=="USB")usbNotice="";Render();
    }
    internal void UsbMessage(string code){
        if(IsDisposed||!IsHandleCreated)return;if(InvokeRequired){BeginInvoke(()=>UsbMessage(code));return;}
        usbNotice=code switch{"USB_DRIVER_MISSING"=>"Для подключения по кабелю нужен USB-компонент CatchWave.","USB_DRIVER_RESTART_REQUIRED"=>"Перезапустите ПК для завершения установки USB-компонента. Wi-Fi доступен.","USB_DRIVER_ACCESS_DENIED"=>"Windows не разрешает доступ к USB-компоненту.","USB_DRIVER_BUSY"=>"USB-компонент занят. Повторите подключение.","USB_AOA_UNSUPPORTED"=>"Телефон не поддерживает режим USB-аксессуара.","USB_NATIVE_INVALID_PARAMETER"=>"USB-компонент Windows отклонил запрос. Подключитесь по Wi-Fi; код ошибки есть в диагностике.","USB_DEVICE_NOT_READY"=>"Windows не смогла открыть телефон по USB. Переподключите кабель; Wi-Fi остаётся доступен.",_=>"USB-соединение завершено. Проверьте кабель и разрешение CatchWave на телефоне."};Render();
        model.Log.Event("USB",code.StartsWith("USB_",StringComparison.Ordinal)?code:"SESSION_ENDED");
    }
    internal void ConfigureUsb(Func<UsbAccessory.Phone,Task> connect,Func<bool> running){
        usb.Click+=async(_,_)=>{
            if(running()){MessageBox.Show(this,"Телефон уже подключается или подключён по USB.","CatchWave");return;}
            if(!UsbAccessory.Available){
                if(MessageBox.Show(this,"Для обычного кабеля нужен подписанный USB-компонент на ПК. Установка может кратко перезапустить USB-устройства и потребовать перезапуска ПК. Отладка Android и USB-модем не нужны. Установить компонент?","CatchWave",MessageBoxButtons.YesNo,MessageBoxIcon.Information,MessageBoxDefaultButton.Button2)!=DialogResult.Yes)return;
                usb.Enabled=false;try{await UsbComponent.Install();UsbMessage("USB_DRIVER_RESTART_REQUIRED");}
                catch(Exception e)when(e is IOException or System.ComponentModel.Win32Exception or System.Net.Http.HttpRequestException or TaskCanceledException){UsbMessage("USB_DRIVER_MISSING");}
                finally{if(!IsDisposed)usb.Enabled=true;}return;
            }
            usb.Enabled=false;
            try{
                var choices=await Task.Run(UsbAccessory.Phones);
                if(IsDisposed)return;
                if(choices.Count==0){MessageBox.Show(this,"Подключите телефон кабелем с передачей данных.","CatchWave");return;}
                UsbAccessory.Phone selected;
                if(choices.Count==1)selected=choices[0];else{
                    using var pick=new Form{Text="Выберите телефон CatchWave",ClientSize=new Size(420,150),StartPosition=FormStartPosition.CenterParent};
                    var list=new ComboBox{Dock=DockStyle.Top,DropDownStyle=ComboBoxStyle.DropDownList};list.Items.AddRange(choices.Cast<object>().ToArray());list.SelectedIndex=0;
                    var go=new Button{Text="Подключить",Dock=DockStyle.Bottom,DialogResult=DialogResult.OK};pick.Controls.Add(list);pick.Controls.Add(go);pick.AcceptButton=go;
                    if(pick.ShowDialog(this)!=DialogResult.OK)return;selected=(UsbAccessory.Phone)list.SelectedItem!;
                }
                usbNotice="Разрешите USB-доступ CatchWave на телефоне.";Render();await connect(selected);
            }catch(Exception e)when(e is IOException or System.Runtime.InteropServices.SEHException or DllNotFoundException){UsbMessage(e is IOException?e.Message:"USB_OPEN_FAILED");}
            finally{if(!IsDisposed)usb.Enabled=true;}
        };
    }
    internal void ConfigureNetworks(HostEndpoint current,Func<HostEndpoint,Task> select,Func<string,string> pairCode,Func<IReadOnlyList<HostEndpoint>> available,int port){
        network.Text=current.ToString();
        networkMenu.BackColor=Surface;networkMenu.ForeColor=TextColor;networkMenu.Font=Font;
        network.Click+=(_,_)=>{
            if(!network.Enabled)return;
            network.Enabled=false;IReadOnlyList<HostEndpoint> choices;
            try{choices=available();}
            catch(System.Net.NetworkInformation.NetworkInformationException){if(!IsDisposed)MessageBox.Show(this,"Не удалось прочитать подключения. Повторите попытку.","CatchWave");return;}
            finally{if(!IsDisposed)network.Enabled=true;}
            if(IsDisposed)return;
            var menu=networkMenu;
            foreach(ToolStripItem item in menu.Items.Cast<ToolStripItem>().ToArray()){menu.Items.Remove(item);item.Dispose();}
            foreach(var next in choices){
                var item=new ToolStripMenuItem(next.ToString()){Checked=next.Address==current.Address};
                item.Click+=async(_,_)=>{
                    if(next.Address==current.Address)return;
                    if(model.Snapshot.Live&&MessageBox.Show(this,"Звук остановится. После смены подключения отсканируйте QR снова.","Сменить подключение?",MessageBoxButtons.OKCancel)!=DialogResult.OK)return;
                    network.Enabled=false;
                    try{await select(next);current=next;network.Text=current.ToString();endpoint=$"{next.Address}:{port}";details.Text=$"Адрес: {endpoint}\nСертификат SHA-256:\n{fingerprint}";
                        using var bytes=new MemoryStream(PairingQr.Png(pairCode(next.Address),4));using var image=Image.FromStream(bytes);var replacement=new Bitmap(image);
                        var previous=picture.Image;picture.Image=replacement;if(previous!=null){assets.Remove(previous);previous.Dispose();}assets.Add(replacement);
                        pairOpen=true;diagnosticOpen=false;Render();
                    }catch(Exception e)when(e is System.Net.Sockets.SocketException or ArgumentException){MessageBox.Show(this,"Адрес недоступен. Проверьте USB-модем или локальную сеть.","CatchWave");}
                    catch(OperationCanceledException){}finally{if(!IsDisposed)network.Enabled=true;}
                };menu.Items.Add(item);
            }
            if(menu.Items.Count==0)menu.Items.Add(new ToolStripMenuItem("Подключите USB-модем или локальную сеть"){Enabled=false});
            menu.Show(network,new Point(0,network.Height));
        };
    }
    internal void AssertLayout(){
        if(Controls[0].Width>1040*DeviceDpi/96f||Controls[0].Height>740*DeviceDpi/96f)throw new Exception("Content stretched beyond readable size");
        if(pairing.Parent!=diagnostics.Parent||pairing.Parent!=commands.Parent||pairing.Parent is TableLayoutPanel)throw new Exception("Content panels must share one overlay container");
        if(pairing.Visible&&(privacy.Height<privacy.GetPreferredSize(new Size(privacy.Width,0)).Height||picture.Height<picture.Image!.Height||picture.Width<picture.Image.Width))throw new Exception("QR or privacy text clipped");
        if(phone.Bottom>phone.Parent!.ClientSize.Height||diagnosticAction.Bottom>diagnosticAction.Parent!.ClientSize.Height)throw new Exception("Right column overflow");
        foreach(Control item in new Control[]{connection,controlStatus,controlHint,control,network})if(item.Right>item.Parent!.ClientSize.Width||item.Bottom>item.Parent.ClientSize.Height)throw new Exception("Main control clipped: "+item.GetType().Name);
    }
    internal Bitmap RenderClient(){using var contentBitmap=new Bitmap(Controls[0].Width,Controls[0].Height);Controls[0].DrawToBitmap(contentBitmap,new Rectangle(Point.Empty,Controls[0].Size));var bitmap=new Bitmap(ClientSize.Width,ClientSize.Height);using var graphics=Graphics.FromImage(bitmap);graphics.Clear(Surface);graphics.DrawImageUnscaled(contentBitmap,Controls[0].Location);return bitmap;}
    protected override void Dispose(bool disposing){if(disposing){timer.Dispose();networkMenu.Dispose();cancellation.Dispose();model.Changed-=RefreshState;picture.Image=null;guide.Image=null;foreach(var asset in assets)asset.Dispose();}base.Dispose(disposing);}
}
