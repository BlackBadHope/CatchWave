// SPDX-License-Identifier: GPL-3.0-or-later
using System.Runtime.InteropServices;
namespace CatchWave.Host;
internal static class MediaKeys {
    // INPUT's union must include the larger MOUSEINPUT; x64 sizeof(INPUT) = 40.
    [StructLayout(LayoutKind.Sequential)]private struct Input {public uint Type;public InputUnion Value;}
    [StructLayout(LayoutKind.Explicit)]private struct InputUnion {[FieldOffset(0)]public Keyboard Key;[FieldOffset(0)]public Mouse Mouse;}
    [StructLayout(LayoutKind.Sequential)]private struct Keyboard {public ushort Vk,Scan;public uint Flags,Time;public UIntPtr Extra;}
    [StructLayout(LayoutKind.Sequential)]private struct Mouse {public int X,Y;public uint Data,Flags,Time;public UIntPtr Extra;}
    [DllImport("user32.dll",SetLastError=true)]private static extern uint SendInput(uint count,Input[] inputs,int size);
    internal static bool Send(byte action) {
        ushort vk=action switch{1=>0xB3,2=>0xB0,3=>0xB1,_=>0};if(vk==0)return false;
        Input down=new(){Type=1,Value=new InputUnion{Key=new Keyboard{Vk=vk}}};
        Input up=new(){Type=1,Value=new InputUnion{Key=new Keyboard{Vk=vk,Flags=2}}};
        return SendInput(2,[down,up],Marshal.SizeOf<Input>())==2;
    }
    internal static int StructureSize=>Marshal.SizeOf<Input>();
}
