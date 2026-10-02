package dev.valkdz.cdisc.audio.player;

// A search or playlist entry: what it plays is looked up only when it starts.
public class LazyTrack extends AudioTrack {

    public interface Resolver {
        AudioTrack resolve(AudioTrackInfo info) throws Exception;
    }

    private final AudioSourceManager sourceManager;
    private final Resolver resolver;

    public LazyTrack(AudioTrackInfo trackInfo, AudioSourceManager sourceManager, Resolver resolver) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.resolver = resolver;
    }

    @Override
    public void process(Playback playback) throws Exception {
        AudioTrack resolved = resolver.resolve(trackInfo);
        if (resolved == null) throw new LoadException("Nothing could be found to play \"" + trackInfo.title + "\"");
        resolved.process(playback);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new LazyTrack(trackInfo, sourceManager, resolver);
    }
}
