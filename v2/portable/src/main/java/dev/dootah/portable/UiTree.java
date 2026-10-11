package dev.dootah.portable;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.dootah.portable.PortableProgram.*;

/** ABI 3 native-owned UI data. No remote objects or commands enter the renderer. */
public final class UiTree {
    public static final int MAX_NODES=64, MAX_DEPTH=12, MAX_MODIFIERS=8, MAX_SLOTS=16, MAX_TEXT_SLOTS=8, MAX_ADAPTER_ARGUMENTS=9;
    /** Native calls nested inside native content (the root call is level 0), and callback arguments per call. */
    public static final int MAX_NESTING=4, MAX_EVENTS=8;
    // Build-generated adapter identity: callee qualified name + 64-bit shape digest (AdapterGen.ID_PATTERN).
    public static final String ADAPTER_ID="[A-Za-z_][A-Za-z0-9_.]{0,255}#[0-9a-f]{16}";
    /** A child call scoped to its container adapter: "container/child". */
    public static final String CHILD_ID=ADAPTER_ID+"/"+ADAPTER_ID;
    /** A call inside nested native content: its ancestors' identities, then its own. */
    public static final String NESTED_ID=ADAPTER_ID+"(/"+ADAPTER_ID+"){1,"+MAX_NESTING+"}";
    /** Signature markers after the data types: the trailing content lambda, a named content slot, a callback argument. */
    public static final String CONTENT="@content", SLOT="@slot:", EVENT="@event";
    public static final String SLOT_NAME="[A-Za-z_][A-Za-z0-9_]{0,63}";
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
    private final int actions;

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
            // Container adapters render installed child calls, selected and ordered by the update (one level).
            .put("content",new JSONArray(Arrays.asList("native","if")))
            .put("limits",new JSONObject().put("arguments",MAX_ADAPTER_ARGUMENTS));
    }
    UiTree(JSONObject raw,List<String> types) throws JSONException { this(raw,types,0); }
    UiTree(JSONObject raw,List<String> types,int actions) throws JSONException {
        this.types=Collections.unmodifiableList(new ArrayList<>(types));
        this.actions=actions;
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
                children(raw.getJSONArray("then"),depth,boxScope),children(raw.getJSONArray("else"),depth,boxScope),null,Collections.emptyList(),NO_CONTENT);
        }
        if(kind.equals("native")) return nativeCall(raw,depth,null);
        if(kind.equals("box")) {
            keys(raw,"type","alignment","modifiers","children"); capabilities.add(LAYOUT);
            String alignment=token(raw.get("alignment"),ALIGNMENTS,false);
            List<Mod> mods=modifiers(raw.getJSONArray("modifiers"),boxScope);
            return new Node(kind,-1,alignment,null,null,mods,children(raw.getJSONArray("children"),depth,true),Collections.emptyList(),null,Collections.emptyList(),NO_CONTENT);
        }
        check(kind.equals("text"),"Unknown UI node");
        keys(raw,"type","text","color","style","modifiers"); capabilities.add(MATERIAL_TEXT);
        return new Node(kind,slot(raw.get("text"),"String"),null,token(raw.get("color"),COLORS,true),
            token(raw.get("style"),STYLES,true),modifiers(raw.getJSONArray("modifiers"),boxScope),Collections.emptyList(),Collections.emptyList(),
            null,Collections.emptyList(),NO_CONTENT);
    }
    /**
     * A top-level native call (parent null) or a call inside its parent's content, scoped to that parent.
     * `children` is the trailing content lambda alone; `content` lists named and trailing slots in signature
     * order; `events` binds each installed callback argument to one of this entry's actions.
     */
    private Node nativeCall(JSONObject raw,int depth,String parent) throws JSONException {
        boolean single=raw.has("children"), slotted=raw.has("content"), evented=raw.has("events");
        List<String> optional=new ArrayList<>();
        if(single) optional.add("children"); if(slotted) optional.add("content"); if(evented) optional.add("events");
        check(!(single && slotted),"Content form");
        fields(raw,new String[]{"type","adapter","arguments"},optional.toArray(new String[0]));
        capabilities.add(NATIVE);
        if(slotted || evented) capabilities.add(INTERACTION);
        String adapter=string(raw.get("adapter"));
        check(parent==null ? adapter.matches(ADAPTER_ID)
            : adapter.matches(NESTED_ID) && adapter.startsWith(parent+"/") && adapter.indexOf('/',parent.length()+1)<0,"Adapter identity");
        JSONArray raws=raw.getJSONArray("arguments"); check(raws.length()<=MAX_ADAPTER_ARGUMENTS,"Adapter argument count");
        List<Integer> arguments=new ArrayList<>(); List<String> signature=new ArrayList<>();
        for(int i=0;i<raws.length();i++) {
            int index=integer(raws.get(i));
            check(index>=0 && index<types.size() && references.add(index),"Typed/unique UI slot reference");
            arguments.add(index); signature.add(types.get(index));
        }
        List<String> names=new ArrayList<>(); List<List<Node>> contents=new ArrayList<>();
        if(single) { names.add(""); contents.add(content(raw.getJSONArray("children"),depth,adapter)); }
        if(slotted) {
            JSONArray raws2=raw.getJSONArray("content"); check(raws2.length()>0 && raws2.length()<=MAX_SLOTS,"Content slot count");
            for(int i=0;i<raws2.length();i++) {
                JSONObject slot=raws2.getJSONObject(i); keys(slot,"slot","children");
                String name=string(slot.get("slot")); check((name.isEmpty() || name.matches(SLOT_NAME)) && !names.contains(name),"Content slot name");
                names.add(name); contents.add(content(slot.getJSONArray("children"),depth,adapter));
            }
        }
        for(String name:names) signature.add(name.isEmpty()?CONTENT:SLOT+name);
        List<Integer> events=new ArrayList<>();
        if(evented) {
            JSONArray raws3=raw.getJSONArray("events"); check(raws3.length()>0 && raws3.length()<=MAX_EVENTS,"Event count");
            for(int i=0;i<raws3.length();i++) { int a=integer(raws3.get(i)); check(a>=0 && a<actions,"Event action"); events.add(a); signature.add(EVENT); }
        }
        String joined=String.join(",",signature);
        check(adapters.getOrDefault(adapter,joined).equals(joined),"Adapter signature disagreement");
        adapters.put(adapter,joined);
        return new Node("native",-1,null,null,null,Collections.emptyList(),contents.size()==1?contents.get(0):Collections.emptyList(),
            Collections.emptyList(),adapter,Collections.unmodifiableList(arguments),
            new Content(Collections.unmodifiableList(names),Collections.unmodifiableList(contents),Collections.unmodifiableList(events)));
    }
    /** Container content: child calls of that container and if/if-else over them. */
    private List<Node> content(JSONArray raw,int depth,String parent) throws JSONException {
        check(raw.length()<=MAX_NODES,"UI child count"); List<Node> list=new ArrayList<>();
        for(int i=0;i<raw.length();i++) {
            JSONObject item=raw.getJSONObject(i);
            check(depth+1<=MAX_DEPTH && ++nodes<=MAX_NODES,"UI tree budget");
            if(string(item.get("type")).equals("if")) {
                keys(item,"type","condition","then","else"); capabilities.add(LAYOUT);
                int condition=slot(item.get("condition"),"Boolean");
                list.add(new Node("if",condition,null,null,null,Collections.emptyList(),content(item.getJSONArray("then"),depth+1,parent),
                    content(item.getJSONArray("else"),depth+1,parent),null,Collections.emptyList(),NO_CONTENT));
            } else {
                check(string(item.get("type")).equals("native"),"Unknown content node");
                check(parent.split("/").length<=MAX_NESTING,"Native content nesting");
                list.add(nativeCall(item,depth+1,parent));
            }
        }
        return Collections.unmodifiableList(list);
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
        if(node.kind.equals("native")) { for(List<Node> slot:node.contents) for(Node child:slot) emit(child,expressions,code); }
        else for(Node child:node.children) emit(child,expressions,code);
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
        if(node.kind.equals("native")) { for(List<Node> slot:node.contents) for(Node child:slot) validateActive(child,values,active); return; }
        List<Node> children=node.kind.equals("if") && !((Boolean)values.get(node.slot)) ? node.otherwise : node.children;
        for(Node child:children) validateActive(child,values,active);
    }
    public static final class Node {
        public final String kind,alignment,color,style,adapter;
        public final int slot;
        public final List<Mod> modifiers;
        public final List<Node> children,otherwise;
        public final List<Integer> arguments;
        /** A native container: renders [contents] inside its installed content lambdas. */
        public final boolean container;
        /** Native calls: content slot names ("" is the trailing lambda) and their nodes, in signature order. */
        public final List<String> slotNames;
        public final List<List<Node>> contents;
        /** Native calls: the action bound to each installed callback argument, in signature order. */
        public final List<Integer> events;
        private Node(String k,int s,String a,String c,String t,List<Mod> m,List<Node> children,List<Node> otherwise,String adapter,
                     List<Integer> arguments,Content content) {
            kind=k;slot=s;alignment=a;color=c;style=t;modifiers=m;this.children=children;this.otherwise=otherwise;
            this.adapter=adapter;this.arguments=arguments;container=!content.contents.isEmpty();
            slotNames=content.names;contents=content.contents;events=content.events;
        }
    }
    private static final class Content {
        final List<String> names; final List<List<Node>> contents; final List<Integer> events;
        Content(List<String> n,List<List<Node>> c,List<Integer> e){names=n;contents=c;events=e;}
    }
    private static final Content NO_CONTENT=new Content(Collections.emptyList(),Collections.emptyList(),Collections.emptyList());
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
