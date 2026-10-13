// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text.Json;
namespace CatchWave.Host;

internal static class ControlIdentity {
    internal const int CoreLength=41;
    internal static byte[]? Authenticate(byte[] auth,byte[] token){
        if(auth.Length<32||!CryptographicOperations.FixedTimeEquals(auth.AsSpan(0,32),token))throw new IOException("Pairing rejected");
        if(auth.Length==32)return null; // Older clients may receive audio, never command authority.
        if(auth.Length<35||auth[32]!=1)throw new IOException("Invalid control identity");
        int length=BinaryPrimitives.ReadUInt16BigEndian(auth.AsSpan(33));
        if(length>128||length<64||auth.Length!=35+length)throw new IOException("Invalid control identity");
        byte[] encoded=auth[35..];
        try{using var key=ECDsa.Create();key.ImportSubjectPublicKeyInfo(encoded,out int consumed);
            if(consumed!=encoded.Length||key.ExportParameters(false).Curve.Oid.Value!="1.2.840.10045.3.1.7")throw new IOException("Invalid control identity");
        }catch(CryptographicException){throw new IOException("Invalid control identity");}
        return encoded;
    }
    internal static bool Verify(byte[]? publicKey,byte[] command){
        if(publicKey==null||command.Length<CoreLength+9||command.Length>CoreLength+73)return false;
        int length=command[CoreLength];if(length<8||length>72||command.Length!=CoreLength+1+length)return false;
        try{using var key=ECDsa.Create();key.ImportSubjectPublicKeyInfo(publicKey,out _);
            return key.VerifyData(command.AsSpan(0,CoreLength),command.AsSpan(CoreLength+1),HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence);
        }catch(CryptographicException){return false;}
    }
}

/** Owner-approved public key hashes. No pairing tokens, audio, or private keys. */
internal sealed class HostTrustedDevices {
    private readonly object gate=new();private readonly string? path;
    private HashSet<string> approved=[];private byte[]? current;private long revision;
    internal HostTrustedDevices(string? path=null){this.path=path;if(path==null)return;
        try{if(new FileInfo(path).Length>4096)return;var items=JsonSerializer.Deserialize<string[]>(File.ReadAllText(path));
            if(items!=null&&items.Length<=16&&items.All(x=>x!=null&&x.Length==64&&x.All(Uri.IsHexDigit)))approved=new(items,StringComparer.Ordinal);
        }catch(Exception e)when(e is IOException or UnauthorizedAccessException or JsonException){}
    }
    internal static HostTrustedDevices Local()=>new(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"CatchWave","approved-control-keys.json"));
    private static string Hash(byte[] key)=>Convert.ToHexString(SHA256.HashData(key));
    internal string? PendingFingerprint{get{lock(gate)return current!=null&&!approved.Contains(Hash(current))?Hash(current):null;}}
    internal void Observe(byte[]? key){lock(gate)current=key?.ToArray();}
    internal HostPermission CurrentPermission(HostPermission pc){lock(gate)return PermissionFor(current,pc);}
    internal HostPermission PermissionFor(byte[]? key,HostPermission pc){lock(gate)return new(pc.Enabled&&key!=null&&approved.Contains(Hash(key)),checked(pc.Generation*4294967296L+revision));}
    internal bool Approve(string observed){lock(gate){
        if(current==null||Hash(current)!=observed||approved.Count>=16)return false;
        var next=new HashSet<string>(approved,StringComparer.Ordinal){observed};
        if(path!=null)try{Directory.CreateDirectory(Path.GetDirectoryName(path)!);File.WriteAllText(path+".tmp",JsonSerializer.Serialize(next));File.Move(path+".tmp",path,true);}
        catch(Exception e)when(e is IOException or UnauthorizedAccessException){return false;}
        approved=next;revision++;return true;
    }}
}
