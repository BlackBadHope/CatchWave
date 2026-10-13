// SPDX-License-Identifier: GPL-3.0-or-later
using System.Buffers.Binary;
using System.Security.Cryptography;
namespace CatchWave.Host;

/** Ephemeral loopback fixture, never used by production connections. */
internal sealed class SignedPeer:IDisposable {
    private readonly ECDsa key=ECDsa.Create(ECCurve.NamedCurves.nistP256);
    internal byte[] PublicKey=>key.ExportSubjectPublicKeyInfo();
    internal byte[] Auth(byte[] token){byte[] publicKey=PublicKey;byte[] body=new byte[35+publicKey.Length];token.CopyTo(body,0);body[32]=1;BinaryPrimitives.WriteUInt16BigEndian(body.AsSpan(33),(ushort)publicKey.Length);publicKey.CopyTo(body,35);return body;}
    internal void Approve(HostIdentity identity){identity.Trusted.Observe(PublicKey);if(!identity.Trusted.Approve(Convert.ToHexString(SHA256.HashData(PublicKey))))throw new IOException("Fixture approval failed");}
    internal byte[] Sign(byte[] core){byte[] signature=key.SignData(core,HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence);byte[] body=new byte[42+signature.Length];core.CopyTo(body,0);body[41]=(byte)signature.Length;signature.CopyTo(body,42);return body;}
    public void Dispose()=>key.Dispose();
}
