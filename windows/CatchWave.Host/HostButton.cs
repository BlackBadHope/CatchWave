// SPDX-License-Identifier: GPL-3.0-or-later
using System.Drawing.Drawing2D;
namespace CatchWave.Host;
/** Native button semantics with the same rounded control surface as Android. */
internal sealed class HostButton:Button {
    private bool hover;
    internal HostButton(){SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer,true);}
    protected override void OnMouseEnter(EventArgs e){hover=true;Invalidate();base.OnMouseEnter(e);}
    protected override void OnMouseLeave(EventArgs e){hover=false;Invalidate();base.OnMouseLeave(e);}
    protected override void OnPaint(PaintEventArgs e){
        e.Graphics.Clear(Parent?.BackColor??HostWindow.Surface);
        e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
        var r=new RectangleF(1,1,Width-3,Height-3);float d=Math.Min(24,r.Height);
        using var path=new GraphicsPath();path.AddArc(r.X,r.Y,d,d,180,90);path.AddArc(r.Right-d,r.Y,d,d,270,90);path.AddArc(r.Right-d,r.Bottom-d,d,d,0,90);path.AddArc(r.X,r.Bottom-d,d,d,90,90);path.CloseFigure();
        Color fill=Enabled&&hover&&BackColor==HostWindow.Surface?Color.FromArgb(38,49,31):BackColor;
        using var brush=new SolidBrush(fill);e.Graphics.FillPath(brush,path);using var pen=new Pen(HostWindow.Line);e.Graphics.DrawPath(pen,path);
        TextRenderer.DrawText(e.Graphics,Text,Font,ClientRectangle,Enabled?ForeColor:HostWindow.Muted,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);
        if(Focused&&ShowFocusCues)ControlPaint.DrawFocusRectangle(e.Graphics,Rectangle.Inflate(ClientRectangle,-6,-6),ForeColor,BackColor);
    }
}
