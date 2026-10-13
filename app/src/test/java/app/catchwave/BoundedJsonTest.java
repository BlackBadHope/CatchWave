package app.catchwave;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class BoundedJsonTest {
    private static void rejected(String text)throws Exception{
        try{BoundedJson.parse(text,()->{});fail("Complex response accepted");}
        catch(RecognitionClient.RequestException expected){assertEquals(RecognitionClient.Kind.BAD_RESPONSE,expected.kind);}
    }
    @Test public void deepBodyBelowByteCapFailsBeforeRecursiveParser()throws Exception{
        rejected("{\"a\":".repeat(2000)+"0"+"}".repeat(2000));
    }
    @Test public void wideBodyBelowByteCapIsRejected()throws Exception{
        rejected("{\"a\":["+"0,".repeat(BoundedJson.NODES)+"0]}");
    }
    @Test public void largeEscapedAndPlainFieldsAreRejected()throws Exception{
        rejected("{\"a\":\""+"x".repeat(BoundedJson.STRING+1)+"\"}");
        rejected("{\"a\":\""+"\\u0041".repeat(BoundedJson.STRING+1)+"\"}");
    }
    @Test public void unquotedAndCommentSyntaxCannotBypassLexicalBounds()throws Exception{
        rejected("{a:'value'}");rejected("{/* ignored */\"a\":1}");
    }
    @Test public void stringsContainingBracketsAndEscapedQuotesRemainValid()throws Exception{
        JSONObject value=BoundedJson.parse("{\"a\":\"{[\\\"]}\",\"b\":[true,false,null,1.5]}",()->{});
        assertEquals(4,value.getJSONArray("b").length());assertEquals("{[\"]}",value.getString("a"));
    }
    @Test public void cancellationIsObservedDuringRawParse()throws Exception{
        java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        try{BoundedJson.parse("{\"a\":["+"0,".repeat(1000)+"0]}",()->{if(checks.incrementAndGet()>3)throw new java.io.IOException("cancelled");});fail("Cancellation ignored");}
        catch(java.io.IOException expected){assertEquals("cancelled",expected.getMessage());}
    }
    @Test public void inMemoryCallerCannotBypassDepthOrCreateCycles()throws Exception{
        JSONObject root=new JSONObject();root.put("self",root);
        try{BoundedJson.validate(root,()->{});fail("Cycle accepted");}catch(RecognitionClient.RequestException expected){}
    }
}
