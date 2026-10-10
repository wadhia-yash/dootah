package dev.dootah.portable;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed, typed IR compiler shared by publisher and APK. Remote JS is never an input. */
public final class PortableProgram {
    public static final int ABI = 3, LOGIC_ABI = 1;
    public static final int MAX_BYTES = 65536, MAX_STRING = 256, MAX_DEPTH = 24, MAX_OPERATIONS = 512;
    public static final String LOGIC = "logic.pure.v1", TEXT = "compose.basicText.v1";
    public static final String LAYOUT = "compose.layout.v1", MATERIAL_TEXT = "compose.material3.text.v1";
    public static final String NATIVE = "compose.nativeAdapter.v1";
    private final Map<String, Entry> entries;
    private final Map<String, String> nativeAdapters;
    public final String runtimeVersion;

    public static JSONObject contract() throws org.json.JSONException {
        return new JSONObject().put("runtimeAbi", ABI).put("logicAbi", LOGIC_ABI)
            .put("capabilities", new JSONObject()
                .put(LOGIC, new JSONObject().put("version", 1).put("effect", "pure")
                    .put("inputs", new JSONArray(Arrays.asList("String", "Boolean", "Int", "String?", "Boolean?", "Int?")))
                    .put("outputs", new JSONArray(Arrays.asList("String", "Boolean", "Int", "String?", "Boolean?", "Int?")))
                    .put("implementation", "PortableProgram/JavaScriptSandbox").put("permissions", new JSONArray())
                    .put("limits", new JSONObject().put("operations", MAX_OPERATIONS).put("depth", MAX_DEPTH).put("stringUnits", MAX_STRING)))
                .put(TEXT, new JSONObject().put("version", 1).put("effect", "render")
                    .put("inputs", new JSONArray(Arrays.asList("String"))).put("outputs", new JSONArray(Arrays.asList("Unit")))
                    .put("implementation", "ComposeDispatch/BasicText").put("permissions", new JSONArray())
                    .put("limits", new JSONObject().put("stringUnits", MAX_STRING)))
                .put(LAYOUT, UiTree.layoutContract()).put(MATERIAL_TEXT, UiTree.textContract()).put(NATIVE, UiTree.nativeContract()))
            .put("limits", new JSONObject().put("payloadBytes", MAX_BYTES).put("entries", 32).put("parameters", 9)
                .put("loops", 0).put("recursion", 0).put("collections", 0).put("async", false));
    }

