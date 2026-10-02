package dev.valkdz.cdisc.audio.sabr;

import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.ElementGet;
import org.mozilla.javascript.ast.ExpressionStatement;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.KeywordLiteral;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NumberLiteral;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.StringLiteral;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.VariableInitializer;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YoutubeCipher {

    // The iframe API writes the path JSON-escaped (\/s\/player\/...), the embed page does not.
    private static final Pattern PLAYER_ID = Pattern.compile("\\\\?/s\\\\?/player\\\\?/([0-9a-f]{8})\\\\?/");
    private static final Pattern TIMESTAMP = Pattern.compile("(?:signatureTimestamp|sts)\\s*:\\s*(\\d{5})");
    private static final long PLAYER_ID_TTL_MS = 60 * 60 * 1000L;
    private static final int KEPT_PLAYERS = 2;

    private static final String SETUP = String.join("\n",
            "var XMLHttpRequest = { prototype: {} };",
            "var location = { hash: '', host: 'www.youtube.com', hostname: 'www.youtube.com',",
            "  href: 'https://www.youtube.com/watch?v=cdisc', origin: 'https://www.youtube.com',",
            "  password: '', pathname: '/watch', port: '', protocol: 'https:', search: '?v=cdisc', username: '' };",
            "var document = Object.create(null);",
            "var navigator = Object.create(null);",
            "var self = this;",
            "var window = this;",
            "var _cdisc = {};");

    private final HttpClient http;
    private final Map<String, Solver> solvers = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Solver> eldest) {
            return size() > KEPT_PLAYERS;
        }
    };

    private final AtomicBoolean refreshing = new AtomicBoolean();
    private String currentPlayer;
    private long currentPlayerUntil;

    public YoutubeCipher(HttpClient http) {
        this.http = http;
    }

    public static String playerIdIn(String text) {
        if (text == null) return null;
        Matcher found = PLAYER_ID.matcher(text);
        return found.find() ? found.group(1) : null;
    }

    public void warmUp() {
        refreshLater();
    }

    public String currentPlayerId() throws IOException {
        String known;
        boolean stale;
        synchronized (this) {
            known = currentPlayer;
            stale = System.currentTimeMillis() > currentPlayerUntil;
        }
        if (known == null) return refreshPlayer();
        if (stale) refreshLater();
        return known;
    }

    // The new player is read before it replaces the old one, so no track waits on Rhino.
    private String refreshPlayer() throws IOException {
        String id = playerIdIn(get("https://www.youtube.com/iframe_api"));
        if (id == null) throw new IOException("the iframe API named no player");
        solver(id);

        synchronized (this) {
            currentPlayer = id;
            currentPlayerUntil = System.currentTimeMillis() + PLAYER_ID_TTL_MS;
        }
        return id;
    }

    private void refreshLater() {
        if (!refreshing.compareAndSet(false, true)) return;

        CompletableFuture.runAsync(() -> {
            try {
                refreshPlayer();
            } catch (IOException ignored) {
            } finally {
                refreshing.set(false);
            }
        });
    }

    public int signatureTimestamp(String playerId) throws IOException {
        return solver(playerId).timestamp;
    }

    public String resolve(String playerId, String url, String signature, String signatureKey)
            throws IOException {
        if (signature == null && queryParam(url, "n") == null) return url;

        Solver solver = solver(playerId);
        String resolved = url;

        if (signature != null) {
            String key = signatureKey == null || signatureKey.isBlank() ? "sig" : signatureKey;
            resolved += (resolved.contains("?") ? "&" : "?") + key + "="
                    + URLEncoder.encode(solver.solve("sig", signature), StandardCharsets.UTF_8);
        }

        String n = queryParam(resolved, "n");
        if (n != null) {
            String solved = URLEncoder.encode(
                    solver.solve("n", URLDecoder.decode(n, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            resolved = resolved.replaceFirst("([?&])n=" + Pattern.quote(n) + "(?=&|$)",
                    "$1n=" + Matcher.quoteReplacement(solved));
        }
        return resolved;
    }

    private Solver solver(String playerId) throws IOException {
        synchronized (solvers) {
            Solver known = solvers.get(playerId);
            if (known != null) return known;
        }

        String script = get("https://www.youtube.com/s/player/" + playerId + "/player_ias.vflset/en_US/base.js");
        Solver made;
        try {
            made = Solver.load(script);
        } catch (RuntimeException e) {
            throw new IOException("couldn't read player " + playerId + ": " + e.getMessage(), e);
        }

        synchronized (solvers) {
            solvers.put(playerId, made);
        }
        return made;
    }

    private String get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36")
                .GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException(url + " answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + url, e);
        }
    }

    private static String queryParam(String url, String key) {
        int start = url.indexOf('?');
        if (start < 0) return null;

        for (String pair : url.substring(start + 1).split("&")) {
            if (pair.startsWith(key + "=")) return pair.substring(key.length() + 1);
        }
        return null;
    }

    private static final class Solver {

        private final Scriptable scope;
        private final List<Function> candidates;
        private final int timestamp;

        private Solver(Scriptable scope, List<Function> candidates, int timestamp) {
            this.scope = scope;
            this.candidates = candidates;
            this.timestamp = timestamp;
        }

        static Solver load(String script) {
            Matcher sts = TIMESTAMP.matcher(script);
            int timestamp = sts.find() ? Integer.parseInt(sts.group(1)) : 0;

            CompilerEnvirons env = new CompilerEnvirons();
            env.setLanguageVersion(Context.VERSION_ES6);
            env.setRecordingComments(false);
            AstRoot root = new Parser(env).parse(script, "player.js", 1);

            AstNode body = wrapper(root).getBody();
            boolean windowFirst = root.getFirstChild() != root.getLastChild();
            StringBuilder source = new StringBuilder(script);
            List<String> names = new ArrayList<>();

            for (Node child = body.getFirstChild(); child != null; child = child.getNext()) {
                AstNode statement = (AstNode) child;
                if (windowFirst) {
                    windowFirst = false;
                    blank(source, statement);
                    continue;
                }
                if (statement instanceof ExpressionStatement expression && !keeps(expression.getExpression())) {
                    blank(source, statement);
                    continue;
                }
                String name = urlBuilder(statement, script);
                if (name != null) names.add(name);
            }
            if (names.isEmpty()) throw new IllegalStateException("no URL builder in the player");

            StringBuilder injected = new StringBuilder(";_cdisc.solvers=[");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) injected.append(',');
                injected.append(solverFor(names.get(i)));
            }
            injected.append("];");

            int close = body.getAbsolutePosition() + body.getLength() - 1;
            if (source.charAt(close) != '}') throw new IllegalStateException("the player body ends oddly");
            source.insert(close, injected);

            Context cx = enter();
            try {
                Scriptable scope = cx.initStandardObjects();
                cx.evaluateString(scope, SETUP, "setup.js", 1, null);
                cx.evaluateString(scope, source.toString(), "player.js", 1, null);

                Scriptable result = (Scriptable) scope.get("_cdisc", scope);
                List<Function> candidates = new ArrayList<>();
                for (Object solver : ((NativeArray) result.get("solvers", result)).toArray()) {
                    candidates.add((Function) solver);
                }
                return new Solver(scope, candidates, timestamp);
            } finally {
                Context.exit();
            }
        }

        synchronized String solve(String kind, String value) {
            Context cx = enter();
            try {
                Set<String> answers = new LinkedHashSet<>();
                List<String> errors = new ArrayList<>();
                for (Function candidate : candidates) {
                    try {
                        Scriptable input = cx.newObject(scope);
                        input.put(kind, input, value);
                        Scriptable output = (Scriptable) candidate.call(cx, scope, scope, new Object[]{input});
                        Object answer = output.get(kind, output);
                        if (answer != null && answer != Scriptable.NOT_FOUND && !(answer instanceof Undefined)) {
                            answers.add(Context.toString(answer));
                        }
                    } catch (RuntimeException e) {
                        errors.add(e.getMessage());
                    }
                }
                if (answers.size() != 1) {
                    throw new IllegalStateException("the player solved " + kind + " " + answers.size()
                            + " ways" + (errors.isEmpty() ? "" : ": " + errors));
                }
                return answers.iterator().next();
            } finally {
                Context.exit();
            }
        }

        private static Context enter() {
            Context cx = Context.enter();
            cx.setLanguageVersion(Context.VERSION_ES6);
            // Compiled mode overflows the 64 KB JVM method limit on a three-megabyte player.
            cx.setOptimizationLevel(-1);
            return cx;
        }

        private static FunctionNode wrapper(AstRoot root) {
            if (!(root.getLastChild() instanceof ExpressionStatement statement)
                    || !(statement.getExpression() instanceof FunctionCall call)) {
                throw new IllegalStateException("the player is not one wrapped call");
            }
            AstNode target = call.getTarget();
            if (target instanceof PropertyGet member) target = member.getTarget();
            while (target instanceof ParenthesizedExpression parens) target = parens.getExpression();
            if (!(target instanceof FunctionNode function)) {
                throw new IllegalStateException("the player is not wrapped in a function");
            }
            return function;
        }

        private static boolean keeps(AstNode expression) {
            return expression instanceof Assignment || expression instanceof StringLiteral
                    || expression instanceof NumberLiteral || expression instanceof KeywordLiteral;
        }

        private static String urlBuilder(AstNode statement, String script) {
            if (statement instanceof ExpressionStatement expression
                    && expression.getExpression() instanceof Assignment assignment
                    && "=".equals(AstNode.operatorToString(assignment.getOperator()))
                    && assignment.getRight() instanceof FunctionNode function
                    && (assignment.getLeft() instanceof Name || assignment.getLeft() instanceof PropertyGet
                        || assignment.getLeft() instanceof ElementGet)) {
                return marked(function) ? text(script, assignment.getLeft()) : null;
            }
            if (statement instanceof FunctionNode function && function.getFunctionName() != null) {
                return marked(function) ? function.getFunctionName().getIdentifier() : null;
            }
            if (statement instanceof VariableDeclaration declaration) {
                for (VariableInitializer variable : declaration.getVariables()) {
                    if (variable.getInitializer() instanceof FunctionNode function
                            && variable.getTarget() instanceof Name name && marked(function)) {
                        return name.getIdentifier();
                    }
                }
            }
            return null;
        }

        // yt-dlp's ejs finds the player's URL class by this call; its first own method
        // applies both the signature and the n transforms.
        private static boolean marked(FunctionNode function) {
            for (Node child = function.getBody().getFirstChild(); child != null; child = child.getNext()) {
                if (!(child instanceof ExpressionStatement statement)
                        || !(statement.getExpression() instanceof FunctionCall call)) continue;

                AstNode target = call.getTarget();
                AstNode object = target instanceof PropertyGet member ? member.getTarget()
                        : target instanceof ElementGet element ? element.getTarget() : null;
                List<AstNode> args = call.getArguments();
                if (object instanceof Name && args.size() == 2
                        && args.get(0) instanceof StringLiteral first && "alr".equals(first.getValue())
                        && args.get(1) instanceof StringLiteral second && "yes".equals(second.getValue())) {
                    return true;
                }
            }
            return false;
        }

        private static String solverFor(String builder) {
            return "function(input){"
                    + "var url=(" + builder + ")('https://youtube.com/watch?v=cdisc','s',"
                    + "input.sig?encodeURIComponent(input.sig):undefined);"
                    + "url.set('n',input.n);"
                    + "var proto=Object.getPrototypeOf(url);"
                    + "var keys=Object.keys(proto).concat(Object.getOwnPropertyNames(proto));"
                    + "for(var i=0;i<keys.length;i++){"
                    + "if(['constructor','set','get','clone'].indexOf(keys[i])<0){url[keys[i]]();break;}}"
                    + "var s=url.get('s');var n=url.get('n');"
                    + "return {sig:s?decodeURIComponent(s):null,n:n==null?null:n};}";
        }

        private static String text(String script, AstNode node) {
            int at = node.getAbsolutePosition();
            return script.substring(at, at + node.getLength());
        }

        private static void blank(StringBuilder source, AstNode node) {
            int at = node.getAbsolutePosition();
            int end = at + node.getLength();
            source.setCharAt(at, ';');
            for (int i = at + 1; i < end; i++) {
                if (source.charAt(i) != '\n') source.setCharAt(i, ' ');
            }
        }
    }
}
