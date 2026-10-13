// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import org.json.*;

/** Checks raw syntax complexity before JSONObject's recursive parser, and bounds in-memory callers. */
final class BoundedJson {
    static final int DEPTH=64,NODES=50000,STRING=16384,TEXT=500000;
    interface Check {void run()throws IOException;}
    static JSONObject parse(String text,Check check)throws Exception{
        raw(text,check);check.run();JSONObject result=new JSONObject(text);check.run();validate(result,check);return result;
    }
    static void raw(String text,Check check)throws IOException{
        char[] stack=new char[DEPTH];int depth=0,nodes=0,characters=0;
        for(int i=0;i<text.length();i++){
            if((i&255)==0)check.run();char c=text.charAt(i);
            if(c=='"'){
                if(++nodes>NODES)bad();int length=0;boolean ended=false;
                while(++i<text.length()){
                    if((i&255)==0)check.run();c=text.charAt(i);
                    if(c=='"'){ended=true;break;}
                    if(c<32||++length>STRING||++characters>TEXT)bad();
                    if(c=='\\'){
                        if(++i>=text.length())bad();c=text.charAt(i);
                        if(c=='u'){for(int j=0;j<4;j++){if(++i>=text.length()||Character.digit(text.charAt(i),16)<0)bad();}}
                        else if("\"\\/bfnrt".indexOf(c)<0)bad();
                    }
                }
                if(!ended)bad();
            }else if(c=='{'||c=='['){if(depth==DEPTH||++nodes>NODES)bad();stack[depth++]=c;}
            else if(c=='}'||c==']'){if(depth==0||stack[--depth]!=(c=='}'?'{':'['))bad();}
            else if(c==','||c==':'||c==' '||c=='\n'||c=='\r'||c=='\t'){}
            else if(c=='t'||c=='f'||c=='n'){
                String literal=c=='t'?"true":c=='f'?"false":"null";
                if(!text.startsWith(literal,i)||++nodes>NODES)bad();i+=literal.length()-1;
            }else if(c=='-'||c>='0'&&c<='9'){
                if(++nodes>NODES)bad();int start=i;
                while(i+1<text.length()&&"0123456789.eE+-".indexOf(text.charAt(i+1))>=0){i++;if(i-start>64)bad();}
            }else bad();
        }
        if(depth!=0)bad();check.run();
    }
    private record Node(Object value,int depth){}
    static void validate(Object root,Check check)throws IOException{
        ArrayDeque<Node> work=new ArrayDeque<>();work.push(new Node(root,0));int nodes=0,characters=0;
        while(!work.isEmpty()){
            Node current=work.pop();if(++nodes>NODES||current.depth>DEPTH)bad();if((nodes&127)==0)check.run();
            Object value=current.value;
            if(value instanceof String s){if(s.length()>STRING||(characters+=s.length())>TEXT)bad();}
            else if(value instanceof JSONArray array){if(array.length()>NODES-nodes-work.size())bad();for(int i=array.length()-1;i>=0;i--)work.push(new Node(array.opt(i),current.depth+1));}
            else if(value instanceof JSONObject object){Iterator<String> keys=object.keys();while(keys.hasNext()){
                String key=keys.next();if(key.length()>STRING||(characters+=key.length())>TEXT||work.size()+nodes>=NODES)bad();
                work.push(new Node(object.opt(key),current.depth+1));
            }}
        }
        check.run();
    }
    private static void bad()throws IOException{throw new RecognitionClient.RequestException(RecognitionClient.Kind.BAD_RESPONSE,"Ответ сервиса превышает безопасные ограничения JSON.");}
    private BoundedJson(){}
}