    public PortableProgram(String raw, String installedRuntime) throws org.json.JSONException {
        check(raw.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "Payload size");
        // Bound parser recursion before constructing any JSON objects.
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i=0; i<raw.length(); i++) {
            char c=raw.charAt(i);
            if (quoted) { if (escaped) escaped=false; else if (c=='\\') escaped=true; else if(c=='"') quoted=false; }
            else if(c=='"') quoted=true;
            else if(c=='[' || c=='{') check(++depth <= 64, "JSON nesting");
            else if(c==']' || c=='}') check(--depth >= 0, "JSON structure");
        }
        check(!quoted && depth==0, "JSON structure");
        org.json.JSONTokener tokens = new org.json.JSONTokener(raw);
        Object parsed = tokens.nextValue();
        check(parsed instanceof JSONObject && tokens.nextClean() == 0, "Artifact JSON/trailing data");
        JSONObject root = (JSONObject) parsed;
        keys(root, "schema", "runtimeAbi", "logicAbi", "runtimeVersion", "requires", "overrides");
        check("dootah.portable".equals(root.get("schema")), "Schema");
        check(integer(root.get("runtimeAbi")) == ABI && integer(root.get("logicAbi")) == LOGIC_ABI, "ABI");
        runtimeVersion = string(root.get("runtimeVersion"));
        check(runtimeVersion.equals(installedRuntime), "Runtime mismatch");
        JSONArray requires = root.getJSONArray("requires");
        Set<String> declared = new HashSet<>();
        for (int i=0;i<requires.length();i++) check(declared.add(string(requires.get(i))) &&
            Arrays.asList(LOGIC,TEXT,LAYOUT,MATERIAL_TEXT,NATIVE).contains(requires.get(i)), "Capabilities");
        Set<String> used = new HashSet<>(Collections.singleton(LOGIC));
        JSONArray overrides=root.getJSONArray("overrides");
        check(overrides.length()>0 && overrides.length()<=32, "Override count");
        Map<String, Entry> built = new LinkedHashMap<>();
        Map<String, String> adapters = new TreeMap<>();
        for(int i=0;i<overrides.length();i++) {
            JSONObject item=overrides.getJSONObject(i);
            boolean structural = item.has("tree");
            if (structural) keys(item,"functionId","parameters","slots","tree");
            else keys(item, "functionId", "parameters", "expression");
            String id=string(item.get("functionId"));
            check(id.matches("dth1:[0-9a-f]{64}") && !built.containsKey(id), "Function identity");
            JSONArray types=item.getJSONArray("parameters");
            check(types.length()<=9, "Parameter count");
            List<String> parameters=new ArrayList<>();
            for(int j=0;j<types.length();j++) { String t=string(types.get(j)); check(validType(t), "Parameter type"); parameters.add(t); }
            Counter counter = new Counter();
            UiTree tree = null;
            String code;
            if (structural) {
                JSONArray slots = item.getJSONArray("slots");
                check(slots.length()<=UiTree.MAX_SLOTS,"Slot count");
                List<String> typesList=new ArrayList<>(), expressions=new ArrayList<>();
                int strings=0;
                for(int j=0;j<slots.length();j++) {
                    JSONObject slot=slots.getJSONObject(j); keys(slot,"type","expression");
                    String type=string(slot.get("type"));
                    check(validType(type),"UI slot type");
                    if(type.startsWith("String")) check(++strings<=UiTree.MAX_TEXT_SLOTS,"Text slot count");
                    Expression expr=compile(slot.getJSONObject("expression"),parameters,0,counter);
                    check(type.equals(expr.type),"UI slot expression type");
                    typesList.add(type); expressions.add(expr.code);
                }
                tree=new UiTree(item.getJSONObject("tree"),typesList);
                code=tree.expression(expressions); used.addAll(tree.capabilities());
                for(Map.Entry<String,String> a:tree.adapters().entrySet()) {
                    check(adapters.getOrDefault(a.getKey(),a.getValue()).equals(a.getValue()),"Adapter signature disagreement");
                    adapters.put(a.getKey(),a.getValue());
                }
            } else {
                Expression expression=compile(item.getJSONObject("expression"), parameters, 0, counter);
                check(expression.type.equals("String"), "Renderer requires String");
                code=expression.code; used.add(TEXT);
            }
            check(code.getBytes(StandardCharsets.UTF_8).length + PRELUDE.length() + parameters.size()*1540 + 100 <= 65536,"Generated source budget");
            built.put(id, new Entry(Collections.unmodifiableList(new ArrayList<>(parameters)), code, counter.operations,tree));
        }
        check(declared.equals(used),"Capability requirements disagree with program");
        entries=Collections.unmodifiableMap(built);
        nativeAdapters=Collections.unmodifiableMap(adapters);
    }
    /** Every build-generated adapter this program may invoke, with its scalar signature. */
    public Map<String, String> nativeAdapters() { return nativeAdapters; }

    public Set<String> functionIds() { return entries.keySet(); }
    public boolean has(String id) { return entries.containsKey(id); }
    public List<String> parameters(String id) { check(entries.containsKey(id), "Unknown function"); return entries.get(id).parameters; }
    public int operations(String id) { return entries.get(id).operations; }
    public String expression(String id) { return entries.get(id).code; }
    public String source(String id, List<String> installedTypes, List<?> input) throws org.json.JSONException {
        Entry entry=entries.get(id);
        check(entry!=null && entry.parameters.equals(installedTypes), "Installed input ABI mismatch");
        check(input.size()==entry.parameters.size(), "Input count");
        JSONArray values=new JSONArray();
        for(int i=0;i<input.size();i++) { validateValue(input.get(i),entry.parameters.get(i)); values.put(input.get(i)==null?JSONObject.NULL:input.get(i)); }
        return "'use strict';const input="+values+";"+PRELUDE+"JSON.stringify("+entry.code+")";
    }
    public UiTree.Result renderResult(String id, String json) throws org.json.JSONException {
        check(entries.containsKey(id),"Unknown function");
        Entry entry=entries.get(id);
        return entry.tree==null ? UiTree.Result.basic(result(json)) : entry.tree.result(json);
    }
    public UiTree tree(String id) { return entries.get(id).tree; }
    public String result(String json) throws org.json.JSONException {
        // Parse a single JSON value without permitting a different result schema.
        JSONArray array=new JSONArray("["+json+"]");
        check(array.length()==1, "Result count");
        Object value=array.get(0); validateValue(value,"String");
        check(!((String)value).trim().isEmpty(), "Empty rendering");
        return (String)value;
    }
    public static final String PRELUDE =
        "function s(x){x=String(x);if(x.length>256)throw Error('string limit');return x;}"+
        "function d(a,b){if(b===0)throw Error('division by zero');return (a/b)|0;}"+
        "function r(a,b){if(b===0)throw Error('division by zero');return (a%b)|0;}";

    private static Expression compile(JSONObject node,List<String> params,int depth,Counter counter) throws org.json.JSONException {
        check(depth<=MAX_DEPTH && ++counter.operations<=MAX_OPERATIONS,"Operation/depth budget");
        String op=string(node.get("op"));
        if(op.equals("literal")) {
            keys(node,"op","type","value"); String type=string(node.get("type")); Object value=node.get("value");
            check(validType(type),"Literal type"); validateValue(value,type);
            return new Expression(type,value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):value.toString());
        }
        if(op.equals("input")) {
            keys(node,"op","index"); int i=integer(node.get("index")); check(i>=0 && i<params.size(),"Input index");
            return new Expression(params.get(i),"input["+i+"]");
        }
        keys(node,"op","args"); JSONArray args=node.getJSONArray("args");
        int arity=op.equals("seq")?args.length():op.equals("if")?3:Arrays.asList("not","neg","string").contains(op)?1:2;
        check(arity>0 && arity<=17 && args.length()==arity,"Arity"); List<Expression> a=new ArrayList<>();
        for(int i=0;i<args.length();i++) a.add(compile(args.getJSONObject(i),params,depth+1,counter));
        String x=a.get(0).code,y=arity>1?a.get(1).code:"";
        switch(op) {
            case "seq": return new Expression(a.get(a.size()-1).type,"("+String.join(",",a.stream().map(e->e.code).collect(java.util.stream.Collectors.toList()))+")");
            case "string": return new Expression("String","s("+x+")");
            case "not": check(a.get(0).type.equals("Boolean"),"Boolean operand");return new Expression("Boolean","(!"+x+")");
            case "neg": check(a.get(0).type.equals("Int"),"Int operand");return new Expression("Int","((-"+x+")|0)");
            case "if": check(a.get(0).type.equals("Boolean") && a.get(1).type.equals(a.get(2).type),"Branch types");return new Expression(a.get(1).type,"("+x+"?"+y+":"+a.get(2).code+")");
            case "and": case "or": check(a.stream().allMatch(e->e.type.equals("Boolean")),"Boolean operands");return new Expression("Boolean","("+x+(op.equals("and")?"&&":"||")+y+")");
            case "eq": case "ne": check(base(a.get(0).type).equals(base(a.get(1).type)),"Equality types");return new Expression("Boolean","("+x+(op.equals("eq")?"===":"!==")+y+")");
            case "concat": check(a.stream().allMatch(e->e.type.equals("String")),"String operands");return new Expression("String","s("+x+"+"+y+")");
            default:
                check(a.stream().allMatch(e->e.type.equals("Int")),"Int operands");
                switch(op) {
                    case "add":return new Expression("Int","(("+x+"+"+y+")|0)");
                    case "sub":return new Expression("Int","(("+x+"-"+y+")|0)");
                    case "mul":return new Expression("Int","Math.imul("+x+","+y+")");
                    case "div":return new Expression("Int","d("+x+","+y+")");
                    case "rem":return new Expression("Int","r("+x+","+y+")");
                    case "lt": case "le": case "gt": case "ge":
                        String symbol=op.equals("lt")?"<":op.equals("le")?"<=":op.equals("gt")?">":">=";
                        return new Expression("Boolean","("+x+symbol+y+")");
                    default:throw new IllegalArgumentException("Unknown operation: "+op);
                }
        }
    }
    private static String base(String t){return t.endsWith("?")?t.substring(0,t.length()-1):t;}
    public static boolean validType(String t){return Arrays.asList("String","Int","Boolean","String?","Int?","Boolean?").contains(t);}
    public static void validateValue(Object value,String type) {
        if(value==null || value==JSONObject.NULL) {check(type.endsWith("?"),"Nullability");return;}
        switch(base(type)) {
            case "String":
                check(value instanceof String,"String value");String s=(String)value;check(s.length()<=MAX_STRING,"String size");
                for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c))check(++i<s.length() && Character.isLowSurrogate(s.charAt(i)),"Surrogate");else check(!Character.isLowSurrogate(c),"Surrogate");} break;
            case "Int":integer(value);break;
            case "Boolean":check(value instanceof Boolean,"Boolean value");break;
            default:throw new IllegalArgumentException("Unknown type");
        }
    }
    static int integer(Object value){check(value instanceof Integer || value instanceof Long,"Integer value");long n=((Number)value).longValue();check(n>=Integer.MIN_VALUE && n<=Integer.MAX_VALUE,"Int range");return(int)n;}
    static String string(Object value){check(value instanceof String,"Expected String");return(String)value;}
    static void keys(JSONObject value,String... keys){Set<String> actual=new HashSet<>();value.keys().forEachRemaining(actual::add);check(actual.equals(new HashSet<>(Arrays.asList(keys))),"Unknown/missing fields");}
    public static void check(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
    private static final class Counter {int operations;}
    private static final class Expression { final String type,code; Expression(String t,String c){type=t;code=c;} }
    private static final class Entry {final List<String> parameters;final String code;final int operations;final UiTree tree;Entry(List<String>p,String c,int o,UiTree t){parameters=p;code=c;operations=o;tree=t;} }
}
