package dev.valkdz.cdisc.feature.karaoke;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VariantFinder {

    public enum Kind {
        MINUS,
        UNCENSORED
    }

    public record Song(String artist, String name, long durationMs, String uri) {

        public static Song of(String title, String author, long durationMs, String uri) {
            String clean = NOISE.matcher(title == null ? "" : title).replaceAll(" ").replaceAll("\\s+", " ").trim();
            Matcher dash = DASH.matcher(clean);
            if (dash.find()) {
                return new Song(clean.substring(0, dash.start()).trim(), clean.substring(dash.end()).trim(), durationMs, uri);
            }
            String artist = author == null ? "" : author.replaceFirst("\\s*-\\s*Topic$", "").trim();
            return new Song(artist, clean, durationMs, uri);
        }
    }

    public record Hit(String source, String uri, String title, String author, long durationMs) {
    }

    public record Candidate(Hit hit, double score) {
    }

    public interface Source {

        String name();

        List<Hit> search(String query) throws IOException, InterruptedException;
    }

    public static final String YOUTUBE = "youtube";
    public static final String SOUNDCLOUD = "soundcloud";

    private static final Pattern NOISE = Pattern.compile(
            "[(\\[][^)\\]]*(official|video|клип|lyric|текст|audio|премьера|premiere|visuali[sz]er|\\bmv\\b|\\bhd\\b|4k"
                    + "|kbps|\\bhq\\b|flac|mp3|lossless|качеств)[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DASH = Pattern.compile("\\s[-–—]\\s|\\s[–—]|[–—]\\s");
    private static final Pattern CREDIT = Pattern.compile("\\s*(?:,|&|\\b(?:feat|ft)\\b\\.?)\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final int ARTISTS_ASKED = 3;
    private static final Pattern MINUS = Pattern.compile(
            "минус|\\bminus\\b|instrumental|инструментал|karaoke|караоке|без слов|backing track");
    private static final Pattern WRONG = Pattern.compile(
            "slowed|sped up|speed up|nightcore|reverb|\\b8d\\b|bass ?boost|remix|ремикс|\\bcover\\b|кавер|reaction|реакция"
                    + "|\\blive\\b|лайв|mashup|мэшап|tutorial|урок|type beat"
                    + "|перевод|translation|\\bloop\\b|extended|1 hour|1 час|\\bmix\\b|сборник|playlist|плейлист"
                    + "|remake|ремейк|mylancore");
    // Read last: such a cut is still listened to, and the sound decides what it holds.
    private static final Pattern UNLIKELY = Pattern.compile(
            "a ?cc?app?ella|акапелла|vocals? only|only vocals"
                    + "|(?:guitar|drums?|bass|piano) (?:backing|play ?along)|drumless|bassless|guitarless|no (?:drums|guitar|bass)"
                    + "|(?:без|для) (?:гитар|барабан|бас)|гитарн\\p{L}* (?:минус|подложк)");
    private static final Pattern CENSORED = Pattern.compile(
            "без мата|\\bclean\\b|(?<!un)censored|(?<!не)цензурн|radio edit|\\bedit\\b");
    private static final Pattern UNCENSORED = Pattern.compile(
            "без цензуры|uncensored|explicit|нецензурн|\\b18\\b");

    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Executor executor;

    public VariantFinder(List<Source> sources) {
        this(sources, Runnable::run);
    }

    // Every query of every source is asked at once on executor; a source that fails is left out.
    public VariantFinder(List<Source> sources, Executor executor) {
        for (Source source : sources) this.sources.put(source.name(), source);
        this.executor = executor;
    }

    public List<Candidate> find(Song song, Kind kind, int limit) throws InterruptedException {
        return rank(song, kind, gather(song, kind), limit);
    }

    public List<Hit> gather(Song song, Kind kind) throws InterruptedException {
        List<CompletableFuture<List<Hit>>> asked = new ArrayList<>();
        for (String name : order(kind)) {
            Source source = sources.get(name);
            if (source == null) continue;
            for (String query : queries(song, kind, name)) {
                asked.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return source.search(query);
                    } catch (IOException | RuntimeException e) {
                        return List.<Hit>of();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return List.<Hit>of();
                    }
                }, executor));
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (CompletableFuture<List<Hit>> answer : asked) {
            try {
                hits.addAll(answer.get());
            } catch (ExecutionException ignored) {
            }
        }
        return hits;
    }

    public static List<Candidate> rank(Song song, Kind kind, List<Hit> hits, int limit) {
        List<String> order = order(kind);
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Hit hit : hits) {
            double score = score(song, kind, hit);
            if (score > 0) best.merge(hit.uri(), new Candidate(hit, score), (a, b) -> a.score() >= b.score() ? a : b);
        }
        List<Candidate> ranked = new ArrayList<>();
        if (kind == Kind.MINUS) {
            interleave(order, best.values().stream().filter(c -> marked(c.hit())).toList(), limit, ranked);
            interleave(order, best.values().stream().filter(c -> !marked(c.hit())).toList(), limit, ranked);
        } else {
            interleave(order, List.copyOf(best.values()), limit, ranked);
        }
        return ranked;
    }

    // The second source gets every third place rather than whatever the first leaves over.
    private static void interleave(List<String> order, List<Candidate> candidates, int limit, List<Candidate> ranked) {
        List<Candidate> first = new ArrayList<>();
        List<Candidate> second = new ArrayList<>();
        for (Candidate c : candidates) (order.indexOf(c.hit().source()) == 0 ? first : second).add(c);
        Comparator<Candidate> byScore = Comparator.comparingDouble(Candidate::score).reversed();
        first.sort(byScore);
        second.sort(byScore);
        int a = 0;
        int b = 0;
        for (int placed = 0; ranked.size() < limit && (a < first.size() || b < second.size()); placed++) {
            boolean secondsTurn = placed % 3 == 2 || a >= first.size();
            ranked.add(secondsTurn && b < second.size() ? second.get(b++) : first.get(a++));
        }
    }

    private static boolean marked(Hit hit) {
        return MINUS.matcher(normalize(hit.title())).find();
    }

    static List<String> order(Kind kind) {
        return kind == Kind.MINUS ? List.of(YOUTUBE, SOUNDCLOUD) : List.of(SOUNDCLOUD, YOUTUBE);
    }

    static List<String> queries(Song song, Kind kind, String source) {
        List<String> queries = new ArrayList<>();
        List<String> bases = new ArrayList<>(bases(song));
        if (kind == Kind.MINUS && !bases.contains(song.name())) bases.add(song.name());
        for (String base : bases) {
            if (kind == Kind.MINUS) {
                queries.add(base + " минус");
                queries.add(base + " instrumental");
                queries.add(base + " караоке");
            } else if (source.equals(SOUNDCLOUD)) {
                queries.add(base);
            } else {
                queries.add(base + " без цензуры");
                queries.add(base);
            }
        }
        return queries;
    }

    // A song credited to several artists is often uploaded under only one of them.
    static List<String> bases(Song song) {
        String credit = song.artist().trim();
        if (credit.isEmpty()) return List.of(song.name());
        List<String> bases = new ArrayList<>();
        bases.add(credit.replace(",", "") + " " + song.name());
        List<String> artists = new ArrayList<>();
        for (String artist : CREDIT.split(credit)) {
            if (!artist.isBlank() && !artists.contains(artist.trim())) artists.add(artist.trim());
        }
        if (artists.size() > 1) {
            for (String artist : artists.subList(0, Math.min(ARTISTS_ASKED, artists.size()))) {
                bases.add(artist + " " + song.name());
            }
        }
        return bases;
    }

    static double score(Song song, Kind kind, Hit hit) {
        if (hit.uri() != null && hit.uri().equals(song.uri())) return -1;
        String title = normalize(hit.title());
        String text = title + " " + normalize(hit.author());
        if (WRONG.matcher(title).find()) return -1;

        boolean minus = MINUS.matcher(title).find();
        if (kind == Kind.UNCENSORED && (minus || CENSORED.matcher(title).find())) return -1;

        boolean named = containsAll(text, normalize(song.name()));
        boolean byArtist = containsAny(text, normalize(song.artist()));
        if (!named && !byArtist) return -1;

        if (song.durationMs() > 0 && hit.durationMs() > 0 && !lengthFits(song.durationMs(), hit.durationMs())) return -1;

        double score = (named ? 1.0 : 0.3) + (byArtist ? 0.5 : 0);
        if (kind == Kind.MINUS) score += minus ? 1.5 : -1.0;
        if (UNLIKELY.matcher(title).find()) score -= 1.5;
        if (containsAny(normalize(hit.author()), normalize(song.artist()))) score += 0.5;
        if (title.contains("official") || title.contains("официальн")) score += 0.2;
        if (kind == Kind.UNCENSORED && UNCENSORED.matcher(title).find()) score += 0.5;
        if (hit.author() != null && hit.author().endsWith(" - Topic")) score += 0.4;
        if (song.durationMs() > 0 && hit.durationMs() > 0) {
            score -= Math.abs(hit.durationMs() - song.durationMs()) / 60_000.0 * 0.5;
        }
        return score;
    }

    static boolean lengthFits(long songMs, long hitMs) {
        return hitMs <= songMs + 20_000 && hitMs >= songMs * 0.6;
    }

    static boolean markedUncensored(String title) {
        return UNCENSORED.matcher(normalize(title)).find();
    }

    static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}+]+", " ").trim();
    }

    private static boolean containsAll(String text, String words) {
        if (words.isBlank()) return false;
        String padded = " " + text + " ";
        for (String word : words.split(" ")) {
            if (!padded.contains(" " + word + " ")) return false;
        }
        return true;
    }

    private static boolean containsAny(String text, String words) {
        String padded = " " + text + " ";
        for (String word : words.split(" ")) {
            if (word.length() >= 2 && padded.contains(" " + word + " ")) return true;
        }
        return false;
    }
}
