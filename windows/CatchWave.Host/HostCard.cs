// SPDX-License-Identifier: GPL-3.0-or-later
using System.Drawing.Drawing2D;
namespace CatchWave.Host;
/** A native layout surface; the original illustration remains an image resource. */
internal sealed class HostCard:Panel {
    internal static readonly Color Fill=Color.FromArgb(22,30,22);
    internal HostCard(){BackColor=Fill;Padding=new Padding(16);SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer,true);}
    protected override void OnPaintBackground(PaintEventArgs e){e.Graphics.Clear(Parent?.BackColor??HostWindow.Surface);e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
        var r=new RectangleF(1,1,Width-3,Height-3);float d=20;
        using var path=new GraphicsPath();path.AddArc(r.X,r.Y,d,d,180,90);path.AddArc(r.Right-d,r.Y,d,d,270,90);path.AddArc(r.Right-d,r.Bottom-d,d,d,0,90);path.AddArc(r.X,r.Bottom-d,d,d,90,90);path.CloseFigure();
        using var brush=new SolidBrush(Fill);e.Graphics.FillPath(brush,path);using var pen=new Pen(HostWindow.Line);e.Graphics.DrawPath(pen,path);
    }
}
