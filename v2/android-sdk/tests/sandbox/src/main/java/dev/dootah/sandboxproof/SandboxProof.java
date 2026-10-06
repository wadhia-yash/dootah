package dev.dootah.sandboxproof;

import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Base64;
import dev.dootah.runtime.sandbox.RestrictedSandbox;
import org.json.JSONObject;
import dev.dootah.portable.PortableProgram;
import java.util.List;
import java.nio.charset.StandardCharsets;

public final class SandboxProof extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        JSONObject evidence = new JSONObject();
        try (var sandbox = new RestrictedSandbox(getTargetContext())) {
            String remote = new String(Base64.decode(arguments.getString("program"), Base64.DEFAULT), StandardCharsets.UTF_8);
            evidence.put("remoteResult", sandbox.evaluate(remote));
            evidence.put("portable", portable(sandbox));
            String[] names = {"NativeModules", "TurboModuleRegistry", "nativeModuleProxy", "__turboModuleProxy", "expo", "require", "__r", "__d", "Context", "Activity", "Java", "Packages", "fetch", "XMLHttpRequest", "WebSocket", "File", "FileReader", "localStorage", "indexedDB", "setTimeout", "setInterval", "nativeCallSyncHook", "__fbBatchedBridge", "process"};
            JSONObject globals = new JSONObject();
            for (String name : names) {
                String type = sandbox.evaluate("typeof globalThis[" + JSONObject.quote(name) + "]");
                if (!type.equals("undefined")) throw new AssertionError(name + " unexpectedly available: " + type);
                globals.put(name, type);
            }
            evidence.put("blockedGlobals", globals);
            evidence.put("globalNames", sandbox.evaluate("JSON.stringify(Object.getOwnPropertyNames(globalThis).sort())"));
            evidence.put("androidNames", sandbox.evaluate("JSON.stringify(Object.getOwnPropertyNames(android).sort())"));
            evidence.put("unknownNamedData", sandbox.evaluate("android.consumeNamedDataAsArrayBuffer('file:///data/local/tmp/dootah').then(()=> 'UNSAFE',()=> 'BLOCKED')"));
            if (!"BLOCKED".equals(evidence.getString("unknownNamedData"))) throw new AssertionError("Unexpected named data access");
            evidence.put("eval", sandbox.evaluate("String((0,eval)('1+2'))"));
            evidence.put("Function", sandbox.evaluate("String(new Function('return 3')())"));
            evidence.put("constructorNative", sandbox.evaluate("(()=>{}).constructor('return typeof globalThis.nativeModuleProxy')()"));
            evidence.put("import", sandbox.evaluate("import('react-native').then(()=> 'UNSAFE',()=> 'BLOCKED')"));
            if (!"BLOCKED".equals(evidence.getString("import"))) throw new AssertionError("Import allowed");
            try { sandbox.evaluate("while(true){}"); throw new AssertionError("Unbounded execution returned"); }
            catch (java.util.concurrent.TimeoutException expected) { evidence.put("timeout", "terminated"); }
            if (!sandbox.evaluate("'alive'").equals("alive")) throw new AssertionError("No recovery");
            evidence.put("afterTimeout", "PASS");
            sandbox.evaluate("globalThis.previous = 42; 'set'");
            if (!sandbox.evaluate("typeof globalThis.previous").equals("undefined")) throw new AssertionError("State leaked");
            evidence.put("freshIsolate", "PASS");
            JSONObject attempts = new JSONObject();
            for (String attempt : new String[] {"NativeModules.Networking", "TurboModuleRegistry.get('Networking')", "expo.modules.ExponentFileSystem", "android.getContext()", "Java.type('java.io.File')", "fetch('http://127.0.0.1:3101/')", "require('fs').readFileSync('/data/local/tmp/test')", "(()=>{}).constructor('return nativeModuleProxy.Networking')()"}) {
                try { sandbox.evaluate(attempt); throw new AssertionError("Native attempt succeeded: " + attempt); }
                catch (java.util.concurrent.ExecutionException expected) { attempts.put(attempt, expected.getCause().getClass().getSimpleName()); }
            }
            evidence.put("nativeAttempts", attempts);
            try { sandbox.evaluate("'x'.repeat(20000)"); throw new AssertionError("Large result accepted"); }
            catch (java.util.concurrent.ExecutionException expected) { evidence.put("resultLimit", expected.getCause().getClass().getSimpleName()); }
            try { sandbox.evaluate("const a=[]; while(true) a.push(new Array(10000).fill(a));"); throw new AssertionError("Heap exhaustion returned"); }
            catch (java.util.concurrent.ExecutionException expected) { evidence.put("heapLimit", expected.getCause().getClass().getSimpleName()); }
            sandbox.close();
            try (var replacement = new RestrictedSandbox(getTargetContext())) {
                evidence.put("afterHeap", replacement.evaluate("'PASS'"));
            }
            evidence.put("status", "PASS");
            output.putString("evidence", evidence.toString());
            finish(-1, output);
        } catch (Throwable error) {
            output.putString("failure", android.util.Log.getStackTraceString(error));
            output.putString("partialEvidence", evidence.toString());
            finish(0, output);
        }
    }
    private JSONObject portable(RestrictedSandbox sandbox) throws Exception {
        String id = "dth1:" + "a".repeat(64);
        String expression = "{\"op\":\"string\",\"args\":[{\"op\":\"sub\",\"args\":[{\"op\":\"input\",\"index\":0},{\"op\":\"input\",\"index\":1}]}]}";
        JSONObject root = new JSONObject().put("schema","dootah.portable").put("runtimeAbi",2).put("logicAbi",1)
            .put("runtimeVersion","proof").put("requires",new org.json.JSONArray(List.of(PortableProgram.LOGIC,PortableProgram.TEXT)))
            .put("overrides",new org.json.JSONArray().put(new JSONObject().put("functionId",id)
                .put("parameters",new org.json.JSONArray(List.of("Int","Int"))).put("expression",new JSONObject(expression))));
        PortableProgram p = new PortableProgram(root.toString(),"proof");
        String result = p.result(sandbox.evaluate(p.source(id,List.of("Int","Int"),List.of(100,20))));
        if (!result.equals("80")) throw new AssertionError("Portable calculation");
        int rejected=0;
        for (String key : List.of("runtimeAbi","logicAbi","runtimeVersion","requires")) {
            JSONObject bad = new JSONObject(root.toString()).put(key,"INVALID");
            try { new PortableProgram(bad.toString(),"proof"); throw new AssertionError("Accepted " + key); }
            catch (IllegalArgumentException | org.json.JSONException expected) { rejected++; }
        }
        for (Object bad : List.of("100",true,2147483648L,new Object())) {
            try {p.source(id,List.of("Int","Int"),List.of(bad,20));throw new AssertionError("Accepted malformed value");}
            catch (IllegalArgumentException expected) {rejected++;}
        }
        try {p.source(id,List.of("String","Int"),List.of("100",20));throw new AssertionError("Accepted input ABI");}
        catch (IllegalArgumentException expected) {rejected++;}
        for (String op : List.of("invokeNative","eval","import","loop")) {
            JSONObject bad=new JSONObject(root.toString());
            bad.getJSONArray("overrides").getJSONObject(0).put("expression",new JSONObject().put("op",op).put("args",new org.json.JSONArray()));
            try {new PortableProgram(bad.toString(),"proof");throw new AssertionError("Accepted unknown operation");}
            catch (IllegalArgumentException expected) {rejected++;}
        }
        for (org.json.JSONArray requirements : List.of(new org.json.JSONArray(),new org.json.JSONArray(List.of("logic.pure.v2",PortableProgram.TEXT)),new org.json.JSONArray(List.of("navigation.openRoute.v1",PortableProgram.TEXT)))) {
            JSONObject bad=new JSONObject(root.toString()).put("requires",requirements);
            try {new PortableProgram(bad.toString(),"proof");throw new AssertionError("Accepted capability");}
            catch (IllegalArgumentException expected) {rejected++;}
        }
        JSONObject tree=new JSONObject().put("op","literal").put("type","String").put("value","x");
        for(int i=0;i<9;i++) tree=new JSONObject().put("op","concat").put("args",new org.json.JSONArray().put(tree).put(tree));
        JSONObject oversized=new JSONObject(root.toString());
        oversized.getJSONArray("overrides").getJSONObject(0).put("expression",tree);
        try {new PortableProgram(oversized.toString(),"proof");throw new AssertionError("Accepted operation overflow");}
        catch (IllegalArgumentException expected) {rejected++;}
        long bridgeStart=android.os.SystemClock.elapsedRealtimeNanos();
        for(int i=0;i<20;i++) sandbox.evaluate("'80'");
        double bridgeMs=(android.os.SystemClock.elapsedRealtimeNanos()-bridgeStart)/20.0/1000000.0;
        long compileStart=android.os.SystemClock.elapsedRealtimeNanos();
        for(int i=0;i<100;i++) new PortableProgram(root.toString(),"proof");
        double compileMs=(android.os.SystemClock.elapsedRealtimeNanos()-compileStart)/100.0/1000000.0;
        long start = android.os.SystemClock.elapsedRealtimeNanos();
        for (int i=0;i<20;i++) {
            String current = p.result(sandbox.evaluate(p.source(id,List.of("Int","Int"),List.of(100,20))));
            if (!current.equals("80")) throw new AssertionError("Non deterministic result");
        }
        return new JSONObject().put("result",result).put("rejections",rejected)
            .put("meanBridgeAndIsolateMs",bridgeMs).put("meanValidationCompileMs",compileMs)
            .put("meanEvaluationMs",(android.os.SystemClock.elapsedRealtimeNanos()-start)/20.0/1000000.0);
    }

}
