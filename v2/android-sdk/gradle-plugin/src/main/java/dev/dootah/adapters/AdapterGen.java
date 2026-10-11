package dev.dootah.adapters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;

/**
 * Native adapters: ordinary Kotlin, generated at build time and compiled by the application's own
 * Kotlin/Compose compiler. Shared by the Gradle plugin (generation) and the publisher (lowering),
 * so both sides derive the same identity from the same call. No compiler mutation, no reflection.
 *
 * An adapter is a copy of one real call that an eligible hook body makes to an app-owned composable.
 * Arguments that read the hook's data (inputs and its `remember { mutableStateOf(..) }` state) become typed
 * values; every other argument is copied verbatim and stays frozen in the APK, and omitted arguments keep
 * their Kotlin defaults. A lambda argument is either a content slot, whose installed child calls the update
 * selects and orders, or a callback, which the update binds to an action over state and installed
 * callbacks. Selection is syntactic and deliberately narrow; anything doubtful produces no adapter.
 */
public final class AdapterGen {
    public static final String INDEX_PACKAGE = AdapterNames.INDEX_PACKAGE, INDEX = AdapterNames.INDEX;
    public static final String METHOD_PREFIX = AdapterNames.METHOD_PREFIX, FACADE_PREFIX = AdapterNames.FACADE_PREFIX,
        REGISTRY_PREFIX = AdapterNames.REGISTRY_PREFIX;
    private static final String ID = "[A-Za-z_][A-Za-z0-9_.]{0,255}#[0-9a-f]{16}";
    /** Native calls nested inside native content; the root call is level 0. */
    public static final int MAX_NESTING = 4;
    /** A top-level adapter, or a call nested in its ancestors' content ("root/child/grandchild"). */
    public static final String ID_PATTERN = ID + "(/" + ID + "){0," + MAX_NESTING + "}";
    /** Signature markers after the data types: the trailing content lambda, a named content slot, a callback argument. */
    public static final String CONTENT = "@content", SLOT = "@slot:", EVENT = "@event";
    /** Lowered structurally by the publisher instead. */
    public static final Set<String> STRUCTURAL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("Box", "Text")));
    public static final List<String> SCALARS = Collections.unmodifiableList(Arrays.asList("String", "Int", "Boolean", "String?", "Int?", "Boolean?"));
    /** Installed callback parameters: Unit-returning function types with at most one scalar parameter. */
    public static final String CALLBACK = "\\((String|Int|Boolean)?\\)->Unit";
    public static final int MAX_PARAMETERS = 9, MAX_ARGUMENTS = 32, MAX_STATE = 8;

    private AdapterGen() {}

    /** One value argument of a copied call: frozen text, a typed data value, a content slot, or a callback. */
    public static final class Argument {
        public final String name, frozen, type;
        public final KtExpression expression;
        public final Slot slot;
        public final Event event;
        Argument(String name, String frozen, String type, KtExpression expression, Slot slot, Event event) {
            this.name = name; this.frozen = frozen; this.type = type; this.expression = expression; this.slot = slot; this.event = event;
        }
    }

    /** A content lambda. Its children are the installed calls it makes, each scoped to this slot. */
    public static final class Slot {
        /** The argument name; "" for the trailing lambda. */
        public final String name;
        public final int index;
        public final KtBlockExpression body;
        public final Map<String, Shape> children = new LinkedHashMap<>();
        Slot(String name, int index, KtBlockExpression body) { this.name = name; this.index = index; this.body = body; }
        public String marker() { return name.isEmpty() ? CONTENT : SLOT + name; }
    }

    /** A callback argument: an event lambda over state and installed callbacks, or a hook callback passed on. */
    public static final class Event {
        public final String name;
        public final int index;
        public final KtExpression expression;
        /** The forwarded callback parameter: "it", the lambda's own parameter name, or null when none is read. */
        public final String parameter;
        Event(String name, int index, KtExpression expression, String parameter) {
            this.name = name; this.index = index; this.expression = expression; this.parameter = parameter;
        }
    }

    public static final class Shape {
        public final String id, callee, method;
        /** The callee as written at the call site (a simple name, possibly an import alias). */
        final String written;
        public final List<Argument> arguments;
        /** The trailing lambda, a content slot or a callback; null when absent. */
        public final Argument trailing;
        public final List<Slot> slots;
        public final List<Event> events;
        /** Container adapters copy the call and render installed child calls inside their content lambdas. */
        public final boolean container;
        /** Direct children across all slots. */
        public final Map<String, Shape> children = new TreeMap<>();
        Shape(String id, String callee, String method, String written, List<Argument> arguments, Argument trailing,
              List<Slot> slots, List<Event> events) {
            this.id = id; this.callee = callee; this.method = method; this.written = written; this.arguments = arguments;
            this.trailing = trailing; this.slots = slots; this.events = events; this.container = !slots.isEmpty();
        }
        public List<String> types() {
            List<String> types = new ArrayList<>();
            for (Argument a : arguments) if (a.type != null) types.add(a.type);
            return types;
        }
        public String signature() {
            List<String> tokens = types();
            for (Slot s : slots) tokens.add(s.marker());
            for (int i = 0; i < events.size(); i++) tokens.add(EVENT);
            return String.join(",", tokens);
        }
        /** JVM name the adapter body must invoke (alias imports resolve to the declared name). */
        public String calleeName() { return callee.substring(callee.lastIndexOf('.') + 1); }
        /** This call's children and theirs, depth first. */
        public List<Shape> descendants() {
            List<Shape> all = new ArrayList<>();
            for (Slot s : slots) for (Shape c : s.children.values()) { all.add(c); all.addAll(c.descendants()); }
            return all;
        }
        void add(Slot slot, Shape child) {
            Shape existing = slot.children.putIfAbsent(child.id, child);
            children.putIfAbsent(child.id, child);
            if (existing != null) for (int i = 0; i < child.slots.size(); i++) {
                for (Shape grandchild : child.slots.get(i).children.values()) existing.add(existing.slots.get(i), grandchild);
            }
        }
    }

    /** What the hook binds: data names with their types, installed callbacks with their arity, and state. */
    public static final class Hook {
        public final Set<String> scope, privateNames;
        /** Scalar parameters and state variables -> type. */
        public final Map<String, String> inputs;
        /** Callback parameters -> parameter count. */
        public final Map<String, Integer> callbacks;
        /** `var x by remember { mutableStateOf(literal) }` -> type, in declaration order. */
        public final Map<String, String> state;
        Hook(Set<String> scope, Set<String> privateNames, Map<String, String> inputs, Map<String, Integer> callbacks, Map<String, String> state) {
            this.scope = scope; this.privateNames = privateNames; this.inputs = inputs; this.callbacks = callbacks; this.state = state;
        }
    }

    public static final class Result {
        public final String path, packageName, facade, registry;
        public final List<Shape> adapters;
        public final List<String> refused;
        Result(String path, String packageName, String facade, String registry, List<Shape> adapters, List<String> refused) {
            this.path = path; this.packageName = packageName; this.facade = facade; this.registry = registry;
            this.adapters = adapters; this.refused = refused;
        }
        public String qualifiedRegistry() { return packageName.isEmpty() ? registry : packageName + "." + registry; }
        public String qualifiedFacade() { return packageName.isEmpty() ? facade : packageName + "." + facade; }
        /** Every adapter identity and its signature, nested children included. */
        public Map<String, String> signatures() {
            Map<String, String> all = new TreeMap<>();
            for (Shape s : adapters) {
                all.put(s.id, s.signature());
                for (Shape c : s.descendants()) all.put(c.id, c.signature());
            }
            return all;
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    /** Module-relative source path with '/' separators; part of every identity in the file. */
    public static String fileHash(String path) { return sha256("dootah-adapter-file-v1|" + path).substring(0, 12); }

    /** Generates the adapters for one source file. Refusals are reported, never thrown. */
    public static Result generate(KtFile file, String path) {
        String pkg = file.getPackageFqName().asString();
        String hash = fileHash(path);
        Map<String, Shape> adapters = new TreeMap<>();
        List<String> refused = new ArrayList<>();
        for (KtNamedFunction function : hooks(file)) {
            Hook hook;
            try { hook = hook(function, file); }
            catch (IllegalArgumentException e) { refused.add(function.getName() + ": " + e.getMessage()); continue; }
            Function<KtExpression, String> typer = e -> dataType(e, hook.inputs);
            List<KtCallExpression> calls = new ArrayList<>(PsiTreeUtil.findChildrenOfType(function.getBodyExpression(), KtCallExpression.class));
            if (function.getBodyExpression() instanceof KtCallExpression) calls.add(0, (KtCallExpression) function.getBodyExpression());
            Set<PsiElement> lambdas = new HashSet<>();
            for (KtCallExpression call : calls) {
                if (within(call, lambdas)) continue; // inside an adapted call's content or callback
                if (!candidate(call) || !statementPosition(call, function)) continue;
                try {
                    require(statementInHook(call, function), "Not a direct statement of the hook body (lambda scope or nested expression)");
                    Shape shape = shape(call, file, path, hook, typer);
                    expand(shape, file, hook, typer, 0);
                    Shape existing = adapters.get(shape.id);
                    if (existing == null) adapters.put(shape.id, shape);
                    else for (int i = 0; i < shape.slots.size(); i++) {
                        for (Shape c : shape.slots.get(i).children.values()) existing.add(existing.slots.get(i), c);
                    }
                    for (KtLambdaArgument l : call.getLambdaArguments()) lambdas.add(l);
                    for (KtValueArgument a : arguments(call)) if (a.getArgumentExpression() instanceof KtLambdaExpression) lambdas.add(a);
                } catch (IllegalArgumentException e) {
                    refused.add(function.getName() + ": " + call.getCalleeExpression().getText() + ": " + e.getMessage());
                }
            }
        }
        return new Result(path, pkg, FACADE_PREFIX + hash, REGISTRY_PREFIX + hash,
            Collections.unmodifiableList(new ArrayList<>(adapters.values())), Collections.unmodifiableList(refused));
    }

    /** Builds the installed children of every content slot, recursively and within the nesting bound. */
    private static void expand(Shape shape, KtFile file, Hook hook, Function<KtExpression, String> typer, int depth) {
        for (Slot slot : shape.slots) {
            for (KtCallExpression call : contentCalls(slot.body)) {
                require(depth < MAX_NESTING, "Native content nesting exceeds " + MAX_NESTING);
                Shape child = childShape(shape, slot, call, file, hook, typer);
                expand(child, file, hook, typer, depth + 1);
                shape.add(slot, child);
            }
        }
    }

    /** A capitalised call outside the structural vocabulary, written as `Name(...)`. */
    public static boolean candidate(KtCallExpression call) {
        if (!(call.getCalleeExpression() instanceof KtNameReferenceExpression)) return false;
        String name = ((KtNameReferenceExpression) call.getCalleeExpression()).getReferencedName();
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0)) && !STRUCTURAL.contains(name)
            && !(call.getParent() instanceof KtDotQualifiedExpression || call.getParent() instanceof KtSafeQualifiedExpression);
    }

    private static boolean within(PsiElement element, Set<PsiElement> ancestors) {
        for (PsiElement e = element.getParent(); e != null; e = e.getParent()) if (ancestors.contains(e)) return true;
        return false;
    }

    /** The single plain trailing content lambda of a container call (no parameters, no other lambdas). */
    public static KtBlockExpression contentBody(KtCallExpression call) {
        require(call.getLambdaArguments().size() == 1, "Exactly one trailing content lambda");
        KtLambdaExpression lambda = call.getLambdaArguments().get(0).getLambdaExpression();
        require(contentLambda(lambda), "Content lambda parameters unsupported");
        return lambda.getBodyExpression();
    }

    private static boolean contentLambda(KtLambdaExpression lambda) {
        return lambda != null && lambda.getFunctionLiteral().getValueParameterList() == null
            && lambda.getFunctionLiteral().getArrow() == null && lambda.getBodyExpression() != null;
    }

    /** Content statements: capitalised child calls, possibly under if/if-else. Anything else refuses the container. */
    public static List<KtCallExpression> contentCalls(KtBlockExpression body) {
        List<KtCallExpression> calls = new ArrayList<>();
        for (KtExpression statement : body.getStatements()) content(statement, calls);
        return calls;
    }

    private static void content(KtExpression statement, List<KtCallExpression> calls) {
        if (statement instanceof KtBlockExpression) {
            for (KtExpression s : ((KtBlockExpression) statement).getStatements()) content(s, calls);
        } else if (statement instanceof KtIfExpression) {
            KtIfExpression branch = (KtIfExpression) statement;
            require(branch.getThen() != null, "Empty content branch");
            content(branch.getThen(), calls);
            if (branch.getElse() != null) content(branch.getElse(), calls);
        } else {
            require(statement instanceof KtCallExpression && childCandidate((KtCallExpression) statement), "Unsupported content statement");
            calls.add((KtCallExpression) statement);
        }
    }

    /** A child call: any capitalised `Name(...)`; Box/Text are ordinary children here. Its own lambdas nest. */
    public static boolean childCandidate(KtCallExpression call) {
        if (!(call.getCalleeExpression() instanceof KtNameReferenceExpression)) return false;
        String name = ((KtNameReferenceExpression) call.getCalleeExpression()).getReferencedName();
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    /**
     * Top-level, non-extension, non-generic composables with at most nine inputs, each a scalar or an installed
     * callback (a Unit function type with scalar parameters): the hookable form.
     */
    public static List<KtNamedFunction> hooks(KtFile file) {
        List<String> imports = new ArrayList<>();
        for (KtImportDirective d : file.getImportDirectives()) if (d.getImportPath() != null) imports.add(d.getImportPath().getPathStr());
        List<KtNamedFunction> result = new ArrayList<>();
        for (KtDeclaration declaration : file.getDeclarations()) {
            if (!(declaration instanceof KtNamedFunction)) continue;
            KtNamedFunction f = (KtNamedFunction) declaration;
            if (f.getBodyExpression() == null || f.getReceiverTypeReference() != null || !f.getTypeParameters().isEmpty()
                || f.hasModifier(KtTokens.INLINE_KEYWORD) || f.hasModifier(KtTokens.SUSPEND_KEYWORD)
                || f.getValueParameters().size() > MAX_PARAMETERS || !composable(f, imports)) continue;
            boolean eligible = true;
            for (KtParameter p : f.getValueParameters()) {
                if (p.isVarArg() || p.getTypeReference() == null) { eligible = false; continue; }
                String type = type(p.getTypeReference().getText());
                if (!SCALARS.contains(type) && !type.matches(CALLBACK)) eligible = false;
            }
            if (eligible) result.add(f);
        }
        return result;
    }

    private static boolean composable(KtNamedFunction f, List<String> imports) {
        for (KtAnnotationEntry a : f.getAnnotationEntries()) {
            String name = a.getTypeReference() == null ? null : a.getTypeReference().getText();
            if ("androidx.compose.runtime.Composable".equals(name) || ("Composable".equals(name) &&
                (imports.contains("androidx.compose.runtime.Composable") || imports.contains("androidx.compose.runtime.*")))) return true;
        }
        return false;
    }

    /** Normalised type text: no `kotlin.` qualifiers or whitespace ("(kotlin.Boolean) -> Unit" is "(Boolean)->Unit"). */
    public static String type(String text) { return text.replace("kotlin.", "").replaceAll("\\s+", ""); }

    /** Scalar and callback parameters by name; callbacks map to their declared type. */
    public static Map<String, String> inputs(KtNamedFunction function) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (KtParameter p : function.getValueParameters()) inputs.put(p.getName(), type(p.getTypeReference().getText()));
        return inputs;
    }

    /**
     * The hook's bindings. State is `var name by remember { mutableStateOf(literal) }` written directly in the
     * body; its type is the literal's (Boolean, Int or String), and it is read and assigned like a scalar input.
     */
    public static Hook hook(KtNamedFunction function, KtFile file) {
        Map<String, String> inputs = new LinkedHashMap<>(), state = new LinkedHashMap<>();
        Map<String, Integer> callbacks = new LinkedHashMap<>();
        for (Map.Entry<String, String> p : inputs(function).entrySet()) {
            if (p.getValue().matches(CALLBACK)) callbacks.put(p.getKey(), callbackArity(p.getValue()));
            else inputs.put(p.getKey(), p.getValue());
        }
        if (function.getBodyExpression() instanceof KtBlockExpression) {
            for (KtExpression s : ((KtBlockExpression) function.getBodyExpression()).getStatements()) {
                if (!(s instanceof KtProperty) || ((KtProperty) s).getDelegateExpression() == null) continue;
                KtProperty p = (KtProperty) s;
                String type = stateType(p);
                require(type != null, "Unsupported state declaration: " + p.getName());
                require(state.size() < MAX_STATE && !inputs.containsKey(p.getName()) && !callbacks.containsKey(p.getName()), "State declaration");
                state.put(p.getName(), type);
            }
        }
        inputs.putAll(state);
        return new Hook(scope(function), privateNames(file), Collections.unmodifiableMap(inputs),
            Collections.unmodifiableMap(callbacks), Collections.unmodifiableMap(state));
    }

    static int callbackArity(String type) {
        return type.startsWith("()") ? 0 : 1;
    }

    /** `var x by remember { mutableStateOf(<Boolean/Int/String literal>) }`, untyped or with the literal's type; else null. */
    public static String stateType(KtProperty p) {
        if (!p.isVar() || p.getModifierList() != null || !(p.getDelegateExpression() instanceof KtCallExpression)) return null;
        KtCallExpression remember = (KtCallExpression) p.getDelegateExpression();
        if (!"remember".equals(remember.getCalleeExpression() == null ? null : remember.getCalleeExpression().getText())
            || !remember.getValueArguments().isEmpty() && remember.getLambdaArguments().size() != remember.getValueArguments().size()
            || remember.getLambdaArguments().size() != 1 || !remember.getTypeArguments().isEmpty()) return null;
        KtLambdaExpression lambda = remember.getLambdaArguments().get(0).getLambdaExpression();
        if (!contentLambda(lambda) || lambda.getBodyExpression().getStatements().size() != 1
            || !(lambda.getBodyExpression().getStatements().get(0) instanceof KtCallExpression)) return null;
        KtCallExpression create = (KtCallExpression) lambda.getBodyExpression().getStatements().get(0);
        if (!"mutableStateOf".equals(create.getCalleeExpression() == null ? null : create.getCalleeExpression().getText())
            || create.getValueArguments().size() != 1 || !create.getLambdaArguments().isEmpty() || !create.getTypeArguments().isEmpty()) return null;
        String type = literalType(create.getValueArguments().get(0).getArgumentExpression());
        if (type == null) return null;
        return p.getTypeReference() == null || type(p.getTypeReference().getText()).equals(type) ? type : null;
    }

    static String literalType(KtExpression e) {
        if (e instanceof KtConstantExpression) {
            if (e.getText().equals("true") || e.getText().equals("false")) return "Boolean";
            if (e.getText().matches("0|[1-9][0-9]{0,8}")) return "Int";
        }
        if (e instanceof KtStringTemplateExpression && !e.getText().startsWith("\"\"\"")) {
            for (KtStringTemplateEntry entry : ((KtStringTemplateExpression) e).getEntries()) {
                if (!(entry instanceof KtLiteralStringTemplateEntry)) return null;
            }
            return "String";
        }
        return null;
    }

    /** Every name the hook body or its parameters bind; an argument naming one of these reads hook data. */
    public static Set<String> scope(KtNamedFunction function) {
        Set<String> scope = new HashSet<>(inputs(function).keySet());
        for (KtNamedDeclaration d : PsiTreeUtil.findChildrenOfType(function.getBodyExpression(), KtNamedDeclaration.class)) {
            if (d.getName() != null) scope.add(d.getName());
        }
        return scope;
    }

    /** File-private top-level names cannot be reached from the generated file. */
    public static Set<String> privateNames(KtFile file) {
        Set<String> names = new HashSet<>();
        for (KtDeclaration d : file.getDeclarations()) {
            if (d instanceof KtNamedDeclaration && d.hasModifier(KtTokens.PRIVATE_KEYWORD) && ((KtNamedDeclaration) d).getName() != null) {
                names.add(((KtNamedDeclaration) d).getName());
            }
        }
        return names;
    }

    /** Written as a statement, not as an argument, condition or receiver. */
    public static boolean statementPosition(KtCallExpression call, KtNamedFunction function) {
        PsiElement parent = call.getParent();
        return parent instanceof KtBlockExpression || parent instanceof KtContainerNodeForControlStructureBody
            || parent instanceof KtWhenEntry || parent == function;
    }

    /** True when every ancestor up to the hook is a block or an if/when branch: no implicit receivers. */
    public static boolean statementInHook(KtCallExpression call, KtNamedFunction function) {
        if (!statementPosition(call, function)) return false;
        for (PsiElement e = call.getParent(); e != null && e != function; e = e.getParent()) {
            if (!(e instanceof KtBlockExpression || e instanceof KtContainerNodeForControlStructureBody || e instanceof KtIfExpression
                || e instanceof KtWhenEntry || e instanceof KtWhenExpression || e instanceof KtContainerNode)) return false;
        }
        return true;
    }

    /** Generation-side data typing: only forms whose type is evident without resolution. */
    static String dataType(KtExpression expression, Map<String, String> inputs) {
        if (expression instanceof KtStringTemplateExpression) return "String";
        if (expression instanceof KtNameReferenceExpression) {
            String type = inputs.get(((KtNameReferenceExpression) expression).getReferencedName());
            require(type != null, "Reads a local value; only hook inputs, state and string templates become adapter parameters");
            require(!type.endsWith("?"), "Nullable input may be smart-cast at the call site");
            return type;
        }
        throw new IllegalArgumentException("Computed argument from hook data");
    }

    /** Resolves the callee to a qualified name from explicit imports or the file's own package. */
    static String callee(String name, KtFile file) {
        String found = null;
        boolean star = false;
        for (KtImportDirective d : file.getImportDirectives()) {
            if (d.getImportPath() == null) continue;
            if (d.getImportPath().isAllUnder()) { star = true; continue; }
            String visible = d.getAliasName() != null ? d.getAliasName() : d.getImportPath().getImportedName().asString();
            if (visible.equals(name)) {
                require(found == null, "Ambiguous import: " + name);
                found = d.getImportPath().getPathStr();
            }
        }
        if (found != null) return found;
        require(!star, "Wildcard imports require semantic resolution: " + name);
        String pkg = file.getPackageFqName().asString();
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /**
     * The identity and typed contract of one call written in the hook body. `dataType` types each argument
     * that reads hook data: generation accepts only evident forms, while the publisher supplies the type of
     * the lowered Logic ABI 1 expression. Both see the same frozen text and therefore the same identity.
     */
    public static Shape shape(KtCallExpression call, KtFile file, String path, Hook hook, Function<KtExpression, String> dataType) {
        require(candidate(call), "Not an app-owned composable call");
        return build(call, file, hook, dataType, "dootah-adapter-v1|" + path, "");
    }

    /**
     * A call written inside a content slot of [container]. Its identity is scoped to that container and slot,
     * and its copy is generated inside the copied content lambda, so implicit receivers resolve as in source.
     */
    public static Shape childShape(Shape container, Slot slot, KtCallExpression call, KtFile file, Hook hook,
                                   Function<KtExpression, String> dataType) {
        require(container.slots.contains(slot), "Not a content slot of this container");
        require(childCandidate(call), "Not a child call");
        // The trailing slot keeps the original child scope, so earlier container identities are unchanged.
        String scope = "dootah-adapter-child-v1|" + container.id + (slot.name.isEmpty() ? "" : "|" + slot.name);
        return build(call, file, hook, dataType, scope, container.id + "/");
    }

    private static List<KtValueArgument> arguments(KtCallExpression call) {
        return call.getValueArgumentList() == null ? Collections.emptyList() : call.getValueArgumentList().getArguments();
    }

    private static Shape build(KtCallExpression call, KtFile file, Hook hook, Function<KtExpression, String> dataType,
                               String identityPrefix, String idPrefix) {
        String name = ((KtNameReferenceExpression) call.getCalleeExpression()).getReferencedName();
        require(!hook.scope.contains(name) && !hook.privateNames.contains(name), "Local or file-private callee");
        require(call.getTypeArguments().isEmpty(), "Explicit type arguments");
        require(call.getLambdaArguments().size() <= 1, "Several trailing lambdas");
        List<KtValueArgument> values = arguments(call);
        require(values.size() <= MAX_ARGUMENTS, "Argument count");
        String callee = callee(name, file);
        List<Argument> arguments = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        StringBuilder identity = new StringBuilder(identityPrefix).append('|').append(callee);
        int parameters = 0;
        for (KtValueArgument argument : values) {
            KtExpression expression = argument.getArgumentExpression();
            require(expression != null && argument.getSpreadElement() == null, "Unsupported argument form");
            String label = argument.isNamed() ? argument.getArgumentName().getAsName().asString() : null;
            String key = label == null ? "#" + arguments.size() : label;
            require(label != null || !(expression instanceof KtLambdaExpression), "Positional lambda argument");
            Argument special = lambdaArgument(label, expression, hook, slots, events);
            if (special != null) {
                arguments.add(special);
                identity.append('|').append(key).append(special.slot != null ? "=S" : special.event.parameter == null ? "=E0" : "=E1");
                continue;
            }
            require(!(expression instanceof KtLambdaExpression) && !(expression instanceof KtCallableReferenceExpression)
                && !(expression instanceof KtThisExpression) && !(expression instanceof KtObjectLiteralExpression)
                && PsiTreeUtil.findChildrenOfAnyType(expression, KtLambdaExpression.class, KtNamedFunction.class,
                    KtObjectLiteralExpression.class, KtThisExpression.class, KtSuperExpression.class,
                    KtCallableReferenceExpression.class, KtReturnExpression.class, KtBreakExpression.class,
                    KtContinueExpression.class).isEmpty(), "Callbacks, receivers and object arguments are not adapter inputs");
            Set<String> names = new HashSet<>();
            if (expression instanceof KtNameReferenceExpression) names.add(((KtNameReferenceExpression) expression).getReferencedName());
            for (KtNameReferenceExpression r : PsiTreeUtil.findChildrenOfType(expression, KtNameReferenceExpression.class)) names.add(r.getReferencedName());
            require(Collections.disjoint(names, hook.privateNames), "Argument reads a file-private declaration");
            require(Collections.disjoint(names, hook.callbacks.keySet()), "Callback used in a data argument");
            if (Collections.disjoint(names, hook.scope)) {
                String frozen = expression.getText();
                arguments.add(new Argument(label, frozen, null, expression, null, null));
                identity.append('|').append(key).append("=F:").append(frozen.replaceAll("\\s+", " "));
            } else {
                String type = dataType.apply(expression);
                require(SCALARS.contains(type), "Argument is not a scalar");
                require(++parameters <= MAX_PARAMETERS, "Adapter parameter count");
                arguments.add(new Argument(label, null, type, expression, null, null));
                identity.append('|').append(key).append("=P:").append(type);
            }
        }
        Argument trailing = null;
        if (!call.getLambdaArguments().isEmpty()) {
            trailing = lambdaArgument("", call.getLambdaArguments().get(0).getLambdaExpression(), hook, slots, events);
            require(trailing != null, "Trailing lambda is neither content nor a callback");
            identity.append(trailing.slot != null ? "|content" : trailing.event.parameter == null ? "|trailing=E0" : "|trailing=E1");
        }
        String hex = sha256(identity.toString()).substring(0, 16);
        return new Shape(idPrefix + callee + "#" + hex, callee, METHOD_PREFIX + hex, name, Collections.unmodifiableList(arguments),
            trailing, Collections.unmodifiableList(slots), Collections.unmodifiableList(events));
    }

    /**
     * A lambda (or a hook callback passed on) as a content slot or a callback; null when the argument is
     * neither. Content holds only capitalised calls under if/else; a callback holds only assignments to hook
     * state and calls to the hook's own callbacks. Its body is never copied: the update's action replaces it.
     */
    private static Argument lambdaArgument(String label, KtExpression expression, Hook hook, List<Slot> slots, List<Event> events) {
        if (expression instanceof KtNameReferenceExpression && hook.callbacks.containsKey(((KtNameReferenceExpression) expression).getReferencedName())) {
            int arity = hook.callbacks.get(((KtNameReferenceExpression) expression).getReferencedName());
            Event event = new Event(label == null ? "" : label, events.size(), expression, arity == 1 ? "it" : null);
            events.add(event);
            return new Argument(label, null, null, expression, null, event);
        }
        if (!(expression instanceof KtLambdaExpression)) return null;
        KtLambdaExpression lambda = (KtLambdaExpression) expression;
        require(lambda.getBodyExpression() != null && !lambda.getBodyExpression().getStatements().isEmpty(), "Empty lambda argument");
        if (eventLambda(lambda, hook)) {
            List<KtParameter> parameters = lambda.getFunctionLiteral().getValueParameters();
            String forwarded = parameters.isEmpty() ? (readsIt(lambda) ? "it" : null) : parameters.get(0).getName();
            Event event = new Event(label, events.size(), lambda, forwarded);
            events.add(event);
            return new Argument(label, null, null, lambda, null, event);
        }
        require(contentLambda(lambda), "Lambda argument is neither content nor a supported callback");
        try { contentCalls(lambda.getBodyExpression()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Lambda argument is neither content nor a supported callback: " + e.getMessage()); }
        // label is "" for the trailing lambda and the argument name otherwise.
        require(label.isEmpty() || label.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"), "Content slot name");
        Slot slot = new Slot(label, slots.size(), lambda.getBodyExpression());
        slots.add(slot);
        return new Argument(label, null, null, lambda, slot, null);
    }

    private static boolean eventLambda(KtLambdaExpression lambda, Hook hook) {
        List<KtParameter> parameters = lambda.getFunctionLiteral().getValueParameters();
        if (parameters.size() > 1) return false;
        if (parameters.size() == 1 && (parameters.get(0).getDestructuringDeclaration() != null || parameters.get(0).getTypeReference() != null)) return false;
        for (KtExpression s : lambda.getBodyExpression().getStatements()) {
            boolean set = s instanceof KtBinaryExpression && ((KtBinaryExpression) s).getOperationToken() == KtTokens.EQ
                && ((KtBinaryExpression) s).getLeft() instanceof KtNameReferenceExpression
                && hook.state.containsKey(((KtNameReferenceExpression) ((KtBinaryExpression) s).getLeft()).getReferencedName());
            boolean call = s instanceof KtCallExpression && ((KtCallExpression) s).getCalleeExpression() instanceof KtNameReferenceExpression
                && hook.callbacks.containsKey(((KtNameReferenceExpression) ((KtCallExpression) s).getCalleeExpression()).getReferencedName())
                && ((KtCallExpression) s).getLambdaArguments().isEmpty() && ((KtCallExpression) s).getTypeArguments().isEmpty();
            if (!set && !call) return false;
        }
        return true;
    }

    private static boolean readsIt(KtLambdaExpression lambda) {
        for (KtNameReferenceExpression r : PsiTreeUtil.findChildrenOfType(lambda.getBodyExpression(), KtNameReferenceExpression.class)) {
            if (r.getReferencedName().equals("it")) return true;
        }
        return false;
    }

    private static String kotlinType(String type) {
        return "kotlin." + type;
    }

    /** The copied call, reading data from [call] and forwarding content and callbacks to it. */
    private static String callText(Shape s, String call, int depth) {
        StringBuilder text = new StringBuilder(s.written).append('(');
        int data = 0;
        for (int i = 0; i < s.arguments.size(); i++) {
            Argument a = s.arguments.get(i);
            if (i > 0) text.append(", ");
            if (a.name != null) text.append(a.name).append(" = ");
            if (a.slot != null) text.append(slotText(s, a.slot, call, depth));
            else if (a.event != null) text.append(eventText(a.event, call));
            else if (a.type != null) text.append('(').append(call).append(".arguments[").append(data++).append("] as ").append(kotlinType(a.type)).append(')');
            else text.append(a.frozen);
        }
        text.append(')');
        if (s.trailing != null) text.append(' ').append(s.trailing.slot != null ? slotText(s, s.trailing.slot, call, depth) : eventText(s.trailing.event, call));
        return text.toString();
    }

    private static String slotText(Shape s, Slot slot, String call, int depth) {
        String indent = "    ".repeat(depth + 2);
        String id = "dootahChild" + (depth + 1), child = "dootahCall" + (depth + 1);
        StringBuilder text = new StringBuilder("{\n").append(indent).append(call).append(".content(").append(slot.index).append(") { ")
            .append(id).append(", ").append(child).append(" ->\n").append(indent).append("    when (").append(id).append(") {\n");
        for (Shape c : slot.children.values()) {
            text.append(indent).append("        \"").append(c.id).append("\" -> ").append(callText(c, child, depth + 2)).append('\n');
        }
        return text.append(indent).append("        else -> kotlin.error(\"Unknown native child\")\n").append(indent).append("    }\n")
            .append(indent).append("}\n").append("    ".repeat(depth + 1)).append('}').toString();
    }

    private static String eventText(Event e, String call) {
        if (e.parameter == null) return "{ " + call + ".event(" + e.index + ") }";
        if (e.parameter.equals("it")) return "{ " + call + ".event(" + e.index + ", it) }";
        return "{ " + e.parameter + " -> " + call + ".event(" + e.index + ", " + e.parameter + ") }";
    }

    /** One generated file per source file: same package and imports, so frozen text resolves identically. */
    public static String render(KtFile file, Result result) {
        if (result.adapters.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        out.append("// Generated by Dootah from ").append(result.path).append(". Do not edit.\n");
        out.append("@file:kotlin.jvm.JvmName(\"").append(result.facade).append("\")\n");
        if (!result.packageName.isEmpty()) out.append("package ").append(result.packageName).append("\n");
        out.append('\n');
        for (KtImportDirective d : file.getImportDirectives()) out.append(d.getText().trim()).append('\n');
        out.append('\n');
        for (Shape s : result.adapters) {
            out.append("@androidx.compose.runtime.Composable\nprivate fun ").append(s.method)
                .append("(dootahCall: dev.dootah.runtime.NativeCall) {\n    ").append(callText(s, "dootahCall", 0)).append("\n}\n\n");
        }
        out.append("internal object ").append(result.registry).append(" : dev.dootah.runtime.NativeAdapterRegistry {\n");
        out.append("    override val signatures: kotlin.collections.Map<kotlin.String, kotlin.String> = kotlin.collections.mapOf(\n");
        for (Map.Entry<String, String> e : result.signatures().entrySet()) {
            out.append("        kotlin.Pair(\"").append(e.getKey()).append("\", \"").append(e.getValue()).append("\"),\n");
        }
        out.append("    )\n\n    @androidx.compose.runtime.Composable\n");
        out.append("    override fun render(id: kotlin.String, call: dev.dootah.runtime.NativeCall) {\n        when (id) {\n");
        for (Shape s : result.adapters) out.append("            \"").append(s.id).append("\" -> ").append(s.method).append("(call)\n");
        out.append("            else -> kotlin.error(\"Unknown native adapter\")\n        }\n    }\n}\n");
        return out.toString();
    }

    /** The single class the SDK links against. Always generated, even when empty. */
    public static String index(List<Result> results) {
        List<String> registries = new ArrayList<>();
        for (Result r : results) if (!r.adapters.isEmpty()) registries.add(r.qualifiedRegistry());
        Collections.sort(registries);
        StringBuilder out = new StringBuilder("// Generated by Dootah. Do not edit.\npackage ").append(INDEX_PACKAGE).append("\n\n");
        out.append("public object ").append(INDEX).append(" {\n    @kotlin.jvm.JvmStatic\n    public fun registry(): kotlin.Any = ");
        if (registries.isEmpty()) out.append("dev.dootah.runtime.NativeAdapterRegistry.Empty\n");
        else out.append("dev.dootah.runtime.NativeAdapterRegistry.of(\n        ").append(String.join(",\n        ", registries)).append(",\n    )\n");
        return out.append("}\n").toString();
    }
}
