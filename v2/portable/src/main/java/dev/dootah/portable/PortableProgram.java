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
    /** Compose-owned state, actions over installed callbacks, and named/nested content slots of native adapters. */
    public static final String INTERACTION = "compose.interaction.v1";
    public static final int MAX_STATE = 8, MAX_ACTIONS = 16, MAX_STEPS = 8, MAX_CALLBACK_ARGUMENTS = 1;
    /**
     * An installed callback parameter: a non-suspend, non-composable Kotlin function type returning Unit with at
     * most one scalar parameter (the token never contains the hook type list's ',' separator).
     */
    public static final String CALLBACK = "\\((String|Int|Boolean)?\\)->Unit";
    private final Map<String, Entry> entries;
    private final Map<String, String> nativeAdapters;
    public final String runtimeVersion;

    /** The capability document this build installs. */
    public static JSONObject contract() throws org.json.JSONException { return contract(true); }
    /**
     * The document installed before compose.interaction.v1. Publisher and Cloud still accept such a baseline
     * and never send it a program that requires the newer capability; the device re-checks `requires`.
     */
    public static JSONObject previousContract() throws org.json.JSONException { return contract(false); }
    /** Exactly one of the negotiated documents, or refused; returns whether it installs compose.interaction.v1. */
    public static boolean negotiate(JSONObject installed) throws org.json.JSONException {
        if (same(contract(true), installed)) return true;
        check(same(contract(false), installed), "Unknown installed capability contract");
        return false;
    }
    /** Structural JSON equality (Android's org.json has no `similar`). Numbers compare by value. */
    static boolean same(Object a, Object b) throws org.json.JSONException {
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x=(JSONObject)a, y=(JSONObject)b;
            if (x.length()!=y.length()) return false;
            for (Iterator<String> keys=x.keys(); keys.hasNext();) { String k=keys.next(); if (!y.has(k) || !same(x.get(k),y.get(k))) return false; }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x=(JSONArray)a, y=(JSONArray)b;
            if (x.length()!=y.length()) return false;
            for (int i=0;i<x.length();i++) if (!same(x.get(i),y.get(i))) return false;
            return true;
        }
        if (a instanceof Number && b instanceof Number) return ((Number)a).doubleValue()==((Number)b).doubleValue();
        return a.equals(b);
    }
    public static boolean callbackType(String t) { return t.matches(CALLBACK); }
    /** Argument types of a callback token, e.g. "(Boolean)->Unit" -> [Boolean]. */
    public static List<String> callbackArguments(String t) {
        check(callbackType(t), "Callback type");
        String inner = t.substring(1, t.indexOf(')'));
        return inner.isEmpty() ? Collections.emptyList() : Collections.singletonList(inner);
    }
    private static JSONObject interactionContract() throws org.json.JSONException {
        return new JSONObject().put("version", 1).put("effect", "render").put("permissions", new JSONArray())
            .put("implementation", "ComposeDispatch/remember+mutableStateOf; installed callbacks")
            .put("state", new JSONArray(Arrays.asList("String", "Int", "Boolean")))
            .put("callbackArguments", new JSONArray(Arrays.asList("String", "Int", "Boolean")))
            .put("steps", new JSONArray(Arrays.asList("set", "call")))
            .put("content", new JSONArray(Arrays.asList("slot", "event")))
            .put("limits", new JSONObject().put("state", MAX_STATE).put("actions", MAX_ACTIONS).put("steps", MAX_STEPS)
                .put("callbackArguments", MAX_CALLBACK_ARGUMENTS).put("nesting", UiTree.MAX_NESTING).put("events", UiTree.MAX_EVENTS));
    }
    private static JSONObject contract(boolean interaction) throws org.json.JSONException {
        JSONObject document = new JSONObject().put("runtimeAbi", ABI).put("logicAbi", LOGIC_ABI)
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
        if (interaction) document.getJSONObject("capabilities").put(INTERACTION, interactionContract());
        return document;
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
            Arrays.asList(LOGIC,TEXT,LAYOUT,MATERIAL_TEXT,NATIVE,INTERACTION).contains(requires.get(i)), "Capabilities");
        Set<String> used = new HashSet<>(Collections.singleton(LOGIC));
        JSONArray overrides=root.getJSONArray("overrides");
        check(overrides.length()>0 && overrides.length()<=32, "Override count");
        Map<String, Entry> built = new LinkedHashMap<>();
        Map<String, String> adapters = new TreeMap<>();
        for(int i=0;i<overrides.length();i++) {
            JSONObject item=overrides.getJSONObject(i);
            boolean structural = item.has("tree");
            if (structural) fields(item,new String[]{"functionId","parameters","slots","tree"},new String[]{"state","actions"});
            else keys(item, "functionId", "parameters", "expression");
            String id=string(item.get("functionId"));
            check(id.matches("dth1:[0-9a-f]{64}") && !built.containsKey(id), "Function identity");
            JSONArray types=item.getJSONArray("parameters");
            check(types.length()<=9, "Parameter count");
            // Installed hook parameters in order: scalars become Logic inputs; callbacks stay native handles.
            List<String> parameters=new ArrayList<>(), inputs=new ArrayList<>();
            List<Integer> scalars=new ArrayList<>(), callbacks=new ArrayList<>();
            for(int j=0;j<types.length();j++) {
                String t=string(types.get(j));
                if(validType(t)) { inputs.add(t); scalars.add(j); }
                else { check(structural && callbackType(t), "Parameter type"); callbacks.add(j); used.add(INTERACTION); }
                parameters.add(t);
            }
            List<String> stateTypes=new ArrayList<>(); List<Object> initials=new ArrayList<>();
            if (structural && item.has("state")) {
                JSONArray state=item.getJSONArray("state"); check(state.length()>0 && state.length()<=MAX_STATE,"State count"); used.add(INTERACTION);
                for(int j=0;j<state.length();j++) {
                    JSONObject s=state.getJSONObject(j); keys(s,"type","initial");
                    String t=string(s.get("type")); check(Arrays.asList("String","Int","Boolean").contains(t),"State type");
                    // Initial values are literals: the native side creates the state before any evaluation.
                    Object initial=s.get("initial"); validateValue(initial,t);
                    stateTypes.add(t); initials.add(initial instanceof Long ? integer(initial) : initial);
                }
            }
            inputs.addAll(stateTypes);
            Counter counter = new Counter();
            UiTree tree = null;
            List<Action> actions=new ArrayList<>();
            String code;
            if (structural && item.has("actions")) {
                JSONArray rawActions=item.getJSONArray("actions"); check(rawActions.length()>0 && rawActions.length()<=MAX_ACTIONS,"Action count"); used.add(INTERACTION);
                for(int j=0;j<rawActions.length();j++) actions.add(action(rawActions.getJSONObject(j),inputs,parameters,stateTypes.size(),counter));
            }
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
                    Expression expr=compile(slot.getJSONObject("expression"),inputs,0,counter);
                    check(type.equals(expr.type),"UI slot expression type");
                    typesList.add(type); expressions.add(expr.code);
                }
                tree=new UiTree(item.getJSONObject("tree"),typesList,actions.size());
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
            int actionBytes=0; for(Action a:actions) actionBytes=Math.max(actionBytes,a.code.getBytes(StandardCharsets.UTF_8).length);
            check(Math.max(code.getBytes(StandardCharsets.UTF_8).length,actionBytes) + PRELUDE.length() + (inputs.size()+2)*1540 + 100 <= 65536,"Generated source budget");
            built.put(id, new Entry(Collections.unmodifiableList(new ArrayList<>(parameters)), code, counter.operations,tree,
                Collections.unmodifiableList(inputs),new Interaction(scalars,callbacks,stateTypes,initials,actions)));
        }
        check(declared.equals(used),"Capability requirements disagree with program");
        entries=Collections.unmodifiableMap(built);
        nativeAdapters=Collections.unmodifiableMap(adapters);
    }
    /** Every build-generated adapter this program may invoke, with its scalar signature. */
    public Map<String, String> nativeAdapters() { return nativeAdapters; }

    public Set<String> functionIds() { return entries.keySet(); }
    public boolean has(String id) { return entries.containsKey(id); }
    /** Installed hook parameter tokens, in order: scalar types and callback types. */
    public List<String> parameters(String id) { check(entries.containsKey(id), "Unknown function"); return entries.get(id).parameters; }
    /** Logic inputs of the render expression: the scalar parameters, then the state values. */
    public List<String> inputTypes(String id) { check(entries.containsKey(id), "Unknown function"); return entries.get(id).inputs; }
    public Interaction interaction(String id) { check(entries.containsKey(id), "Unknown function"); return entries.get(id).interaction; }
    public int operations(String id) { return entries.get(id).operations; }
    public String expression(String id) { return entries.get(id).code; }
    public String source(String id, List<String> installedTypes, List<?> input) throws org.json.JSONException {
        Entry entry=entries.get(id);
        check(entry!=null && entry.parameters.equals(installedTypes), "Installed input ABI mismatch");
        return source(id, input);
    }
    /** The render evaluation over validated Logic inputs ({@link #inputTypes}); no callback or native object enters. */
    public String source(String id, List<?> input) throws org.json.JSONException {
        Entry entry=entries.get(id); check(entry!=null, "Unknown function");
        return "'use strict';const input="+values(input,entry.inputs)+";"+PRELUDE+"JSON.stringify("+entry.code+")";
    }
    /** One event action over the inputs, the state at the event, and the event's own arguments. */
    public String actionSource(String id, int action, List<?> input) throws org.json.JSONException {
        Action a=interaction(id).action(action);
        return "'use strict';const input="+values(input,a.inputs)+";"+PRELUDE+"JSON.stringify("+a.code+")";
    }
    /** The step values of one action, validated against their declared types, in step order. */
    public List<Object> actionResult(String id, int action, String json) throws org.json.JSONException {
        Action a=interaction(id).action(action);
        check(json.getBytes(StandardCharsets.UTF_8).length<=UiTree.MAX_RESULT_BYTES,"Action result size");
        for(char c:json.toCharArray()) check(c!='{',"Scalar action result required");
        org.json.JSONTokener tokener=new org.json.JSONTokener(json); Object parsed=tokener.nextValue();
        check(parsed instanceof JSONArray && tokener.nextClean()==0,"Action result array/trailing data");
        JSONArray array=(JSONArray)parsed; check(array.length()==a.valueTypes.size(),"Action result count");
        List<Object> values=new ArrayList<>();
        for(int i=0;i<array.length();i++) {
            Object v=array.get(i); check(!(v instanceof JSONArray),"Scalar action result required"); validateValue(v,a.valueTypes.get(i));
            values.add(v==JSONObject.NULL?null:v instanceof Number?(Object)integer(v):v);
        }
        return Collections.unmodifiableList(values);
    }
    private static JSONArray values(List<?> input,List<String> types) throws org.json.JSONException {
        check(input.size()==types.size(), "Input count");
        JSONArray values=new JSONArray();
        for(int i=0;i<input.size();i++) { validateValue(input.get(i),types.get(i)); values.put(input.get(i)==null?JSONObject.NULL:input.get(i)); }
        return values;
    }
    private static Action action(JSONObject raw,List<String> inputs,List<String> parameters,int state,Counter counter) throws org.json.JSONException {
        keys(raw,"parameters","steps");
        JSONArray rawParameters=raw.getJSONArray("parameters"); check(rawParameters.length()<=MAX_CALLBACK_ARGUMENTS,"Event parameter count");
        List<String> all=new ArrayList<>(inputs), eventTypes=new ArrayList<>();
        for(int i=0;i<rawParameters.length();i++) {
            String t=string(rawParameters.get(i)); check(Arrays.asList("String","Int","Boolean").contains(t),"Event parameter type");
            eventTypes.add(t); all.add(t);
        }
        JSONArray rawSteps=raw.getJSONArray("steps"); check(rawSteps.length()>0 && rawSteps.length()<=MAX_STEPS,"Step count");
        int stateBase=inputs.size()-state;
        List<Step> steps=new ArrayList<>(); List<String> valueTypes=new ArrayList<>(), code=new ArrayList<>();
        for(int i=0;i<rawSteps.length();i++) {
            JSONObject s=rawSteps.getJSONObject(i); String op=string(s.get("op"));
            if(op.equals("set")) {
                keys(s,"op","state","value");
                int target=integer(s.get("state")); check(target>=0 && target<state,"State index");
                Expression value=compile(s.getJSONObject("value"),all,0,counter);
                check(value.type.equals(inputs.get(stateBase+target)),"State value type");
                steps.add(new Step(true,target,1)); valueTypes.add(value.type); code.add(value.code);
            } else {
                check(op.equals("call"),"Unknown step"); keys(s,"op","callback","arguments");
                int target=integer(s.get("callback")); check(target>=0 && target<parameters.size() && callbackType(parameters.get(target)),"Callback index");
                List<String> expected=callbackArguments(parameters.get(target));
                JSONArray args=s.getJSONArray("arguments"); check(args.length()==expected.size(),"Callback arity");
                for(int j=0;j<args.length();j++) {
                    Expression value=compile(args.getJSONObject(j),all,0,counter);
                    check(value.type.equals(expected.get(j)),"Callback argument type");
                    valueTypes.add(value.type); code.add(value.code);
                }
                steps.add(new Step(false,target,args.length()));
            }
        }
        return new Action(Collections.unmodifiableList(eventTypes),Collections.unmodifiableList(all),Collections.unmodifiableList(steps),
            Collections.unmodifiableList(valueTypes),"["+String.join(",",code)+"]");
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
    static void fields(JSONObject value,String[] required,String[] optional){
        Set<String> actual=new HashSet<>();value.keys().forEachRemaining(actual::add);
        Set<String> allowed=new HashSet<>(Arrays.asList(required));allowed.addAll(Arrays.asList(optional));
        check(actual.containsAll(Arrays.asList(required)) && allowed.containsAll(actual),"Unknown/missing fields");
    }
    static void keys(JSONObject value,String... keys){Set<String> actual=new HashSet<>();value.keys().forEachRemaining(actual::add);check(actual.equals(new HashSet<>(Arrays.asList(keys))),"Unknown/missing fields");}
    public static void check(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
    private static final class Counter {int operations;}
    private static final class Expression { final String type,code; Expression(String t,String c){type=t;code=c;} }
    private static final class Entry {
        final List<String> parameters,inputs;final String code;final int operations;final UiTree tree;final Interaction interaction;
        Entry(List<String>p,String c,int o,UiTree t,List<String> i,Interaction x){parameters=p;code=c;operations=o;tree=t;inputs=i;interaction=x;}
    }
    /** How the native side binds one hooked call: scalar and callback parameter positions, state, and actions. */
    public static final class Interaction {
        public final List<Integer> scalars,callbacks; public final List<String> stateTypes; public final List<Object> initials;
        private final List<Action> actions;
        Interaction(List<Integer> s,List<Integer> c,List<String> t,List<Object> i,List<Action> a){
            scalars=Collections.unmodifiableList(s);callbacks=Collections.unmodifiableList(c);stateTypes=Collections.unmodifiableList(t);
            initials=Collections.unmodifiableList(i);actions=Collections.unmodifiableList(a);
        }
        public int actionCount(){return actions.size();}
        public Action action(int i){check(i>=0 && i<actions.size(),"Action index");return actions.get(i);}
    }
    /** An event action: typed event parameters and ordered steps, all evaluated over one input snapshot. */
    public static final class Action {
        public final List<String> parameters,inputs; public final List<Step> steps; final List<String> valueTypes; final String code;
        Action(List<String> p,List<String> i,List<Step> s,List<String> v,String c){parameters=p;inputs=i;steps=s;valueTypes=v;code=c;}
    }
    /** Assign one state value, or invoke one installed callback parameter with [values] arguments. */
    public static final class Step {
        public final boolean set; public final int target, values;
        Step(boolean s,int t,int v){set=s;target=t;values=v;}
    }
}
