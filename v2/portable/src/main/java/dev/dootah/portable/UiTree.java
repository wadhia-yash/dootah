package dev.dootah.portable;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.dootah.portable.PortableProgram.*;

/** ABI 3 native-owned UI data. No remote objects or commands enter the renderer. */
public final class UiTree {
    public static final int MAX_NODES=64, MAX_DEPTH=12, MAX_MODIFIERS=8, MAX_SLOTS=16, MAX_TEXT_SLOTS=8, MAX_ADAPTER_ARGUMENTS=9;
    // Build-generated adapter identity: callee qualified name + 64-bit shape digest (AdapterGen.ID_PATTERN).
    public static final String ADAPTER_ID="[A-Za-z_][A-Za-z0-9_.]{0,255}#[0-9a-f]{16}";
    // Eight maximally escaped 256-unit strings + eight booleans + punctuation fit below 16 KiB.
    public static final int MAX_RESULT_BYTES=16384;
    public static final List<String> ALIGNMENTS=Collections.unmodifiableList(Arrays.asList(
        "TopStart","TopCenter","TopEnd","CenterStart","Center","CenterEnd","BottomStart","BottomCenter","BottomEnd"));
    public static final List<String> COLORS=Collections.unmodifiableList(Arrays.asList("secondary","tertiary"));
    public static final List<String> STYLES=Collections.unmodifiableList(Arrays.asList("bodyMedium","labelSmall"));
    public final Node root;
    private final List<String> types;
    private final Set<String> capabilities=new HashSet<>();
    private int nodes;
    private final Set<Integer> references=new HashSet<>();
    private final Map<String,String> adapters=new TreeMap<>();

