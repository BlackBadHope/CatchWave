// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.util.Base64;

/** Untrusted QR parsing; no settings/network changes until the owner confirms. */
final class PairingCode {
    final String host; final int port; final byte[] pin,token;
    private PairingCode(String h,int p,byte[] f,byte[] t){host=h;port=p;pin=f;token=t;}
    PairingCode loopback(int value){if(value<1024||value>65535)throw new IllegalArgumentException("Invalid port");return new PairingCode("127.0.0.1",value,pin.clone(),token.clone());}
    static PairingCode parse(String code){
        if(code==null||code.length()>256)throw new IllegalArgumentException("Invalid pairing QR");
        String[] parts=code.split("/",-1);
        if(parts.length!=5||!parts[0].equals("CW1"))throw new IllegalArgumentException("Invalid pairing QR");
        String[] address=parts[1].split("\\.",-1);
        if(address.length!=4)throw new IllegalArgumentException("Invalid host");
        for(String octet:address)if(!octet.matches("0|[1-9][0-9]{0,2}")||Integer.parseInt(octet)>255)throw new IllegalArgumentException("Invalid host");
        if(address[0].equals("0")||Integer.parseInt(address[0])>=224)throw new IllegalArgumentException("Invalid host");
        if(!parts[2].matches("[1-9][0-9]{3,4}")||!parts[3].matches("[A-Za-z0-9_-]{43}")||!parts[4].matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Invalid pairing QR");
        int port=Integer.parseInt(parts[2]);byte[] pin=Base64.getUrlDecoder().decode(parts[3]),token=Base64.getUrlDecoder().decode(parts[4]);
        if(port<1024||port>65535||pin.length!=32||token.length!=32)throw new IllegalArgumentException("Invalid pairing QR");
        if(!Base64.getUrlEncoder().withoutPadding().encodeToString(pin).equals(parts[3])||!Base64.getUrlEncoder().withoutPadding().encodeToString(token).equals(parts[4]))throw new IllegalArgumentException("Noncanonical QR");
        return new PairingCode(parts[1],port,pin,token);
    }
    String fingerprint(){StringBuilder b=new StringBuilder();for(byte v:pin){if(b.length()>0)b.append(':');b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));}return b.toString();}
}