    public static JSONObject layoutContract() throws JSONException {
        return new JSONObject().put("version",1).put("effect","render").put("permissions",new JSONArray())
            .put("implementation","ComposeDispatch/Box").put("nodes",new JSONArray(Arrays.asList("box","if")))
            .put("modifiers",new JSONArray(Arrays.asList("padding","fillMaxWidth","align")))
            .put("alignments",new JSONArray(ALIGNMENTS))
            .put("limits",new JSONObject().put("nodes",MAX_NODES).put("depth",MAX_DEPTH).put("modifiers",MAX_MODIFIERS)
                .put("slots",MAX_SLOTS).put("textSlots",MAX_TEXT_SLOTS).put("dp",1024).put("resultBytes",MAX_RESULT_BYTES));
    }
    public static JSONObject textContract() throws JSONException {
        return new JSONObject().put("version",1).put("effect","render").put("permissions",new JSONArray())
            .put("implementation","ComposeDispatch/Material3.Text").put("colors",new JSONArray(COLORS))
            .put("styles",new JSONArray(STYLES)).put("limits",new JSONObject().put("stringUnits",MAX_STRING));
    }
    public static JSONObject nativeContract() throws JSONException {
        return new JSONObject().put("version",1).put("effect","render").put("permissions",new JSONArray())
            .put("implementation","NativeAdapterRegistry/build-generated")
            .put("inputs",new JSONArray(Arrays.asList("String","Boolean","Int","String?","Boolean?","Int?")))
            .put("limits",new JSONObject().put("arguments",MAX_ADAPTER_ARGUMENTS));
    }
    UiTree(JSONObject raw,List<String> types) throws JSONException {
        this.types=Collections.unmodifiableList(new ArrayList<>(types));
        // Box/Text roots acknowledge from their own draw; conditional and native roots from the committed frame.
        root=parse(raw,0,false);
        check(references.size()==types.size(),"Unused UI slot");
    }
    public Set<String> capabilities() { return Collections.unmodifiableSet(capabilities); }
    /** Adapter id -> parameter signature for every native call; checked against the installed registry. */
    public Map<String,String> adapters() { return Collections.unmodifiableMap(adapters); }
    private int slot(Object raw,String type) {
        int index=integer(raw);
        check(index>=0 && index<types.size() && types.get(index).equals(type) && references.add(index),"Typed/unique UI slot reference");
        return index;
    }
    private Node parse(JSONObject raw,int depth,boolean boxScope) throws JSONException {
        check(depth<=MAX_DEPTH && ++nodes<=MAX_NODES,"UI tree budget");
        String kind=string(raw.get("type"));
        if(kind.equals("if")) {
            keys(raw,"type","condition","then","else"); capabilities.add(LAYOUT);
            int condition=slot(raw.get("condition"),"Boolean");
            return new Node(kind,condition,null,null,null,Collections.emptyList(),
                children(raw.getJSONArray("then"),depth,boxScope),children(raw.getJSONArray("else"),depth,boxScope),null,Collections.emptyList());
        }
        if(kind.equals("native")) {
            keys(raw,"type","adapter","arguments"); capabilities.add(NATIVE);
            String adapter=string(raw.get("adapter")); check(adapter.matches(ADAPTER_ID),"Adapter identity");
            JSONArray raws=raw.getJSONArray("arguments"); check(raws.length()<=MAX_ADAPTER_ARGUMENTS,"Adapter argument count");
            List<Integer> arguments=new ArrayList<>(); List<String> signature=new ArrayList<>();
            for(int i=0;i<raws.length();i++) {
                int index=integer(raws.get(i));
                check(index>=0 && index<types.size() && references.add(index),"Typed/unique UI slot reference");
                arguments.add(index); signature.add(types.get(index));
            }
            String joined=String.join(",",signature);
            check(adapters.getOrDefault(adapter,joined).equals(joined),"Adapter signature disagreement");
            adapters.put(adapter,joined);
            return new Node(kind,-1,null,null,null,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),
                adapter,Collections.unmodifiableList(arguments));
        }
        if(kind.equals("box")) {
            keys(raw,"type","alignment","modifiers","children"); capabilities.add(LAYOUT);
            String alignment=token(raw.get("alignment"),ALIGNMENTS,false);
            List<Mod> mods=modifiers(raw.getJSONArray("modifiers"),boxScope);
            return new Node(kind,-1,alignment,null,null,mods,children(raw.getJSONArray("children"),depth,true),Collections.emptyList(),null,Collections.emptyList());
        }
        check(kind.equals("text"),"Unknown UI node");
        keys(raw,"type","text","color","style","modifiers"); capabilities.add(MATERIAL_TEXT);
        return new Node(kind,slot(raw.get("text"),"String"),null,token(raw.get("color"),COLORS,true),
            token(raw.get("style"),STYLES,true),modifiers(raw.getJSONArray("modifiers"),boxScope),Collections.emptyList(),Collections.emptyList(),
            null,Collections.emptyList());
    }
    private List<Node> children(JSONArray raw,int depth,boolean boxScope) throws JSONException {
        check(raw.length()<=MAX_NODES,"UI child count"); List<Node> list=new ArrayList<>();
        for(int i=0;i<raw.length();i++) list.add(parse(raw.getJSONObject(i),depth+1,boxScope));
        return Collections.unmodifiableList(list);
    }
    private List<Mod> modifiers(JSONArray raw,boolean boxScope) throws JSONException {
        check(raw.length()<=MAX_MODIFIERS,"Modifier count"); List<Mod> list=new ArrayList<>();
        for(int i=0;i<raw.length();i++) {
            JSONObject m=raw.getJSONObject(i); String op=string(m.get("op")); capabilities.add(LAYOUT);
            switch(op) {
                case "fillMaxWidth": keys(m,"op"); list.add(new Mod(op,null,0,0,0,0)); break;
                case "padding": keys(m,"op","start","top","end","bottom");
                    list.add(new Mod(op,null,dp(m.get("start")),dp(m.get("top")),dp(m.get("end")),dp(m.get("bottom")))); break;
                case "align": keys(m,"op","alignment"); check(boxScope,"align requires BoxScope");
                    list.add(new Mod(op,token(m.get("alignment"),ALIGNMENTS,false),0,0,0,0)); break;
                default: throw new IllegalArgumentException("Unknown modifier");
            }
        }
        return Collections.unmodifiableList(list);
    }
    private static int dp(Object value) { int n=integer(value); check(n>=0 && n<=1024,"Dp range"); return n; }
    private static String token(Object value,List<String> allowed,boolean optional) {
        if(optional && value==JSONObject.NULL) return null;
        String token=string(value); check(allowed.contains(token),"Unknown token"); return token;
    }
    String expression(List<String> expressions) {
        StringBuilder code=new StringBuilder("(()=>{const r=Array("+types.size()+").fill(null);");
        emit(root,expressions,code); return code.append("return r;})()").toString();
    }
    private static void emit(Node node,List<String> expressions,StringBuilder code) {
        if(node.slot>=0) code.append("r[").append(node.slot).append("]=").append(expressions.get(node.slot)).append(';');
        for(int slot:node.arguments) code.append("r[").append(slot).append("]=").append(expressions.get(slot)).append(';');
        if(node.kind.equals("if")) code.append("if(r[").append(node.slot).append("]){");
        for(Node child:node.children) emit(child,expressions,code);
        if(node.kind.equals("if")) {
            code.append("}else{"); for(Node child:node.otherwise) emit(child,expressions,code); code.append('}');
        }
    }
    Result result(String raw) throws JSONException {
        check(raw.getBytes(StandardCharsets.UTF_8).length<=MAX_RESULT_BYTES,"UI result size");
        // Do not recursively parse a malicious nested sandbox result.
        int depth=0; boolean quoted=false,escaped=false;
        for(char c:raw.toCharArray()) {
            if(quoted) { if(escaped) escaped=false; else if(c=='\\') escaped=true; else if(c=='"') quoted=false; }
            else if(c=='"') quoted=true;
            else if(c=='[' || c=='{') check(c=='[' && ++depth==1,"Scalar slot array required");
            else if(c==']') check(--depth==0,"Result nesting");
        }
        check(!quoted && depth==0,"Result structure");
        JSONTokener tokener=new JSONTokener(raw); Object parsed=tokener.nextValue();
        check(parsed instanceof JSONArray && tokener.nextClean()==0,"Result array/trailing data");
        JSONArray array=(JSONArray)parsed; check(array.length()==types.size(),"Result slot count");
        List<Object> values=new ArrayList<>(); for(int i=0;i<array.length();i++) values.add(array.get(i));
        Set<Integer> active=new HashSet<>(); validateActive(root,values,active);
        for(int i=0;i<values.size();i++) if(!active.contains(i)) check(values.get(i)==JSONObject.NULL,"Inactive slot must be null");
        // JSON may carry an in-range Int as a long; native code receives exactly Int.
        for(int i=0;i<values.size();i++) if(values.get(i) instanceof Number) values.set(i,integer(values.get(i)));
        return new Result(root,Collections.unmodifiableList(values),null);
    }
    private void validateActive(Node node,List<Object> values,Set<Integer> active) {
        if(node.slot>=0) { validateValue(values.get(node.slot),types.get(node.slot)); active.add(node.slot); }
        for(int slot:node.arguments) { validateValue(values.get(slot),types.get(slot)); active.add(slot); }
        List<Node> children=node.kind.equals("if") && !((Boolean)values.get(node.slot)) ? node.otherwise : node.children;
        for(Node child:children) validateActive(child,values,active);
    }
    public static final class Node {
        public final String kind,alignment,color,style,adapter;
        public final int slot;
        public final List<Mod> modifiers;
        public final List<Node> children,otherwise;
        public final List<Integer> arguments;
        private Node(String k,int s,String a,String c,String t,List<Mod> m,List<Node> children,List<Node> otherwise,String adapter,List<Integer> arguments) {
            kind=k;slot=s;alignment=a;color=c;style=t;modifiers=m;this.children=children;this.otherwise=otherwise;
            this.adapter=adapter;this.arguments=arguments;
        }
    }
    public static final class Mod {
        public final String op,alignment;
        public final int start,top,end,bottom;
        private Mod(String o,String a,int s,int t,int e,int b){op=o;alignment=a;start=s;top=t;end=e;bottom=b;}
    }
    public static final class Result {
        public final Node root;
        public final List<Object> slots;
        public final String basicText;
        private Result(Node n,List<Object> s,String b){root=n;slots=s;basicText=b;}
        static Result basic(String text){return new Result(null,Collections.emptyList(),text);}
    }
}
